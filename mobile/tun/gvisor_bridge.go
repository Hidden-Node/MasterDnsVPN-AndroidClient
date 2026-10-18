//go:build linux || android

// Package tun — gvisor_bridge.go
//
// Implements a tun2socks bridge using the gVisor netstack (second engine,
// opt-in). Reads raw IP packets from a TUN file descriptor (provided by
// Android's VpnService.establish()), sets up a virtual TCP/IP stack, and
// forwards every TCP flow through the loopback SOCKS5 proxy; DNS (UDP/53) is
// answered in-process by the running VPN client, and every other UDP
// destination is refused so the stack sends ICMP Port Unreachable (apps then
// fall back to TCP).
// ==============================================================================
package tun

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"masterdnsvpn-go/internal/client"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const gvisorTunNICID tcpip.NICID = 1

// gvisorDNSPort — the only UDP destination the bridge serves; DNS is handled
// in-process (see gvisorProxyDNSDirect). DoT/TCP-53 get RST, all other UDP
// gets ICMP Port Unreachable.
const gvisorDNSPort uint16 = 53

// ── Running-client hook ───────────────────────────────────────────────────────
//
// mobile.go stores the running VPN client here on StartClient and stores nil
// on stop. Atomic pointer: the setter runs under StartClient's mutex while
// per-flow DNS/TCP goroutines read it concurrently — a plain package var
// would race across reconnects.

var gvisorClientBox atomic.Pointer[client.Client]

// SetGvisorClient installs (or clears with nil) the running VPN client the
// bridge resolves DNS through. Safe to call from any goroutine at any time;
// nil is accepted and means "client not running".
func SetGvisorClient(c *client.Client) { gvisorClientBox.Store(c) }

// gvisorClient returns the current VPN client, nil between sessions/stops.
func gvisorClient() *client.Client { return gvisorClientBox.Load() }

// ── Tuning constants ───────────────────────────────────────────────────────────

const (
	// gvisorRelayBufSize — each recycled relay buffer. 16 KB keeps per-flow
	// memory low: small buffers with honest backpressure beat big windows
	// that let megabytes pile up invisibly inside relay + gVisor buffers,
	// making uploads LOOK complete while they were actually stalled.
	gvisorRelayBufSize = 16 * 1024

	// gvisorTCPMaxInFlight — maximum concurrent TCP connections being
	// proxied. Browsers/chat apps easily open dozens of flows; each flow
	// holds relay buffers plus bandwidth inside the tunnel, so a generous
	// cap turns SYN storms (and weak phones) into memory blow-ups.
	gvisorTCPMaxInFlight = 96

	// gvisorTCPDialTimeout — timeout for dialling the local SOCKS5 proxy
	// (loopback, sub-millisecond when the engine is healthy).
	gvisorTCPDialTimeout = 10 * time.Second

	// gvisorRelayWriteStallTimeout — max time a single relay Write may block
	// without progress before the whole flow is torn down. Without this a
	// wedged peer leaves goroutines (and orphaned tunnel streams
	// retransmitting to the server) alive until the profile is stopped.
	gvisorRelayWriteStallTimeout = 90 * time.Second

	// gvisorRelayIdleTimeout — close a flow after this much total silence in
	// BOTH directions. Safety net for fully-stalled flows that produce no
	// errors.
	gvisorRelayIdleTimeout = 10 * time.Minute

	// gvisorRelayIdleCheckInterval — how often the per-flow idle watchdog
	// ticks.
	gvisorRelayIdleCheckInterval = 15 * time.Second

	// gvisorUDPReadTimeout is the per-iteration deadline on the UDP relay
	// socket (allows ctx.Done detection without blocking forever).
	gvisorUDPReadTimeout = 5 * time.Second

	// gvisorSocks5HandshakeTimeout lives in gvisor_socks5.go (portable,
	// shared with the unit tests that exercise the handshake on any
	// platform).
)

// ── Buffer pool ────────────────────────────────────────────────────────────────

var gvisorRelayBufPool = sync.Pool{
	New: func() any {
		b := make([]byte, gvisorRelayBufSize)
		return &b
	},
}

