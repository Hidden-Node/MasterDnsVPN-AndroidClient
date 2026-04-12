package client

import (
	"bytes"
	"testing"

	"masterdnsvpn-go/internal/compression"
	"masterdnsvpn-go/internal/config"
	Enums "masterdnsvpn-go/internal/enums"
	VpnProto "masterdnsvpn-go/internal/vpnproto"
)

func TestNextSessionInitAttemptUsesBalancerSnapshotConnection(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{}, "a", "b")
	connections := []*Connection{
		{Key: "a", Domain: "a.example.com", Resolver: "127.0.0.1", ResolverPort: 5300, ResolverLabel: "127.0.0.1:0"},
		{Key: "b", Domain: "b.example.com", Resolver: "127.0.0.1", ResolverPort: 5301, ResolverLabel: "127.0.0.1:1"},
	}
	c.balancer.SetConnections(connections)
	for _, conn := range connections {
		c.balancer.SetConnectionMTU(conn.Key, 120, 180, 220)
		c.balancer.SetConnectionValidity(conn.Key, true)
	}

	originalDomain := connections[0].Domain
	connections[0].Domain = "mutated.example.com"

	conn, _, _, err := c.nextSessionInitAttempt()
	if err != nil {
		t.Fatalf("nextSessionInitAttempt returned error: %v", err)
	}

	if conn.Domain != originalDomain {
		t.Fatalf("expected session init to use balancer snapshot domain %q, got %q", originalDomain, conn.Domain)
	}
}

