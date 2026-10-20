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
	"time"

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"masterdnsvpn-go/mobile/tun"
)

var (
	mu         sync.Mutex
	vpnClient  *client.Client
	cancelFunc context.CancelFunc
	running    bool
	clientDone chan struct{} // closed when app.Run(ctx) returns
)

// Bandwidth holds upload and download counters for gomobile bindings.
type Bandwidth struct {
	Up   int64
	Down int64
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
	tun.SetGvisorClient(vpnClient)
	cancelFunc = cancel
	clientDone = done
	running = true
	mu.Unlock()

	runErr := app.Run(ctx)

	// Signal that the client has fully stopped BEFORE clearing state.
	// StopClient waits on this channel to know it's safe to tear down the
	// gVisor bridge.
	close(done)

	mu.Lock()
	running = false
	vpnClient = nil
	tun.SetGvisorClient(nil)
	cancelFunc = nil
	clientDone = nil
	mu.Unlock()

	return runErr
}

// StopClient gracefully stops the running client.
// Order: cancel Go context first (stops traffic), wait for client to drain,
// THEN stop the gVisor bridge (no more goroutines writing to it).
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
	//    This ensures no goroutine is writing to the gVisor bridge
	//    when we tear it down in step 3.
	if done != nil {
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	}

	// 3. NOW it's safe to stop the gVisor bridge — no traffic flowing.
	StopTunGvisor()
}

// IsRunning returns true if the client is currently running.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// StartTunGvisor starts the gVisor TUN bridge (see mobile/tun).
// logPath is the same Go-core log file path the service passes to
// StartClient — bridge lines are appended there so they reach the in-app
// Logs tab via the existing tailLogFile (no logcat needed on-device).
func StartTunGvisor(tunFd int64, mtu int64, socksAddr string, socksUser string, socksPass string, logPath string) error {
	return tun.StartTunGvisor(tunFd, mtu, socksAddr, socksUser, socksPass, logPath)
}

// StopTunGvisor stops the gVisor TUN bridge. Safe to call multiple times.
func StopTunGvisor() { tun.StopTunGvisor() }

// IsTunGvisorRunning reports whether the gVisor bridge is active.
func IsTunGvisorRunning() bool { return tun.IsTunGvisorRunning() }

// GetGvisorBandwidth returns gVisor bridge upload/download counters.
func GetGvisorBandwidth() *Bandwidth {
	up, down := tun.GvisorBandwidth()
	return &Bandwidth{Up: up, Down: down}
}