func gvisorGetRelayBuf() []byte  { return *(gvisorRelayBufPool.Get().(*[]byte)) }
func gvisorPutRelayBuf(b []byte) { gvisorRelayBufPool.Put(&b) }

// ── Bridge logging (thin severity wrappers over the file logger) ──────────────

func gvisorBridgeLog(format string, args ...any) {
	gvisorLogLine(gvisorLogInfo, fmt.Sprintf(format, args...))
}

func gvisorBridgeWarn(format string, args ...any) {
	gvisorLogLine(gvisorLogWarn, fmt.Sprintf(format, args...))
}

func gvisorBridgeErr(format string, args ...any) {
	gvisorLogLine(gvisorLogError, fmt.Sprintf(format, args...))
}

// Rate-limit slip for the per-packet UDP refusal counter's companion log
// line: per-packet logging is FORBIDDEN, so repeated refusals are counted
// but announced at most once per interval (atomic timestamp, CAS-guarded).
var gvisorRefusalLogLast atomic.Int64

// ── Stack setup (the synchronous portion of bridge startup) ───────────────────

// gvisorSetupStack performs every fallible, synchronous part of bridge
// startup: stack + NIC + routes + forwarders + transport handler
// registrations. It returns the live stack so the caller can spawn only the
// goroutine that awaits ctx and closes it on shutdown.
func gvisorSetupStack(ctx context.Context, tunFd int, mtu int, socksAddr string) (*stack.Stack, error) {
	gvisorBridgeLog("starting bridge: tunFd=%d mtu=%d socksAddr=%s", tunFd, mtu, socksAddr)

	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
		},
	})

	// Disable Nagle's algorithm — flush writes immediately for low-latency
	// streaming and interactive apps instead of waiting to coalesce segments.
	delay := tcpip.TCPDelayEnabled(false)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &delay)

	// Enable Selective Acknowledgement (SACK) for faster loss recovery on
	// lossy mobile connections.
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)

	// Keep receive buffering static: auto-tuning lets the window balloon far
	// beyond what the tunnel can drain, hiding stalls from the uploading app
	// (bufferbloat) and delaying error propagation on cancel.
	moderate := tcpip.TCPModerateReceiveBufferOption(false)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate)

	// Sized for the tunnel's real throughput (~tens of KB/s): 64 KB default,
	// 256 KB ceiling. Small windows give the app honest backpressure so a
	// stalled upload surfaces quickly instead of piling into local buffers.
	sendBuf := tcpip.TCPSendBufferSizeRangeOption{Min: 4096, Default: 64 << 10, Max: 256 << 10}
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sendBuf)

	// Matching receive buffer range.
	recvBuf := tcpip.TCPReceiveBufferSizeRangeOption{Min: 4096, Default: 64 << 10, Max: 256 << 10}
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &recvBuf)

	ep, err := fdbased.New(&fdbased.Options{
		FDs:            []int{tunFd},
		MTU:            uint32(mtu),
		EthernetHeader: false, // TUN (raw IP), not TAP
		// GSOMaxSize, gVisor GSO and GRO are intentionally NOT set.
		// VpnService on Android does NOT support IFF_VNET_HDR, so gVisor GSO
		// superframes are silently dropped by the kernel — causing near-zero
		// throughput. RXChecksumOffload is safe because gVisor validates
		// checksums internally before passing to the stack regardless.
		RXChecksumOffload: true,
	})
	if err != nil {
		return nil, fmt.Errorf("gvisor bridge: fdbased.New: %w", err)
	}

	if tcpErr := s.CreateNIC(gvisorTunNICID, ep); tcpErr != nil {
		return nil, fmt.Errorf("gvisor bridge: CreateNIC: %v", tcpErr)
	}

	// Promiscuous + spoofing: accept packets for any destination IP.
	if tcpErr := s.SetPromiscuousMode(gvisorTunNICID, true); tcpErr != nil {
		return nil, fmt.Errorf("gvisor bridge: SetPromiscuousMode: %v", tcpErr)
	}
	if tcpErr := s.SetSpoofing(gvisorTunNICID, true); tcpErr != nil {
		return nil, fmt.Errorf("gvisor bridge: SetSpoofing: %v", tcpErr)
	}

	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: gvisorTunNICID},
		{Destination: header.IPv6EmptySubnet, NIC: gvisorTunNICID},
	})

	gvisorBridgeLog("gVisor stack created, setting up forwarders")

	// ── TCP forwarder ──────────────────────────────────────────────────────────
	// No connection pool: each TCP flow dials the loopback SOCKS5 proxy
	// directly. Loopback dials are sub-millisecond so pooling adds complexity
	// (race conditions, stale connections) without measurable gain.
	sem := make(chan struct{}, gvisorTCPMaxInFlight)

	tcpFwd := tcp.NewForwarder(s, 0, gvisorTCPMaxInFlight, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		dstAddr := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))

		// DNS-over-TCP (53) and DoT (853) cannot be served by this bridge:
		// the client resolves DNS in-process over UDP only, so a CONNECT for
		// the virtual DNS IP (or any :53/:853 TCP flow) would travel to the
		// tunnel server, time out there, and stall app startup for seconds.
		// RST immediately so clients fall back to UDP/53 (handled in-process).
		if id.LocalPort == gvisorDNSPort || id.LocalPort == 853 {
			r.Complete(true)
			return
		}

		// "Disable IPv6" (default ON): RST IPv6 destinations on the spot.
		// Apps with cached AAAA answers (e.g. YouTube/Cronet) otherwise send
		// every IPv6 CONNECT through the SOCKS/tunnel and only learn of the
		// failure after a full tunnel round-trip with "connect refused code
		// 3". An immediate RST makes them fall back to the IPv4 address
		// right away.
		if id.LocalAddress.Len() == 16 && gvisorIPv6Blocked() {
			r.Complete(true)
			return
		}

		var wq waiter.Queue
		ep, tcpErr := r.CreateEndpoint(&wq)
		if tcpErr != nil {
			gvisorBridgeErr("TCP CreateEndpoint failed for %s: %v", dstAddr, tcpErr)
			r.Complete(true) // send RST
			return
		}
		r.Complete(false)
		conn := gonet.NewTCPConn(&wq, ep)

		// Backpressure: wait up to 2s for a slot. Log if dropping.
		// Use NewTimer (not time.After) so the timer is stopped immediately
		// when a slot is available or ctx is cancelled — time.After leaves
		// the underlying timer live for the full 2s on every fast-path
		// connection, accumulating thousands of leaked timers under load.
		bpTimer := time.NewTimer(2 * time.Second)
		select {
		case sem <- struct{}{}:
			bpTimer.Stop()
		case <-bpTimer.C:
			gvisorBridgeWarn("TCP backpressure: dropping %s (>%d concurrent)", dstAddr, gvisorTCPMaxInFlight)
			_ = conn.Close()
			return
		case <-ctx.Done():
			bpTimer.Stop()
			_ = conn.Close()
			return
		}

		go func() {
			defer func() { <-sem }()
			defer conn.Close()
			if ctx.Err() != nil {
				return
			}
			gvisorProxyTCPDirect(ctx, conn, dstAddr, socksAddr)
		}()
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)

	// ── UDP: port-53 forwarder wrapped in a refusal gate ───────────────────────
	// DNS (port 53) is served in-process (no SOCKS5 overhead, no fake-IP
	// mapping). At the pinned upstream module udp.NewForwarder has NO
	// bool-returning handler and (*Forwarder).HandlePacket always returns
	// true — so port≠53 refusals happen HERE, in the per-stack transport
	// handler: returning false marks the packet unhandled and the network
	// layer answers with ICMP Port Unreachable, making browsers and QUIC
	// clients fall back to TCP immediately instead of stalling.
	udpFwd := udp.NewForwarder(s, func(r *udp.ForwarderRequest) {
		id := r.ID()
		if id.LocalPort != gvisorDNSPort {
			// Never reached via the gate below; kept as a guard so the
			// forwarder alone never widens its surface.
			return
		}

		dstAddr := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))

		var wq waiter.Queue
		ep, udpErr := r.CreateEndpoint(&wq)
		if udpErr != nil {
			gvisorBridgeErr("UDP CreateEndpoint failed for %s: %v", dstAddr, udpErr)
			return
		}

		conn := gonet.NewUDPConn(&wq, ep)
		go func() {
			defer conn.Close()
			if ctx.Err() != nil {
				return
			}
			gvisorProxyDNSDirect(ctx, conn, dstAddr)
		}()
	})
	s.SetTransportProtocolHandler(header.UDPProtocolNumber, func(id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
		if id.LocalPort == gvisorDNSPort {
			return udpFwd.HandlePacket(id, pkt)
		}
		// Refuse → the stack falls through to the unknown-destination path
		// and emits ICMP Port Unreachable for this packet.
		gvisorBytesUp.Add(int64(pkt.Size()))
		// Rate-limited WARN — per-packet logging is forbidden.
		now := time.Now().UnixNano()
		if last := gvisorRefusalLogLast.Load(); now-last > int64(10*time.Second) {
			if gvisorRefusalLogLast.CompareAndSwap(last, now) {
				gvisorBridgeWarn("refused non-DNS UDP packet to port %d (ICMP Port Unreachable; refusals are counted, not logged per packet)", id.LocalPort)
			}
		}
		return false
	})

	gvisorBridgeLog("forwarders ready, bridge is active")
	return s, nil
}

