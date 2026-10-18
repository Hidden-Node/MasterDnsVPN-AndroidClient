// Package tun — gvisor_bridge_test.go
//
// Portable unit tests for the gVisor bridge helpers (state, filter, handshake
// and file logger). No build tag: the whole file compiles and runs on every
// platform, mirroring the structure of fakedns_proxy_test.go (table-driven
// cases, hand-built wire messages, no external DNS library).
// ==============================================================================
package tun

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

// ── Test message builders (hand-rolled wire format) ──────────────────────────

// gvisorTestQuery builds a minimal DNS query message for name with qtype.
func gvisorTestQuery(t *testing.T, name string, qtype uint16) []byte {
	t.Helper()
	msg := []byte{0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, label := range gvisorTestLabels(name) {
		msg = append(msg, byte(len(label)))
		msg = append(msg, label...)
	}
	msg = append(msg, 0x00)
	msg = binary.BigEndian.AppendUint16(msg, qtype)
	msg = binary.BigEndian.AppendUint16(msg, 1) // class IN
	return msg
}

// gvisorTestLabels splits a dotted name into labels without the stdlib DNS
// helpers (there are none — this keeps the builders dependency-free).
func gvisorTestLabels(name string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			if i > start {
				out = append(out, name[start:i])
			}
			start = i + 1
		}
	}
	return out
}

// gvisorTestAnswer appends one uncompressed-owner answer record of rtype to a
// response message under construction.
func gvisorTestAnswer(msg []byte, name string, rtype uint16, rdata []byte) []byte {
	for _, label := range gvisorTestLabels(name) {
		msg = append(msg, byte(len(label)))
		msg = append(msg, label...)
	}
	msg = append(msg, 0x00)
	msg = binary.BigEndian.AppendUint16(msg, rtype)
	msg = binary.BigEndian.AppendUint16(msg, 1)   // class IN
	msg = binary.BigEndian.AppendUint32(msg, 300) // TTL
	msg = binary.BigEndian.AppendUint16(msg, uint16(len(rdata)))
	return append(msg, rdata...)
}

// gvisorTestCounts overwrites the ANCOUNT/NSCOUNT/ARCOUNT header fields.
func gvisorTestCounts(msg []byte, an, ns, ar uint16) {
	binary.BigEndian.PutUint16(msg[6:8], an)
	binary.BigEndian.PutUint16(msg[8:10], ns)
	binary.BigEndian.PutUint16(msg[10:12], ar)
}

// ── AAAA filter ───────────────────────────────────────────────────────────────

