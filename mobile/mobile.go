// ==============================================================================
// MasterDnsVPN - Mobile Bridge
// Thin wrapper around the Go client for Android via gomobile.
// No client logic is modified - this only calls the existing API.
// ==============================================================================

package mobile

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"masterdnsvpn-go/internal/version"
	"masterdnsvpn-go/mobile/tun"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

// LogCallback is an interface for receiving log messages in Kotlin/Java.
type LogCallback interface {
	OnLog(level string, message string)
}

var (
	mu               sync.Mutex
	vpnClient        *client.Client
	cancelFunc       context.CancelFunc
	running          bool
	logCb            LogCallback
	tunRunning       bool
	tunBridgeRunning bool
	clientDone       chan struct{} // closed when app.Run(ctx) returns

	trackedUp        int64
	trackedDown      int64
	trackingListener net.Listener
	trackingMu       sync.Mutex
)

type trackingConn struct {
	net.Conn
	onRead  func(int64)
	onWrite func(int64)
}

func (t *trackingConn) Read(b []byte) (n int, err error) {
	n, err = t.Conn.Read(b)
	if n > 0 && t.onRead != nil {
		t.onRead(int64(n))
	}
	return
}

func (t *trackingConn) Write(b []byte) (n int, err error) {
	n, err = t.Conn.Write(b)
	if n > 0 && t.onWrite != nil {
		t.onWrite(int64(n))
	}
	return
}

func startTrackingProxy(realProxyAddr string) (string, error) {
	trackingMu.Lock()
	defer trackingMu.Unlock()

	if trackingListener != nil {
		trackingListener.Close()
	}

	atomic.StoreInt64(&trackedUp, 0)
	atomic.StoreInt64(&trackedDown, 0)

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}

	trackingListener = l

	go func() {
		for {
			client, err := l.Accept()
			if err != nil {
				return
			}
			go handleTracking(client, realProxyAddr)
		}
	}()

	return l.Addr().String(), nil
}

func handleTracking(c net.Conn, realProxyAddr string) {
	defer c.Close()
	server, err := net.Dial("tcp", realProxyAddr)
	if err != nil {
		return
	}
	defer server.Close()

	tcClient := &trackingConn{
		Conn:    c,
		onRead:  func(n int64) { atomic.AddInt64(&trackedUp, n) },
		onWrite: func(n int64) { atomic.AddInt64(&trackedDown, n) },
	}

	errc := make(chan error, 2)
	go func() {
		_, err := io.Copy(server, tcClient)
		errc <- err
	}()
	go func() {
		_, err := io.Copy(tcClient, server)
		errc <- err
	}()
	<-errc
}

// Bandwidth holds upload and download counters for gomobile bindings.
type Bandwidth struct {
	Up   int64
	Down int64
}

// SetLogCallback sets a callback that receives Go core log messages.
func SetLogCallback(cb LogCallback) {
	mu.Lock()
	defer mu.Unlock()
	logCb = cb
}