// ── TCP proxy (direct dial per flow) ───────────────────────────────────────────

// gvisorProxyTCPDirect dials the SOCKS5 proxy, performs CONNECT handshake,
// then relays bidirectionally. Each flow gets a fresh connection — loopback
// is fast.
func gvisorProxyTCPDirect(ctx context.Context, src net.Conn, dstAddr string, socksAddr string) {
	host, portStr, err := net.SplitHostPort(dstAddr)
	if err != nil {
		gvisorBridgeErr("TCP parse %s: %v", dstAddr, err)
		return
	}
	port, err := gvisorParsePort(portStr)
	if err != nil {
		gvisorBridgeErr("TCP port %s: %v", dstAddr, err)
		return
	}

	// Poison-guard parity with the FakeDNS engine: gVisor hands the target
	// over as a stack address (An IPv4 destination can surface as a
	// v4-mapped IPv6 literal), so normalize to the plain host-string form
	// FIRST, then refuse the exact same target set the old engine refuses —
	// no dial, the flow's source conn is simply closed by the caller.
	host = gvisorNormalizeTargetHost(host)
	if gvisorIsBlockedSOCKSTarget(host) {
		gvisorBridgeWarn("refused blocked target %s", dstAddr)
		return
	}

	dialCtx, dialCancel := context.WithTimeout(ctx, gvisorTCPDialTimeout)
	defer dialCancel()
	// Use plain dial for loopback — VpnService.protect() is NOT needed for
	// 127.0.0.1 (loopback traffic never enters the TUN interface) and calling
	// protect on loopback sockets can cause routing issues on some devices.
	d := net.Dialer{}
	proxy, err := d.DialContext(dialCtx, "tcp", socksAddr)
	if err != nil {
		gvisorBridgeErr("TCP dial SOCKS5 for %s: %v", dstAddr, err)
		return
	}
	defer proxy.Close()

	// Apply the local proxy's credentials (no-op when auth is disabled).
	proxy = gvisorSocksCredsWrapper(proxy)

	if err := gvisorSocks5ConnectTCP(proxy, host, port); err != nil {
		gvisorBridgeErr("TCP SOCKS5 CONNECT %s: %v", dstAddr, err)
		return
	}

	gvisorBridgeLog("TCP connected: %s", dstAddr)
	gvisorBidirectionalRelay(ctx, src, proxy)
}