func TestApplySessionInitPacketAppliesServerClientPolicy(t *testing.T) {
	cfg := config.ClientConfig{
		PacketDuplicationCount:        9,
		SetupPacketDuplicationCount:   12,
		MaxUploadMTU:                  220,
		MaxDownloadMTU:                6000,
		RX_TX_Workers:                 12,
		PingAggressiveIntervalSeconds: 0.01,
		MaxPacketsPerBatch:            20,
		ARQWindowSize:                 9000,
		ARQDataNackMaxGap:             256,
		CompressionMinSize:            10,
		ARQInitialRTOSeconds:          0.01,
		ARQMaxRTOSeconds:              5.0,
		ARQControlInitialRTOSeconds:   0.01,
		ARQControlMaxRTOSeconds:       5.0,
	}

	c := buildTestClientWithResolvers(cfg, "a")
	c.syncedUploadMTU = 200
	c.syncedDownloadMTU = 5000

	verifyCode := [4]byte{1, 2, 3, 4}
	initPayload := make([]byte, sessionInitPayloadSize)
	initPayload[0] = 1

	var payload [VpnProto.SessionAcceptPayloadSize]byte
	payload[0] = 7
	payload[1] = 9
	payload[2] = compression.PackPair(compression.TypeOff, compression.TypeOff)
	copy(payload[3:7], verifyCode[:])
	policy := VpnProto.EncodeSessionAcceptClientPolicy(VpnProto.SessionAcceptClientPolicy{
		MaxPacketDuplicationCount: 5,
		MaxSetupDuplicationCount:  6,
		MaxUploadMTU:              150,
		MaxDownloadMTU:            4000,
		MaxRxTxWorkers:            4,
		MinPingAggressiveInterval: 0.05,
		MaxPacketsPerBatch:        10,
		MaxARQWindowSize:          8000,
		MaxARQDataNackMaxGap:      128,
		MinCompressionMinSize:     120,
		MinARQInitialRTOSeconds:   0.05,
	})
	copy(payload[VpnProto.SessionAcceptBasePayloadSize:], policy[:])

	err := c.applySessionInitPacket(VpnProto.Packet{
		PacketType: Enums.PACKET_SESSION_ACCEPT,
		Payload:    payload[:],
	}, initPayload, verifyCode)
	if err != nil {
		t.Fatalf("applySessionInitPacket returned error: %v", err)
	}

	if !c.sessionReady {
		t.Fatal("expected session to become ready")
	}
	if c.sessionID != 7 || c.sessionCookie != 9 {
		t.Fatalf("unexpected session identity: id=%d cookie=%d", c.sessionID, c.sessionCookie)
	}
	if c.cfg.PacketDuplicationCount != 5 {
		t.Fatalf("unexpected packet duplication clamp: got=%d want=%d", c.cfg.PacketDuplicationCount, 5)
	}
	if c.cfg.SetupPacketDuplicationCount != 6 {
		t.Fatalf("unexpected setup duplication clamp: got=%d want=%d", c.cfg.SetupPacketDuplicationCount, 6)
	}
	if c.cfg.MaxUploadMTU != 150 {
		t.Fatalf("unexpected upload mtu clamp: got=%d want=%d", c.cfg.MaxUploadMTU, 150)
	}
	if c.cfg.MaxDownloadMTU != 4000 {
		t.Fatalf("unexpected download mtu clamp: got=%d want=%d", c.cfg.MaxDownloadMTU, 4000)
	}
	if c.cfg.RX_TX_Workers != 4 || c.tunnelRX_TX_Workers != 4 {
		t.Fatalf("unexpected worker clamp: cfg=%d runtime=%d", c.cfg.RX_TX_Workers, c.tunnelRX_TX_Workers)
	}
	if c.cfg.TunnelProcessWorkers != 5 || c.tunnelProcessWorkers != 5 {
		t.Fatalf("unexpected process worker sync: cfg=%d runtime=%d", c.cfg.TunnelProcessWorkers, c.tunnelProcessWorkers)
	}
	if c.cfg.PingAggressiveIntervalSeconds < 0.049 || c.cfg.PingAggressiveIntervalSeconds > 0.051 {
		t.Fatalf("unexpected ping min clamp: got=%f", c.cfg.PingAggressiveIntervalSeconds)
	}
	if c.cfg.MaxPacketsPerBatch != 10 {
		t.Fatalf("unexpected packets per batch clamp: got=%d want=%d", c.cfg.MaxPacketsPerBatch, 10)
	}
	if c.cfg.ARQWindowSize != 8000 {
		t.Fatalf("unexpected arq window clamp: got=%d want=%d", c.cfg.ARQWindowSize, 8000)
	}
	if c.cfg.ARQDataNackMaxGap != 128 {
		t.Fatalf("unexpected arq data nack clamp: got=%d want=%d", c.cfg.ARQDataNackMaxGap, 128)
	}
	if c.cfg.CompressionMinSize != 120 {
		t.Fatalf("unexpected compression min size clamp: got=%d want=%d", c.cfg.CompressionMinSize, 120)
	}
	if c.cfg.ARQInitialRTOSeconds < 0.049 || c.cfg.ARQInitialRTOSeconds > 0.051 {
		t.Fatalf("unexpected arq initial rto clamp: got=%f", c.cfg.ARQInitialRTOSeconds)
	}
	if c.syncedUploadMTU != 150 || c.syncedDownloadMTU != 4000 {
		t.Fatalf("unexpected synced mtu clamp: up=%d down=%d", c.syncedUploadMTU, c.syncedDownloadMTU)
	}
	if c.safeUploadMTU <= 0 {
		t.Fatalf("expected safe upload mtu to be recomputed, got=%d", c.safeUploadMTU)
	}
	if c.maxPackedBlocks <= 0 {
		t.Fatalf("expected max packed blocks to be recomputed, got=%d", c.maxPackedBlocks)
	}
	if cap(c.plannerQueue) != 96 {
		t.Fatalf("unexpected planner queue capacity: got=%d want=%d", cap(c.plannerQueue), 96)
	}
	if cap(c.encodedTXChannel) != 96 {
		t.Fatalf("unexpected writer queue capacity: got=%d want=%d", cap(c.encodedTXChannel), 96)
	}
	if cap(c.rxChannel) != c.cfg.EffectiveRXChannelSize() {
		t.Fatalf("unexpected rx channel capacity: got=%d want=%d", cap(c.rxChannel), c.cfg.EffectiveRXChannelSize())
	}
	if c.orphanQueue == nil || c.orphanQueue.FastSize() != 0 {
		t.Fatal("expected orphan queue to be rebuilt cleanly")
	}
	if c.dnsResponses == nil {
		t.Fatal("expected dns response fragment store to be rebuilt")
	}
	if !bytes.Equal(payload[3:7], verifyCode[:]) {
		t.Fatal("verify code should remain in legacy position")
	}
}

