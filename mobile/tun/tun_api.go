// Package tun provides gomobile-compatible API for TUN bridge
package tun

import (
	"fmt"
	"log"
	"sync"
)

// GetVersion returns the TUN module version
// This is a simple test function to verify gomobile binding works
func GetVersion() string {
	return "1.0.0"
}

var (
	bridgeMu     sync.Mutex
	activeBridge *Bridge
)

// StartTunBridge starts the TUN bridge with DNS interception
// This is called from Android after VPN interface is established
//
// Parameters:
//   tunFd - File descriptor from Android VpnService.establish()
//   mtu - MTU of the TUN interface (typically 1500)
//   socksAddr - Address of local SOCKS5 proxy (e.g. "127.0.0.1:1080")
//
// Returns: error if bridge fails to start
//
func StartTunBridge(tunFd int32, mtu int32, socksAddr string) error {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if activeBridge != nil {
		return fmt.Errorf("TUN bridge already running")
	}
	
	if socksAddr == "" {
		return fmt.Errorf("socksAddr cannot be empty")
	}
	
	if tunFd < 0 {
		return fmt.Errorf("invalid tunFd: %d", tunFd)
	}
	
	log.Printf("[TUN-API] Starting TUN bridge: fd=%d mtu=%d socks=%s", tunFd, mtu, socksAddr)
	
	// Create bridge with nil protect function (will be set by Android)
	bridge := NewBridge(int(tunFd), socksAddr, nil)
	
	// Start bridge
	if err := bridge.Start(); err != nil {
		return fmt.Errorf("failed to start bridge: %v", err)
	}
	
	activeBridge = bridge
	log.Printf("[TUN-API] TUN bridge started successfully")
	
	return nil
}

// StopTunBridge stops the TUN bridge
//
func StopTunBridge() {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if activeBridge == nil {
		return
	}
	
	log.Printf("[TUN-API] Stopping TUN bridge")
	activeBridge.Stop()
	activeBridge = nil
	log.Printf("[TUN-API] TUN bridge stopped")
}

// IsTunBridgeRunning returns true if bridge is active
//
func IsTunBridgeRunning() bool {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	return activeBridge != nil
}

// GetTunBandwidth returns upload and download bytes
//
func GetTunBandwidth() (up int64, down int64) {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if activeBridge == nil {
		return 0, 0
	}
	
	return activeBridge.GetBandwidth()
}

// NOTE: SetProtectFunc is not exposed via gomobile because it takes a function parameter
// which is not supported by gomobile. Socket protection is not critical for this use case
// since we're only connecting to localhost SOCKS5 proxy.
//
// func SetProtectFunc(protectFn func(int) bool) {
// 	bridgeMu.Lock()
// 	defer bridgeMu.Unlock()
// 	if activeBridge != nil {
// 		activeBridge.protectFn = protectFn
// 	}
// }

// GetDNSMapping returns the hostname for a fake IP
// Returns empty string if not found
//
func GetDNSMapping(fakeIP string) string {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if activeBridge == nil {
		return ""
	}
	
	hostname, ok := activeBridge.dnsMap.GetHostname(fakeIP)
	if !ok {
		return ""
	}
	
	return hostname
}

// GetDNSMappingCount returns the number of DNS mappings
//
func GetDNSMappingCount() int {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if activeBridge == nil {
		return 0
	}
	
	activeBridge.dnsMap.mu.RLock()
	count := len(activeBridge.dnsMap.hostnameToIP)
	activeBridge.dnsMap.mu.RUnlock()
	
	return count
}