// ── DNS proxy (direct in-process call) ─────────────────────────────────────

// gvisorProxyDNSDirect reads DNS queries from the gVisor UDP endpoint and
// processes them directly via the running client's ProcessDNSQuery
// (in-process call, no SOCKS5).
//
// On cache hit the response is returned immediately. On cache miss the query
// is dispatched to the tunnel and the function polls for the response up to
// the 5 s deadline.
func gvisorProxyDNSDirect(ctx context.Context, src net.Conn, dstAddr string) {
	buf := make([]byte, 4096)
	var idleTimeouts int
	for {
		if ctx.Err() != nil {
			return
		}
		// Set a read deadline so we detect context cancellation periodically.
		if tc, ok := src.(interface{ SetReadDeadline(time.Time) error }); ok {
			_ = tc.SetReadDeadline(time.Now().Add(gvisorUDPReadTimeout))
		}

		n, readErr := src.Read(buf)
		if readErr != nil {
			if netErr, ok := readErr.(net.Error); ok && netErr.Timeout() {
				idleTimeouts++
				if idleTimeouts >= 3 { // 3×5s = 15s idle — exit goroutine
					return
				}
				continue
			}
			return
		}
		idleTimeouts = 0
		if n == 0 {
			continue
		}

		query := make([]byte, n)
		copy(query, buf[:n])

		// Resolve the running client on every packet so we handle the case
		// where the client starts up after this goroutine is already running
		// (or dies mid-session). Resolving once at goroutine start would
		// mean a nil return permanently closes the UDP session — all
		// subsequent DNS queries from the same browser socket get no
		// response.
		cl := gvisorClient()
		if cl == nil {
			// Client not ready yet — drop this packet, browser will retry.
			gvisorBytesUp.Add(int64(n))
			continue
		}

		// Drop queries when the tunnel session is not yet established.
		// ProcessDNSQuery would dispatch to the tunnel only when
		// SessionReady=true. If we call it while SessionReady=false, it
		// creates a StatusPending cache entry and then silently skips
		// dispatch. The cache entry then blocks any re-dispatch for the full
		// pendingTimeout window (default 300 s), meaning the poll loop below
		// can NEVER succeed — DNS always times out. DNS clients retry every
		// 1–2 s automatically; by the second or third retry the session is
		// ready and the query dispatches normally.
		if !cl.SessionReady() {
			gvisorBytesUp.Add(int64(n))
			gvisorBridgeLog("DNS drop (session not ready) for %s — client will retry", dstAddr)
			continue
		}

		// shared write-once flag + callback — no mutex needed: everything
		// runs in this single goroutine (ProcessDNSQuery calls the callback
		// synchronously).
		var responseWritten bool
		writeResp := func(resp []byte) {
			// Strip AAAA records: the tunnel has no IPv6 egress, so
			// advertising IPv6 addresses makes apps try (and get RST on)
			// dead connections.
			resp = gvisorFilterDNSAAAA(resp)
			_, _ = src.Write(resp)
			gvisorBytesDown.Add(int64(len(resp)))
			responseWritten = true
		}

		isHit := cl.ProcessDNSQuery(query, nil, writeResp)
		gvisorBytesUp.Add(int64(n))

		if isHit {
			gvisorBridgeLog("DNS cache hit for %s", dstAddr)
		} else {
			// Cache miss: query dispatched to the tunnel (fire-and-forget).
			// Poll ProcessDNSQuery every 20 ms until the cache entry fills
			// (StatusReady), at which point the call hits the cache and
			// invokes writeResp. Calls while StatusPending do NOT re-dispatch
			// (guarded by pendingTimeout in the cache), so each tick is just
			// a cheap read-locked cache lookup.
			// Use a Ticker (created once) instead of time.After (creates a
			// new timer object on every iteration — ~250 per query miss at
			// 5s/20ms).
			deadline := time.Now().Add(5 * time.Second)
			pollTicker := time.NewTicker(20 * time.Millisecond)
		pollLoop:
			for !responseWritten {
				select {
				case <-ctx.Done():
					break pollLoop
				case <-pollTicker.C:
					if time.Now().After(deadline) {
						break pollLoop
					}
					// Re-fetch the client on each tick in case of a
					// reconnect, and guard against a client that lost its
					// session mid-poll.
					if cl = gvisorClient(); cl != nil && cl.SessionReady() {
						_ = cl.ProcessDNSQuery(query, nil, writeResp)
					}
				}
			}
			pollTicker.Stop()
			if responseWritten {
				gvisorBridgeLog("DNS tunnel response for %s", dstAddr)
			} else {
				gvisorBridgeWarn("DNS timeout (no tunnel response) for %s", dstAddr)
			}
		}
	}
}