func TestGvisorAAAAFilter(t *testing.T) {
	t.Run("aaaa_only_drops_answers_other_counts_unchanged", func(t *testing.T) {
		resp := gvisorTestQuery(t, "example.com", 28)
		resp[2] = 0x81 // QR=1, RD=1
		resp[3] = 0x80 // RA=1
		v6 := make([]byte, 16)
		v6[0] = 0x20
		v6[1] = 0x01
		resp = gvisorTestAnswer(resp, "example.com", 28, v6)
		resp = gvisorTestAnswer(resp, "example.com", 28, v6)
		gvisorTestCounts(resp, 2, 0, 0)

		got := gvisorFilterDNSAAAA(resp)
		if an := binary.BigEndian.Uint16(got[6:8]); an != 0 {
			t.Fatalf("expected ANCOUNT=0 after dropping AAAA answers, got %d", an)
		}
		if ns := binary.BigEndian.Uint16(got[8:10]); ns != 0 {
			t.Fatalf("expected NSCOUNT unchanged at 0, got %d", ns)
		}
		if ar := binary.BigEndian.Uint16(got[10:12]); ar != 0 {
			t.Fatalf("expected ARCOUNT unchanged at 0, got %d", ar)
		}
		// No AAAA record may remain in the wire stream.
		aaaas := 0
		p := 12
		for p+10 <= len(got) {
			rdLen := int(binary.BigEndian.Uint16(got[p+8 : p+10]))
			if typ := binary.BigEndian.Uint16(got[p+2 : p+4]); typ == gvisorDnsTypeAAAA {
				aaaas++
			}
			p += 10 + rdLen
		}
		if aaaas != 0 {
			t.Fatalf("expected 0 AAAA records after filtering, found %d", aaaas)
		}
	})

	t.Run("a_only_response_returned_identical", func(t *testing.T) {
		resp := gvisorTestQuery(t, "example.com", 1)
		resp[2] = 0x81
		resp[3] = 0x80
		resp = gvisorTestAnswer(resp, "example.com", 1, []byte{93, 184, 216, 34})
		gvisorTestCounts(resp, 1, 0, 0)

		got := gvisorFilterDNSAAAA(resp)
		// Zero-copy fast path: same backing array, not a rebuilt message.
		if &got[0] != &resp[0] {
			t.Fatal("expected AAAA-free response to be returned on the zero-copy path")
		}
		if len(got) != len(resp) {
			t.Fatalf("length changed: got=%d orig=%d", len(got), len(resp))
		}
		if string(got) != string(resp) {
			t.Fatal("A-only response content changed")
		}
	})

	t.Run("query_qr0_untouched", func(t *testing.T) {
		query := gvisorTestQuery(t, "example.com", 28)
		got := gvisorFilterDNSAAAA(query)
		if &got[0] != &query[0] {
			t.Fatal("expected query (QR=0) to be returned untouched")
		}
	})

	t.Run("malformed_untouched", func(t *testing.T) {
		// Shorter than the fixed DNS header.
		short := []byte{0x81, 0x80, 0, 1, 0, 1, 0, 0, 0, 0}
		if got := gvisorFilterDNSAAAA(short); &got[0] != &short[0] {
			t.Fatal("expected truncated message to be returned unchanged")
		}
		// Answer whose rdlength claims more bytes than the message holds.
		resp := gvisorTestQuery(t, "example.com", 28)
		resp[2] = 0x81
		resp[3] = 0x80
		resp = gvisorTestAnswer(resp, "example.com", 28, make([]byte, 16))
		gvisorTestCounts(resp, 1, 0, 0)
		resp = resp[:len(resp)-8] // truncate into the AAAA rdata
		if got := gvisorFilterDNSAAAA(resp); &got[0] != &resp[0] {
			t.Fatal("expected malformed record stream to be returned unchanged")
		}
	})

	t.Run("toggle_off_keeps_aaaa", func(t *testing.T) {
		restore := GetGvisorDisableIPv6()
		defer func() { SetGvisorDisableIPv6(restore) }()

		SetGvisorDisableIPv6(false)
		if GetGvisorDisableIPv6() {
			t.Fatal("SetGvisorDisableIPv6(false) must disable blocking")
		}

		resp := gvisorTestQuery(t, "example.com", 28)
		resp[2] = 0x81
		resp[3] = 0x80
		v6 := make([]byte, 16)
		v6[0] = 0x20
		v6[1] = 0x01
		resp = gvisorTestAnswer(resp, "example.com", 28, v6)
		resp = gvisorTestAnswer(resp, "example.com", 1, []byte{93, 184, 216, 34})
		gvisorTestCounts(resp, 2, 0, 0)

		got := gvisorFilterDNSAAAA(resp)
		if &got[0] != &resp[0] {
			t.Fatal("toggle OFF: response must pass through unmodified (zero-copy)")
		}

		SetGvisorDisableIPv6(true)
		if !GetGvisorDisableIPv6() {
			t.Fatal("SetGvisorDisableIPv6(true) must restore blocking")
		}
	})
}

// ── SOCKS5 handshake (over net.Pipe) ─────────────────────────────────────────

type gvisorTestSocksResult struct {
	creds  string // "user/pass" as received by the far end ("" when no auth)
	target string // CONNECT target as received ("" when never reached)
	err    string // far-end failure label ("" on success)
}

