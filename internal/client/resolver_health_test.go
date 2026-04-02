package client

import (
	"context"
	"net"
	"testing"
	"time"

	"masterdnsvpn-go/internal/config"
)

func TestResolverHealthAutoDisablesTimeoutOnlyConnection(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 10.0,
		AutoDisableMinObservations:      3,
		AutoDisableCheckIntervalSeconds: 1.0,
	}, "a", "b", "c", "d")
	c.initResolverRecheckMeta()

	base := time.Date(2026, 3, 25, 12, 0, 0, 0, time.UTC)
	current := base
	c.nowFn = func() time.Time { return current }

	c.recordResolverHealthEvent("a", false, current)
	current = base.Add(5 * time.Second)
	c.recordResolverHealthEvent("a", false, current)
	current = base.Add(10 * time.Second)
	c.recordResolverHealthEvent("a", false, current)

	c.runResolverAutoDisable(current)

	conn, ok := c.GetConnectionByKey("a")
	if !ok {
		t.Fatal("expected disabled connection to still exist")
	}
	if conn.IsValid {
		t.Fatal("expected timeout-only resolver to be disabled")
	}
	if c.balancer.ValidCount() != 3 {
		t.Fatalf("unexpected valid count after disable: got=%d want=3", c.balancer.ValidCount())
	}
	if !c.isRuntimeDisabledResolver("a") {
		t.Fatal("expected disabled resolver to be tracked for runtime recheck")
	}
}

func TestResolverHealthRecheckReactivatesConnection(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		RecheckInactiveServersEnabled:  true,
		RecheckInactiveIntervalSeconds: 60.0,
		RecheckServerIntervalSeconds:   3.0,
		RecheckBatchSize:               2,
	}, "a", "b")
	c.successMTUChecks = true
	c.syncedUploadMTU = 120
	c.syncedDownloadMTU = 180

	if !c.balancer.SetConnectionValidity("a", false) {
		t.Fatal("expected test connection a to become invalid")
	}

	if idx, ok := c.connectionsByKey["a"]; ok {
		c.connections[idx].UploadMTUBytes = 0
		c.connections[idx].DownloadMTUBytes = 0
	}

	c.initResolverRecheckMeta()
	c.recheckConnectionFn = func(conn *Connection) bool {
		return conn != nil && conn.Key == "a"
	}

	now := time.Date(2026, 3, 25, 12, 30, 0, 0, time.UTC)
	c.nowFn = func() time.Time { return now }

	c.resolverHealthMu.Lock()
	c.resolverRecheck["a"] = resolverRecheckState{
		FailCount: 2,
		NextAt:    now.Add(-time.Second),
	}
	c.runtimeDisabled["a"] = resolverDisabledState{
		DisabledAt:  now.Add(-time.Minute),
		NextRetryAt: now.Add(-time.Second),
		RetryCount:  2,
		Cause:       "timeout window",
	}
	c.resolverHealthMu.Unlock()

	c.runResolverRecheckBatch(context.Background(), now)

	conn, ok := c.GetConnectionByKey("a")
	if !ok || !conn.IsValid {
		t.Fatal("expected recheck to reactivate resolver")
	}
	if conn.UploadMTUBytes != 120 || conn.DownloadMTUBytes != 180 {
		t.Fatalf("expected synced MTUs to be restored, got up=%d down=%d", conn.UploadMTUBytes, conn.DownloadMTUBytes)
	}
	if c.balancer.ValidCount() != 2 {
		t.Fatalf("unexpected valid count after recheck: got=%d want=%d", c.balancer.ValidCount(), 2)
	}
	if c.isRuntimeDisabledResolver("a") {
		t.Fatal("expected runtime disabled marker to be cleared after reactivation")
	}
}

func TestDisableResolverClearsPreferredStreamResolverReferences(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 10.0,
		AutoDisableMinObservations:      3,
	}, "a", "b", "c", "d")
	c.initResolverRecheckMeta()

	streamA := testStream(21)
	streamA.PreferredServerKey = "a"
	streamA.ResolverResendStreak = 3
	c.active_streams[streamA.StreamID] = streamA

	streamB := testStream(22)
	streamB.PreferredServerKey = "b"
	streamB.ResolverResendStreak = 2
	c.active_streams[streamB.StreamID] = streamB

	if !c.disableResolverConnection("a", "test disable") {
		t.Fatal("expected resolver a to be disabled")
	}

	if streamA.PreferredServerKey != "" {
		t.Fatalf("expected preferred resolver for streamA to be cleared, got=%q", streamA.PreferredServerKey)
	}
	if streamA.ResolverResendStreak != 0 {
		t.Fatalf("expected resend streak for streamA to be reset, got=%d", streamA.ResolverResendStreak)
	}
	if streamB.PreferredServerKey != "b" {
		t.Fatalf("expected unrelated stream preferred resolver to stay intact, got=%q", streamB.PreferredServerKey)
	}
}

