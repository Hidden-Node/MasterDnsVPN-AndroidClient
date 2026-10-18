//go:build linux

// Package tun — gvisor_bridge_linux_test.go
//
// Linux-only lifecycle accounting for the gVisor bridge. Needs
// /proc/self/fd and real dup semantics, so it is gated to linux and
// executes on CI's ubuntu runner (GOOS=linux vet type-checks it on
// other hosts).
// ==============================================================================
package tun

import (
	"os"
	"testing"
)

// TestGvisorStackFdAccounting starts/stops the bridge against /dev/null
// (a valid fd that stack setup accepts) and asserts the process fd count is
// unchanged: proves the dup'd TUN fd is closed on the shutdown path.
// /dev/null proves fd ACCOUNTING only, not traffic — traffic validation
// belongs to plan 073's device check. The setup-error close is one
// syscall.Close line on each exclusive path (review-verified); forcing
// fdbased.New to fail deterministically is unreliable, so it is
// intentionally NOT covered here.
func TestGvisorStackFdAccounting(t *testing.T) {
	f, err := os.OpenFile("/dev/null", os.O_RDWR, 0)
	if err != nil {
		t.Fatalf("open /dev/null: %v", err)
	}
	defer func() { _ = f.Close() }()

	fds, _ := os.ReadDir("/proc/self/fd")
	before := len(fds)

	if err := StartTunGvisor(int64(f.Fd()), 1500, "127.0.0.1:1", "", "", ""); err != nil {
		t.Fatalf("StartTunGvisor against /dev/null: %v", err)
	}

	StopTunGvisor()

	afterFds, _ := os.ReadDir("/proc/self/fd")
	after := len(afterFds)
	if after != before {
		t.Fatalf("fd count changed across start/stop: before=%d after=%d", before, after)
	}
}