// gvisorTestPipeServer drives the far end of a net.Pipe through a SOCKS5
// handshake script. methodSelection is the byte the server answers the
// greeting with (0x00 no-auth, 0x02 USER_PASS). expectUser/expectPass are the
// credentials the server requires (authOK picks matched/mismatched); when
// credentials are requested but prove missing, authOK=false aborts with the
// auth-status byte. connectReply is written verbatim as the CONNECT reply.
func gvisorTestPipeServer(pipe net.Conn, methodSelection byte, authOK bool, expectUser, expectPass string, connectReply []byte, out chan<- gvisorTestSocksResult) {
	defer func() { _ = pipe.Close() }()
	res := gvisorTestSocksResult{}

	greet := make([]byte, 3)
	if n, err := io.ReadFull(pipe, greet); n != 3 || err != nil {
		res.err = fmt.Sprintf("greeting: read %d: %v", n, err)
		out <- res
		return
	}
	if greet[0] != 0x05 || greet[1] != 0x01 {
		res.err = fmt.Sprintf("greeting: ver=%d nmeth=%d", greet[0], greet[1])
		out <- res
		return
	}
	if greet[2] != 0x00 && greet[2] != 0x02 {
		res.err = fmt.Sprintf("greeting: unexpected method offered 0x%02x", greet[2])
		out <- res
		return
	}
	if _, werr := pipe.Write([]byte{0x05, methodSelection}); werr != nil {
		res.err = "method write: " + werr.Error()
		out <- res
		return
	}

	if methodSelection == 0x02 {
		var ver [1]byte
		if _, err := io.ReadFull(pipe, ver[:]); err != nil {
			res.err = "auth ver: " + err.Error()
			out <- res
			return
		}
		var ulen [1]byte
		if _, err := io.ReadFull(pipe, ulen[:]); err != nil {
			res.err = "auth ulen: " + err.Error()
			out <- res
			return
		}
		ub := make([]byte, int(ulen[0]))
		if _, err := io.ReadFull(pipe, ub); err != nil {
			res.err = "auth user: " + err.Error()
			out <- res
			return
		}
		var plen [1]byte
		if _, err := io.ReadFull(pipe, plen[:]); err != nil {
			res.err = "auth plen: " + err.Error()
			out <- res
			return
		}
		pb := make([]byte, int(plen[0]))
		if _, err := io.ReadFull(pipe, pb); err != nil {
			res.err = "auth pass: " + err.Error()
			out <- res
			return
		}
		res.creds = string(ub) + "/" + string(pb)
		if string(ub) != expectUser || string(pb) != expectPass {
			_, _ = pipe.Write([]byte{0x01, 0x01})
			out <- res
			return
		}
		if _, err := pipe.Write([]byte{0x01, 0x00}); err != nil {
			res.err = "auth reply write: " + err.Error()
			out <- res
			return
		}
	}

	// CONNECT: VER CMD RSV ATYP then address then port.
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(pipe, hdr); err != nil {
		res.err = "connect hdr: " + err.Error()
		out <- res
		return
	}
	var host string
	switch hdr[3] {
	case 0x01:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(pipe, ip); err != nil {
			res.err = "connect v4: " + err.Error()
			out <- res
			return
		}
		host = net.IP(ip).String()
	case 0x03:
		lb := make([]byte, 1)
		if _, err := io.ReadFull(pipe, lb); err != nil {
			res.err = "connect domlen: " + err.Error()
			out <- res
			return
		}
		dn := make([]byte, int(lb[0]))
		if _, err := io.ReadFull(pipe, dn); err != nil {
			res.err = "connect domain: " + err.Error()
			out <- res
			return
		}
		host = string(dn)
	case 0x04:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(pipe, ip); err != nil {
			res.err = "connect v6: " + err.Error()
			out <- res
			return
		}
		host = net.IP(ip).String()
	default:
		res.err = fmt.Sprintf("connect atyp unknown: %d", hdr[3])
		out <- res
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(pipe, portBuf); err != nil {
		res.err = "connect port: " + err.Error()
		out <- res
		return
	}
	port := int(binary.BigEndian.Uint16(portBuf))
	res.target = net.JoinHostPort(host, strconv.Itoa(port))

	if _, werr := pipe.Write(connectReply); werr != nil {
		res.err = "connect reply write: " + werr.Error()
		out <- res
		return
	}
	out <- res
}

// gvisorTestRunHandshake connects both pipe halves, launches the far-end
// script and exercises the bridge handshake; returns the client error, the
// far-end result snapshot and a function that releases both halves.
func gvisorTestRunHandshake(t *testing.T, wrapAuth *gvisorSocksCred, methodSelection byte, authOK bool, expectUser, expectPass, connectTarget string, connectReply []byte) (error, gvisorTestSocksResult) {
	t.Helper()
	clientPipe, serverPipe := net.Pipe()
	t.Cleanup(func() {
		_ = clientPipe.Close()
		_ = serverPipe.Close()
	})
	out := make(chan gvisorTestSocksResult, 1)
	go gvisorTestPipeServer(serverPipe, methodSelection, authOK, expectUser, expectPass, connectReply, out)

	var conn net.Conn = clientPipe
	if wrapAuth != nil {
		conn = &gvisorSocksAuthConn{Conn: clientPipe, cred: *wrapAuth}
	}
	clientErr := gvisorSocks5ConnectTCP(conn, connectTarget, 443)
	// Handshake is over (success or refusal) — release both halves now so a
	// far-end write the client never drained (refusal mid-reply) unblocks
	// immediately instead of waiting for t.Cleanup.
	_ = clientPipe.Close()
	_ = serverPipe.Close()

	select {
	case res := <-out:
		return clientErr, res
	case <-time.After(3 * time.Second):
		t.Fatalf("pipe server script did not complete (clientErr=%v)", clientErr)
		return nil, gvisorTestSocksResult{}
	}
}

func TestGvisorSocks5Handshake(t *testing.T) {
	successReply := []byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}

	t.Run("no_auth_connect_succeeds", func(t *testing.T) {
		clientErr, res := gvisorTestRunHandshake(t, nil, 0x00, true, "", "", "example.com", successReply)
		if clientErr != nil {
			t.Fatalf("expected success, got error: %v", clientErr)
		}
		if res.err != "" {
			t.Fatalf("unexpected far-end failure: %q", res.err)
		}
		if res.creds != "" {
			t.Fatalf("no-auth flow must not send credentials, got %q", res.creds)
		}
		if res.target != "example.com:443" {
			t.Fatalf("unexpected CONNECT target: %q", res.target)
		}
	})

	t.Run("user_pass_subnegotiation_succeeds", func(t *testing.T) {
		clientErr, res := gvisorTestRunHandshake(t, &gvisorSocksCred{user: "u", pass: "p"}, 0x02, true, "u", "p", "example.com", successReply)
		if clientErr != nil {
			t.Fatalf("expected success, got error: %v", clientErr)
		}
		if res.err != "" {
			t.Fatalf("unexpected far-end failure: %q", res.err)
		}
		if res.creds != "u/p" {
			t.Fatalf("credentials not forwarded: got %q", res.creds)
		}
		if res.target != "example.com:443" {
			t.Fatalf("CONNECT target lost after auth: %q", res.target)
		}
	})

	t.Run("wrong_password_errors", func(t *testing.T) {
		clientErr, res := gvisorTestRunHandshake(t, &gvisorSocksCred{user: "u", pass: "WRONG"}, 0x02, true, "u", "p", "example.com", successReply)
		if clientErr == nil {
			t.Fatal("expected error on wrong password, got nil")
		}
		if !strings.Contains(clientErr.Error(), "rejected") {
			t.Fatalf("error should mention auth rejection, got: %v", clientErr)
		}
		if res.creds != "u/WRONG" {
			t.Fatalf("expected far end to receive mismatched creds, got %q", res.creds)
		}
	})

	t.Run("connect_refused_code3_errors", func(t *testing.T) {
		refusedReply := []byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
		clientErr, res := gvisorTestRunHandshake(t, nil, 0x00, true, "", "", "192.0.2.44", refusedReply)
		if clientErr == nil {
			t.Fatal("expected error on code=3 refusal, got nil")
		}
		if !strings.Contains(clientErr.Error(), "code=3") {
			t.Fatalf("error should mention code=3, got: %v", clientErr)
		}
		if res.target != "192.0.2.44:443" {
			t.Fatalf("unexpected CONNECT target at far end: %q", res.target)
		}
	})
}

