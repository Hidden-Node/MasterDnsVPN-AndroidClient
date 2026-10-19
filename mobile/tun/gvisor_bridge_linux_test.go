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
	"time"
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

func TestGvisorStopAfterFailedStart(t *testing.T) {
	anchor := t.TempDir() + "/anchor"
	if err := os.WriteFile(anchor, []byte("x"), 0644); err != nil {
		t.Fatalf("anchor write: %v", err)
	}
	badLogPath := anchor + "/bridge.log"

	// Pre-spawned stoppers: the publish→unwind window is ~2 mutex
	// sections + one failing syscall — far narrower than goroutine-spawn
	// latency, so a stopper spawned AFTER the failing start can never
	// observe it (reviewer-proved: 200 sequential rounds pass on
	// unfixed code in 0.00 s). A stopper already blocked in Stop BEFORE
	// the failing start hits the window on round 0.
	for i := 0; i < 2000; i++ {
		stopped := make(chan struct{})
		go func() {
			StopTunGvisor()
			close(stopped)
		}()
		if err := StartTunGvisor(3, 1500, "127.0.0.1:1", "", "", badLogPath); err == nil {
			t.Fatalf("round %d: bad-log start must fail", i)
		}
		select {
		case <-stopped:
		case <-time.After(2 * time.Second):
			t.Fatalf("round %d: stop blocked after failed start", i)
		}
	}

	if err := StartTunGvisor(3, 1500, "127.0.0.1:1", "", "", ""); err != nil {
		t.Fatalf("bridge must be restartable after a failed start: %v", err)
	}
	StopTunGvisor()
}