func TestResetRuntimeBindingsPreservesResolverHealthState(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		RecheckInactiveServersEnabled:  true,
		AutoDisableTimeoutServers:      true,
		RecheckInactiveIntervalSeconds: 60.0,
		RecheckServerIntervalSeconds:   3.0,
	}, "a", "b", "c", "d")
	c.successMTUChecks = true
	c.syncedUploadMTU = 120
	c.syncedDownloadMTU = 180
	c.initResolverRecheckMeta()

	now := time.Date(2026, 3, 25, 13, 0, 0, 0, time.UTC)
	c.nowFn = func() time.Time { return now }

	stream := testStream(31)
	stream.PreferredServerKey = "a"
	c.active_streams[stream.StreamID] = stream

	if !c.disableResolverConnection("a", "test disable") {
		t.Fatal("expected resolver a to be disabled")
	}

	if !c.isRuntimeDisabledResolver("a") {
		t.Fatal("expected runtime disabled state before reset")
	}

	c.resetRuntimeBindings(true)

	if !c.isRuntimeDisabledResolver("a") {
		t.Fatal("expected runtime disabled resolver state to survive runtime reset")
	}

	conn, ok := c.GetConnectionByKey("a")
	if !ok {
		t.Fatal("expected resolver a to still exist after reset")
	}
	if conn.IsValid {
		t.Fatal("expected resolver a to remain invalid after reset")
	}

	if len(c.active_streams) != 0 {
		t.Fatalf("expected streams to be cleared by runtime reset, got=%d", len(c.active_streams))
	}
}

func TestLateResolverSuccessRetractsPriorTimeoutEvent(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 3.0,
		AutoDisableCheckIntervalSeconds: 3.0,
		TunnelPacketTimeoutSec:          10.0,
	}, "a", "b", "c", "d")
	c.initResolverRecheckMeta()

	sentAt := time.Date(2026, 3, 26, 1, 44, 50, 0, time.UTC)
	timeoutAt := sentAt.Add(3 * time.Second)
	receivedAt := timeoutAt.Add(400 * time.Millisecond)

	key := resolverSampleKey{
		resolverAddr: "127.0.0.1:5350",
		dnsID:        77,
	}
	c.resolverPending[key] = resolverSample{
		serverKey: "a",
		sentAt:    sentAt,
	}

	c.collectExpiredResolverTimeouts(timeoutAt)
	c.trackResolverSuccess([]byte{0x00, 0x4d}, &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 5350}, receivedAt)

	c.resolverHealthMu.Lock()
	state := c.resolverHealth["a"]
	c.resolverHealthMu.Unlock()

	if state == nil {
		t.Fatal("expected resolver health state to exist")
	}
	if len(state.Events) != 0 {
		t.Fatalf("expected timeout streak to be cleared after late success, got %d events", len(state.Events))
	}
	if !state.TimeoutOnlySince.IsZero() {
		t.Fatal("expected timeout-only streak to be cleared after late success")
	}
	if state.LastSuccessAt.IsZero() {
		t.Fatal("expected late success timestamp to be recorded")
	}
}

func TestCollectExpiredResolverTimeoutsCanTriggerAutoDisable(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 3.0,
		AutoDisableMinObservations:      3,
		AutoDisableCheckIntervalSeconds: 1.0,
		TunnelPacketTimeoutSec:          10.0,
	}, "a", "b", "c", "d")
	c.initResolverRecheckMeta()

	base := time.Date(2026, 3, 25, 14, 0, 0, 0, time.UTC)
	steps := []time.Time{
		base.Add(2 * time.Second),
		base.Add(4 * time.Second),
		base.Add(5 * time.Second),
	}
	for i, now := range steps {
		key := resolverSampleKey{
			resolverAddr: "127.0.0.1:5300",
			dnsID:        uint16(i + 1),
		}
		c.resolverPending[key] = resolverSample{
			serverKey: "a",
			sentAt:    now.Add(-1 * time.Second),
		}
		c.nowFn = func(current time.Time) func() time.Time {
			return func() time.Time { return current }
		}(now)
		c.collectExpiredResolverTimeouts(now)
		c.runResolverAutoDisable(now)
	}

	conn, ok := c.GetConnectionByKey("a")
	if !ok {
		t.Fatal("expected disabled connection to still exist")
	}
	if conn.IsValid {
		t.Fatal("expected timeout-derived health events to disable resolver")
	}
}