func TestGvisorSocks5ConnectRequest(t *testing.T) {
	t.Run("ipv4_ten_bytes_atyp1", func(t *testing.T) {
		req := gvisorBuildSocks5ConnectRequest("192.0.2.10", 443)
		if len(req) != 10 {
			t.Fatalf("expected 10-byte IPv4 request, got %d bytes: % x", len(req), req)
		}
		if req[3] != 0x01 {
			t.Fatalf("expected ATYP=0x01, got 0x%02x", req[3])
		}
		want := [...]byte{192, 0, 2, 10}
		if got := req[4:8]; string(got) != string(want[:]) {
			t.Fatalf("wrong address bytes: % x", got)
		}
		if binary.BigEndian.Uint16(req[8:10]) != 443 {
			t.Fatalf("wrong port: %d", binary.BigEndian.Uint16(req[8:10]))
		}
	})

	t.Run("domain_atyp3_with_length_byte", func(t *testing.T) {
		req := gvisorBuildSocks5ConnectRequest("example.com", 443)
		if len(req) != 7+len("example.com") {
			t.Fatalf("expected %d-byte domain request, got %d bytes", 7+len("example.com"), len(req))
		}
		if req[3] != 0x03 {
			t.Fatalf("expected ATYP=0x03, got 0x%02x", req[3])
		}
		if req[4] != byte(len("example.com")) {
			t.Fatalf("wrong domain length byte: %d", req[4])
		}
		if string(req[5:5+len("example.com")]) != "example.com" {
			t.Fatalf("wrong domain bytes: %q", string(req[5:5+len("example.com")]))
		}
		if binary.BigEndian.Uint16(req[5+len("example.com"):]) != 443 {
			t.Fatalf("wrong port: %d", binary.BigEndian.Uint16(req[5+len("example.com"):]))
		}
	})

	t.Run("ipv6_twentytwo_bytes_atyp4", func(t *testing.T) {
		req := gvisorBuildSocks5ConnectRequest("2001:db8::1", 443)
		if len(req) != 22 {
			t.Fatalf("expected 22-byte IPv6 request, got %d bytes", len(req))
		}
		if req[3] != 0x04 {
			t.Fatalf("expected ATYP=0x04, got 0x%02x", req[3])
		}
		want := net.ParseIP("2001:db8::1").To16()
		if !bytesEqual(req[4:20], want) {
			t.Fatalf("wrong address bytes: % x vs % x", req[4:20], want)
		}
		if binary.BigEndian.Uint16(req[20:22]) != 443 {
			t.Fatalf("wrong port: %d", binary.BigEndian.Uint16(req[20:22]))
		}
	})
}

func bytesEqual(a, b []byte) bool {
	return len(a) == len(b) && string(a) == string(b)
}