// StartClient starts the MasterDnsVPN client with the given config and log paths.
func StartClient(configPath string, logPath string) error {
	mu.Lock()
	if running {
		mu.Unlock()
		return fmt.Errorf("client is already running")
	}
	mu.Unlock()

	dir := filepath.Dir(configPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create config directory: %w", err)
	}

	app, err := client.Bootstrap(configPath, logPath, config.ClientConfigOverrides{})
	if err != nil {
		return fmt.Errorf("client bootstrap failed: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})

	mu.Lock()
	vpnClient = app
	cancelFunc = cancel
	clientDone = done
	running = true
	mu.Unlock()

	runErr := app.Run(ctx)

	// Signal that the client has fully stopped BEFORE clearing state.
	// StopClient waits on this channel to know it's safe to tear down tun2socks.
	close(done)

	mu.Lock()
	running = false
	vpnClient = nil
	cancelFunc = nil
	clientDone = nil
	mu.Unlock()

	return runErr
}

// dupFd duplicates a file descriptor so tun2socks and Android each own
// independent copies.  When engine.Stop() internally closes the duplicated fd,
// Android's ParcelFileDescriptor still has its original fd to close safely —
// no double-close SIGSEGV.
func dupFd(fd int) int {
	dup, err := syscall.Dup(fd)
	if err != nil {
		// Fallback: use original fd (carries double-close risk but better
		// than failing to start entirely).
		return fd
	}
	return dup
}

// StartTun starts a tun2socks engine to bridge the TUN interface (fd) to a SOCKS5 proxy.
func StartTun(fd int64, proxyAddr string) {
	trackedAddr, err := startTrackingProxy(proxyAddr)
	if err == nil {
		proxyAddr = trackedAddr
	}

	safeFd := dupFd(int(fd))

	key := &engine.Key{
		Proxy:  "socks5://" + proxyAddr,
		Device: fmt.Sprintf("fd://%d", safeFd),
		// ponytail: 1400 leaves headroom for SOCKS5/transport overhead so QUIC
		// datagrams don't exceed upstream path MTU and black-hole (issue #32).
		MTU: 1400,
	}

	engine.Insert(key)
	engine.Start()

	mu.Lock()
	tunRunning = true
	mu.Unlock()
}

// StopTun stops the tun2socks engine. Safe to call multiple times.
func StopTun() {
	mu.Lock()
	if !tunRunning {
		mu.Unlock()
		return
	}
	tunRunning = false
	mu.Unlock()

	trackingMu.Lock()
	if trackingListener != nil {
		trackingListener.Close()
		trackingListener = nil
	}
	trackingMu.Unlock()

	// Stop outside lock to avoid deadlock. Recover from panic in case
	// engine is in a bad state (e.g. fd already closed by Android).
	func() {
		defer func() { recover() }()
		engine.Stop()
	}()
}

// StopClient gracefully stops the running client.
// Order: cancel Go context first (stops traffic), wait for client to drain,
// THEN stop tun2socks (no more goroutines writing to the engine).
func StopClient() {
	// 1. Cancel context — tells app.Run(ctx) to shut down.
	mu.Lock()
	cancel := cancelFunc
	done := clientDone
	cancelFunc = nil
	mu.Unlock()

	if cancel != nil {
		cancel()
	}

	// 2. Wait for the Go client to fully stop (with timeout).
	//    This ensures no goroutine is writing to the tun2socks engine
	//    when we tear it down in step 3.
	if done != nil {
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	}

	// 3. NOW it's safe to stop tun2socks — no traffic flowing.
	StopTun()
	StopTunBridge()
}

// IsRunning returns true if the client is currently running.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// GetVersion returns the build version string of the Go core.
func GetVersion() string {
	return version.GetVersion()
}

// GetListenAddress returns the configured listen address (e.g., "127.0.0.1:18000").
// Returns empty string if client is not initialized.
func GetListenAddress() string {
	mu.Lock()
	defer mu.Unlock()
	if vpnClient == nil {
		return ""
	}
	// The listen address comes from the config, not directly exposed.
	// We'll return it via the config the user provides.
	return ""
}

// StartTunBridge starts the TUN bridge with DNS interception using FakeDNS proxy.
func StartTunBridge(tunFd int64, mtu int64, socksAddr string) error {
	proxyAddr, err := tun.StartFakeDNSProxy(socksAddr)
	if err != nil {
		return err
	}

	trackedAddr, err2 := startTrackingProxy(proxyAddr)
	if err2 == nil {
		proxyAddr = trackedAddr
	}

	safeFd := dupFd(int(tunFd))

	key := &engine.Key{
		Proxy:  "socks5://" + proxyAddr,
		Device: fmt.Sprintf("fd://%d", safeFd),
		MTU:    int(mtu),
	}

	engine.Insert(key)
	engine.Start()

	mu.Lock()
	tunBridgeRunning = true
	mu.Unlock()

	return nil
}

// StopTunBridge stops the DNS-aware TUN bridge. Safe to call multiple times.
func StopTunBridge() {
	mu.Lock()
	if !tunBridgeRunning {
		mu.Unlock()
		return
	}
	tunBridgeRunning = false
	mu.Unlock()

	trackingMu.Lock()
	if trackingListener != nil {
		trackingListener.Close()
		trackingListener = nil
	}
	trackingMu.Unlock()

	// Stop outside lock to avoid deadlock. Recover from panic in case
	// engine is in a bad state (e.g. fd already closed by Android).
	func() {
		defer func() { recover() }()
		engine.Stop()
	}()
	tun.StopFakeDNSProxy()
}

// IsTunBridgeRunning returns true if the DNS-aware TUN bridge is active.
func IsTunBridgeRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return tunBridgeRunning
}

// GetTunBandwidth returns upload/download counters from the TUN bridge.
func GetTunBandwidth() *Bandwidth {
	up := atomic.LoadInt64(&trackedUp)
	down := atomic.LoadInt64(&trackedDown)
	return &Bandwidth{
		Up:   up,
		Down: down,
	}
}

// GetDNSMapping resolves a fake IP to hostname when available.
func GetDNSMapping(fakeIP string) string {
	return tun.GetDNSMapping(fakeIP)
}

// GetDNSMappingCount returns the number of active fake DNS mappings.
func GetDNSMappingCount() int {
	return tun.GetDNSMappingCount()
}

// GetTunVersion returns the TUN bridge module version.
func GetTunVersion() string {
	return tun.GetVersion()
}