func TestResolverRequestTimeoutHonorsShortAutoDisableWindow(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 3.0,
		AutoDisableCheckIntervalSeconds: 3.0,
		TunnelPacketTimeoutSec:          10.0,
	}, "a")

	if got := c.resolverRequestTimeout(); got != 3*time.Second {
		t.Fatalf("unexpected resolver request timeout: got=%s want=%s", got, 3*time.Second)
	}
}

func TestSingleSweepTimeoutsPreserveLogicalTimeoutSpanForAutoDisable(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 3.0,
		AutoDisableMinObservations:      3,
		AutoDisableCheckIntervalSeconds: 3.0,
		TunnelPacketTimeoutSec:          10.0,
	}, "a", "b", "c", "d")
	c.initResolverRecheckMeta()

	now := time.Date(2026, 3, 25, 15, 0, 10, 0, time.UTC)
	c.nowFn = func() time.Time { return now }

	// These three requests all expire during the same sweep, but their logical
	// timeout times are spaced across the whole 3s window.
	samples := []resolverSample{
		{serverKey: "a", sentAt: now.Add(-6 * time.Second)},
		{serverKey: "a", sentAt: now.Add(-4*time.Second - 500*time.Millisecond)},
		{serverKey: "a", sentAt: now.Add(-3 * time.Second)},
	}
	for i, sample := range samples {
		c.resolverPending[resolverSampleKey{
			resolverAddr: "127.0.0.1:5300",
			dnsID:        uint16(100 + i),
		}] = sample
	}

	c.collectExpiredResolverTimeouts(now)
	c.runResolverAutoDisable(now)

	conn, ok := c.GetConnectionByKey("a")
	if !ok {
		t.Fatal("expected disabled connection to still exist")
	}
	if conn.IsValid {
		t.Fatal("expected single-sweep timeout observations to disable resolver")
	}
}

func TestResolverAutoDisableUsesTimeoutOnlyStreakAcrossWindow(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 1.0,
		AutoDisableMinObservations:      1,
		AutoDisableCheckIntervalSeconds: 1.0,
	}, "a", "b", "c", "d")
	c.initResolverRecheckMeta()

	base := time.Date(2026, 3, 25, 16, 0, 0, 0, time.UTC)
	c.recordResolverHealthEvent("a", false, base)
	c.recordResolverHealthEvent("a", false, base.Add(400*time.Millisecond))
	c.recordResolverHealthEvent("a", false, base.Add(800*time.Millisecond))

	now := base.Add(1100 * time.Millisecond)
	c.nowFn = func() time.Time { return now }
	c.runResolverAutoDisable(now)

	conn, ok := c.GetConnectionByKey("a")
	if !ok {
		t.Fatal("expected disabled connection to still exist")
	}
	if conn.IsValid {
		t.Fatal("expected timeout-only streak across the full window to disable resolver")
	}
}

func TestResolverAutoDisableStopsAtThreeValidResolvers(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 1.0,
		AutoDisableMinObservations:      1,
		AutoDisableCheckIntervalSeconds: 1.0,
	}, "a", "b", "c")
	c.initResolverRecheckMeta()

	base := time.Date(2026, 3, 25, 16, 30, 0, 0, time.UTC)
	now := base.Add(1100 * time.Millisecond)

	for _, key := range []string{"a", "b", "c"} {
		c.recordResolverHealthEvent(key, false, base)
	}

	c.runResolverAutoDisable(now)

	valid := c.balancer.ValidCount()
	if valid != 3 {
		t.Fatalf("expected no resolver to be disabled at the floor, got valid=%d", valid)
	}
}

func TestResolverHealthSuccessClearsTimeoutHistory(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{
		AutoDisableTimeoutServers:       true,
		AutoDisableTimeoutWindowSeconds: 3.0,
	}, "a", "b", "c", "d")
	c.initResolverRecheckMeta()

	base := time.Date(2026, 3, 26, 2, 0, 0, 0, time.UTC)
	c.recordResolverHealthEvent("a", true, base.Add(2*time.Second))
	c.recordResolverHealthEvent("a", false, base)
	c.recordResolverHealthEvent("a", true, base.Add(3*time.Second))

	c.resolverHealthMu.Lock()
	state := c.resolverHealth["a"]
	c.resolverHealthMu.Unlock()

	if state == nil {
		t.Fatal("expected resolver health state to exist")
	}
	if len(state.Events) != 0 {
		t.Fatalf("expected success to clear timeout history, got %d events", len(state.Events))
	}
	if state.LastSuccessAt != base.Add(3*time.Second) {
		t.Fatalf("unexpected last success timestamp: got=%s want=%s", state.LastSuccessAt, base.Add(3*time.Second))
	}
}
