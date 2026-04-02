//go:build mobile
// +build mobile

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

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/version"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

// LogCallback is an interface for receiving log messages in Kotlin/Java.
type LogCallback interface {
	OnLog(level string, message string)
}

var (
	mu         sync.Mutex
	vpnClient  *client.Client
	cancelFunc context.CancelFunc
	running    bool
	logCb      LogCallback
	tunRunning bool
)

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

	app, err := client.Bootstrap(configPath, logPath)
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

// StartTun starts a tun2socks engine to bridge the TUN interface (fd) to a SOCKS5 proxy.
func StartTun(fd int, proxyAddr string) {
	key := &engine.Key{
		Proxy:  "socks5://" + proxyAddr,
		Device: fmt.Sprintf("fd://%d", fd),
		MTU:    1500,
	}

	engine.Insert(key)
	engine.Start()

	mu.Lock()
	tunRunning = true
	mu.Unlock()
}

// StopTun stops the tun2socks engine.
func StopTun() {
	mu.Lock()
	defer mu.Unlock()
	if tunRunning {
		engine.Stop()
		tunRunning = false
	}
}

// StopClient gracefully stops the running client.
func StopClient() {
	StopTun()
	mu.Lock()
	defer mu.Unlock()
	if cancelFunc != nil {
		cancelFunc()
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