// ── Relay ──────────────────────────────────────────────────────────────────────

// gvisorHalfCloser is implemented by connections that support TCP
// half-close. Both *net.TCPConn and *gonet.TCPConn implement this interface.
type gvisorHalfCloser interface {
	CloseWrite() error
}

// gvisorBidirectionalRelay copies data between a (TUN/gVisor side) and
// b (SOCKS5 proxy side) until the flow finishes or is aborted.
//
//	a -> b  =  upload   (device -> internet)
//	b -> a  =  download (internet -> device)
//
// Uses pooled 16 KB buffers to bound per-flow memory.
//
// Abort semantics:
//   - ctx cancelled (bridge bounce / VPN stop) → both sides closed at once.
//   - Write stalled longer than gvisorRelayWriteStallTimeout → both sides
//     closed.
//   - non-EOF read error (RST / reset / closed) → BOTH sides closed, so an
//     app-side abort propagates to the tunnel instead of half-closing and
//     waiting for the server to close its half forever.
//   - clean EOF → graceful half-close of the peer's write side only, keeping
//     correct HTTP/1.1 request-response completion.
//   - total silence in both directions for gvisorRelayIdleTimeout → both
//     closed (watchdog for flows that stall without producing any error).
func gvisorBidirectionalRelay(ctx context.Context, a, b net.Conn) {
	var lastActivity atomic.Int64
	lastActivity.Store(time.Now().UnixNano())

	closeBoth := func() {
		_ = a.Close()
		_ = b.Close()
	}

	// Bridge teardown (bounce/stop) must tear down every live relay so no
	// orphaned SOCKS streams survive the bridge.
	stopWatch := context.AfterFunc(ctx, closeBoth)
	defer stopWatch()

	done := make(chan struct{})
	defer close(done)
	go func() {
		ticker := time.NewTicker(gvisorRelayIdleCheckInterval)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				if time.Since(time.Unix(0, lastActivity.Load())) > gvisorRelayIdleTimeout {
					closeBoth()
					return
				}
			}
		}
	}()

	var wg sync.WaitGroup
	wg.Add(2)
	copyHalf := func(dst, src net.Conn, counter *atomic.Int64) {
		defer wg.Done()
		buf := gvisorGetRelayBuf()
		defer gvisorPutRelayBuf(buf)

		for {
			n, readErr := src.Read(buf)
			if n > 0 {
				_ = dst.SetWriteDeadline(time.Now().Add(gvisorRelayWriteStallTimeout))
				wrote, writeErr := dst.Write(buf[:n])
				_ = dst.SetWriteDeadline(time.Time{})
				if wrote > 0 {
					counter.Add(int64(wrote))
				}
				lastActivity.Store(time.Now().UnixNano())
				if writeErr != nil {
					// Stalled write or peer reset: abort the whole flow.
					closeBoth()
					return
				}
			}
			if readErr != nil {
				if errors.Is(readErr, io.EOF) {
					// Clean half-close: this direction finished normally.
					if hc, ok := dst.(gvisorHalfCloser); ok {
						_ = hc.CloseWrite()
					} else {
						_ = dst.Close()
					}
					return
				}
				// Reset/timeout/closed: tear down BOTH directions now —
				// only stopping this half would leave the SOCKS side (and
				// the server's stream) alive forever.
				closeBoth()
				return
			}
		}
	}

	go copyHalf(b, a, &gvisorBytesUp)   // upload:   TUN -> proxy
	go copyHalf(a, b, &gvisorBytesDown) // download: proxy -> TUN
	wg.Wait()
}

// ── Utilities ──────────────────────────────────────────────────────────────────

func gvisorParsePort(portStr string) (uint16, error) {
	p, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		return 0, fmt.Errorf("gvisor bridge: invalid port %q: %w", portStr, err)
	}
	return uint16(p), nil
}