func TestApplySessionInitPacketAcceptsLegacySessionAcceptPayload(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{}, "a")
	verifyCode := [4]byte{4, 3, 2, 1}
	initPayload := make([]byte, sessionInitPayloadSize)

	payload := make([]byte, VpnProto.SessionAcceptBasePayloadSize)
	payload[0] = 3
	payload[1] = 5
	payload[2] = compression.PackPair(compression.TypeOff, compression.TypeOff)
	copy(payload[3:7], verifyCode[:])

	err := c.applySessionInitPacket(VpnProto.Packet{
		PacketType: Enums.PACKET_SESSION_ACCEPT,
		Payload:    payload,
	}, initPayload, verifyCode)
	if err != nil {
		t.Fatalf("legacy session accept should still work, got error: %v", err)
	}
	if !c.sessionReady {
		t.Fatal("expected legacy session accept to mark session ready")
	}
}

func TestApplySessionInitPacketPreservesHigherTunnelProcessWorkers(t *testing.T) {
	cfg := config.ClientConfig{
		PacketDuplicationCount:        8,
		SetupPacketDuplicationCount:   9,
		MaxUploadMTU:                  220,
		MaxDownloadMTU:                6000,
		RX_TX_Workers:                 16,
		TunnelProcessWorkers:          200,
		PingAggressiveIntervalSeconds: 0.02,
		MaxPacketsPerBatch:            30,
		ARQWindowSize:                 12000,
		ARQDataNackMaxGap:             300,
		CompressionMinSize:            40,
		ARQInitialRTOSeconds:          0.02,
		ARQControlInitialRTOSeconds:   0.02,
		ARQMaxRTOSeconds:              5.0,
		ARQControlMaxRTOSeconds:       3.0,
	}
	c := buildTestClientWithResolvers(cfg, "a")
	c.tunnelRX_TX_Workers = 16
	c.tunnelProcessWorkers = 200

	verifyCode := [4]byte{1, 2, 3, 4}
	var payload [VpnProto.SessionAcceptPayloadSize]byte
	payload[0] = 9
	payload[1] = 8
	payload[2] = compression.PackPair(compression.TypeOff, compression.TypeOff)
	copy(payload[3:7], verifyCode[:])

	policy := VpnProto.EncodeSessionAcceptClientPolicy(VpnProto.SessionAcceptClientPolicy{
		MaxPacketDuplicationCount: 5,
		MaxSetupDuplicationCount:  6,
		MaxUploadMTU:              150,
		MaxDownloadMTU:            4000,
		MaxRxTxWorkers:            4,
		MinPingAggressiveInterval: 0.05,
		MaxPacketsPerBatch:        10,
		MaxARQWindowSize:          8000,
		MaxARQDataNackMaxGap:      128,
		MinCompressionMinSize:     120,
		MinARQInitialRTOSeconds:   0.05,
	})
	copy(payload[VpnProto.SessionAcceptBasePayloadSize:], policy[:])

	packet := VpnProto.Packet{
		PacketType: Enums.PACKET_SESSION_ACCEPT,
		Payload:    payload[:],
	}

	initPayload := make([]byte, sessionInitPayloadSize)
	initPayload[0] = 1

	if err := c.applySessionInitPacket(packet, initPayload, verifyCode); err != nil {
		t.Fatalf("applySessionInitPacket returned error: %v", err)
	}

	if c.cfg.RX_TX_Workers != 4 || c.tunnelRX_TX_Workers != 4 {
		t.Fatalf("unexpected worker clamp: cfg=%d runtime=%d", c.cfg.RX_TX_Workers, c.tunnelRX_TX_Workers)
	}
	if c.cfg.TunnelProcessWorkers != 200 || c.tunnelProcessWorkers != 200 {
		t.Fatalf("higher process workers should be preserved: cfg=%d runtime=%d", c.cfg.TunnelProcessWorkers, c.tunnelProcessWorkers)
	}
}
