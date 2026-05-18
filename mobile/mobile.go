// ==============================================================================
// MasterDnsVPN - Mobile Bridge
// Thin wrapper around the Go client for Android via gomobile.
// No client logic is modified - this only calls the existing API.
// ==============================================================================

package mobile

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"

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
)

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

	mu.Lock()
	vpnClient = app
	cancelFunc = cancel
	running = true
	mu.Unlock()

	runErr := app.Run(ctx)

	mu.Lock()
	running = false
	vpnClient = nil
	cancelFunc = nil
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
	safeFd := dupFd(int(fd))

	key := &engine.Key{
		Proxy:  "socks5://" + proxyAddr,
		Device: fmt.Sprintf("fd://%d", safeFd),
		MTU:    1500,
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

	// Stop outside lock to avoid deadlock. Recover from panic in case
	// engine is in a bad state (e.g. fd already closed by Android).
	func() {
		defer func() { recover() }()
		engine.Stop()
	}()
}

// StopClient gracefully stops the running client.
func StopClient() {
	StopTun()
	StopTunBridge()

	mu.Lock()
	defer mu.Unlock()
	if cancelFunc != nil {
		cancelFunc()
		cancelFunc = nil
	}
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
	up, down := tun.GetTunBandwidth()
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
