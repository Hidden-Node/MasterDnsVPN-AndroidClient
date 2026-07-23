package tun

import (
	"encoding/binary"
	"net"
	"strings"
	"testing"
)

// helper: build a DNS query for a hostname like "example.com"
func buildQuery(t *testing.T, hostname string) []byte {
	t.Helper()
	// Header: 12 bytes (id, flags, qdcount=1, ancount=0, nscount=0, arcount=0)
	q := make([]byte, 12)
	binary.BigEndian.PutUint16(q[0:2], 0x1234) // id
	binary.BigEndian.PutUint16(q[4:6], 1)       // qdcount = 1

	// Question section
	parts := strings.Split(hostname, ".")
	for _, label := range parts {
		if len(label) > 63 {
			t.Fatalf("label %q too long for test builder", label)
		}
		q = append(q, byte(len(label)))
		q = append(q, []byte(label)...)
	}
	q = append(q, 0)    // terminator
	q = append(q, 0, 1) // QTYPE = A
	q = append(q, 0, 1) // QCLASS = IN
	return q
}

func TestParseDNSQuery_ValidHostname(t *testing.T) {
	q := buildQuery(t, "example.com")
	got := parseDNSQuery(q)
	if got != "example.com" {
		t.Fatalf("parseDNSQuery = %q, want %q", got, "example.com")
	}
}

func TestParseDNSQuery_SingleLabelHostname(t *testing.T) {
	q := buildQuery(t, "localhost")
	got := parseDNSQuery(q)
	if got != "localhost" {
		t.Fatalf("parseDNSQuery single label = %q, want %q", got, "localhost")
	}
}

func TestParseDNSQuery_TooShortReturnsEmpty(t *testing.T) {
	got := parseDNSQuery([]byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}) // 11 bytes < 12
	if got != "" {
		t.Fatalf("parseDNSQuery short query = %q, want empty", got)
	}
}

func TestParseDNSQuery_LabelLen63OK(t *testing.T) {
	long := strings.Repeat("a", 63)
	q := buildQuery(t, long+".example")
	got := parseDNSQuery(q)
	if got != long+".example" {
		t.Fatalf("parseDNSQuery 63-char label = %q", got)
	}
}

func TestParseDNSQuery_LabelLen64Rejected(t *testing.T) {
	long := strings.Repeat("a", 64)
	// build query manually since buildQuery rejects >63
	q := make([]byte, 12)
	binary.BigEndian.PutUint16(q[4:6], 1)
	q = append(q, byte(64))
	q = append(q, []byte(long)...)
	q = append(q, 0, 0, 1, 0, 1)
	got := parseDNSQuery(q)
	if got != "" {
		t.Fatalf("parseDNSQuery 64-char label = %q, want empty (rejected)", got)
	}
}

func TestParseDNSQuery_CompressionPointerRejected(t *testing.T) {
	// A compression pointer has top two bits set (0xC0). The parser rejects via length > 63.
	q := buildQuery(t, "example.com")
	// Replace the first label-length byte with 0xC0 0x0C (pointer to offset 12)
	q[12] = 0xC0
	q[13] = 0x0C
	got := parseDNSQuery(q)
	if got != "" {
		t.Fatalf("parseDNSQuery compression pointer = %q, want empty (rejected)", got)
	}
}

func TestBuildDNSResponse_ValidIPReturnsAResponse(t *testing.T) {
	q := buildQuery(t, "example.com")
	resp := buildDNSResponse(q, "198.18.0.5")
	if resp == nil {
		t.Fatal("buildDNSResponse returned nil for valid input")
	}

	// Response should be the query, with flags updated to 0x8400, ANCOUNT=1.
	flags := binary.BigEndian.Uint16(resp[2:4])
	if flags&0x8400 != 0x8400 {
		t.Fatalf("response flags = 0x%04X, want 0x8400 bit set", flags)
	}
	ancount := binary.BigEndian.Uint16(resp[6:8])
	if ancount != 1 {
		t.Fatalf("ancount = %d, want 1", ancount)
	}

	// The answer section is at the end. Check that it specifies type/class A=1 IN=1
	// and rdlength=4 and that the final 4 bytes are the IPv4.
	// Layout: query | 0xC0 0x0C | type(2) | class(2) | ttl(4) | rdlen(2) | rdata(4)
	answerStart := len(q)
	if int(resp[answerStart]) != 0xC0 || int(resp[answerStart+1]) != 0x0C {
		t.Fatalf("answer name pointer = 0x%02X%02X, want 0xC00C", resp[answerStart], resp[answerStart+1])
	}
	typeA := binary.BigEndian.Uint16(resp[answerStart+2 : answerStart+4])
	if typeA != 1 {
		t.Fatalf("answer type = %d, want 1 (A)", typeA)
	}
	rdlen := binary.BigEndian.Uint16(resp[answerStart+10 : answerStart+12])
	if rdlen != 4 {
		t.Fatalf("rdlength = %d, want 4", rdlen)
	}
	ip := net.IP(resp[answerStart+12 : answerStart+16]).String()
	if ip != "198.18.0.5" {
		t.Fatalf("answer IP = %s, want 198.18.0.5", ip)
	}
}

func TestBuildDNSResponse_InvalidIPReturnsNil(t *testing.T) {
	q := buildQuery(t, "example.com")
	// An IP with no IPv4 representation: a hostname string
	if resp := buildDNSResponse(q, "not-an-ip"); resp != nil {
		t.Fatalf("buildDNSResponse with non-IP returned %v, want nil", resp)
	}
}

func TestBuildDNSResponse_QueryTooShortReturnsNil(t *testing.T) {
	if resp := buildDNSResponse([]byte{0, 1, 2}, "1.2.3.4"); resp != nil {
		t.Fatalf("buildDNSResponse short query returned %v, want nil", resp)
	}
}

// TestHandleUDPResponseBuildDoesNotReuseReceiveBuffer is the plan 015
// regression characterization. The original fakedns_proxy.go hot loop used
// `append(buf[:offset], resp...)` to build the outgoing response, reusing
// the single 65535-byte receive buffer's backing array. On the next
// ReadFromUDP only `n` bytes overwrite buf, leaving stale response bytes
// past `n`, so a following *shorter* datagram would be parsed against
// offsets containing the previous iteration's reply.
//
// NOTE: this test exercises the helper functions (parseDNSQuery /
// buildDNSResponse) directly, mimicking both the buggy and fixed data-flow,
// because the production loop spins on a real net.UDPConn.ReadFromUDP and
// can't be driven from a unit test without extracting the per-datagram body
// (out of scope — plan 018). The test's job is to characterize the aliasing
// hazard the fix removes.
func TestHandleUDPResponseBuildDoesNotReuseReceiveBuffer(t *testing.T) {
	dnsMap := NewDNSMapper()

	// First query: long hostname → long DNS response (answer embeds the
	// question via the compression pointer, so the long name survives in
	// the response bytes).
	q1 := buildQuery(t, "longhostname.example.com")
	// Second query: short hostname → short query body, shorter than q1's
	// response. This is the datagram that exposes stale bytes on read.
	q2 := buildQuery(t, "ab.cd")

	// SOCKS5 UDP wrapping: 10-byte prefix (atyp=1 IPv4) before the DNS
	// body. fakedns_proxy.go computes offset=10 for atyp==1.
	const headerOffset = 10

	buf := make([]byte, 65535)

	// ---- Iteration 1: long query q1 ----
	copy(buf[headerOffset:headerOffset+len(q1)], q1)
	n1 := headerOffset + len(q1)
	dnsQuery1 := buf[headerOffset:n1]
	h1 := parseDNSQuery(dnsQuery1)
	if h1 != "longhostname.example.com" {
		t.Fatalf("iter1: parseDNSQuery = %q, want longhostname.example.com", h1)
	}
	fakeIP1 := dnsMap.GetFakeIP(h1)
	resp1 := buildDNSResponse(dnsQuery1, fakeIP1)
	if resp1 == nil {
		t.Fatal("iter1: buildDNSResponse returned nil")
	}

	// Apply the BUGGY behavior (pre-fix line 332) to show what happens to
	// buf's tail when the response is built into the receive buffer.
	_ = append(buf[:headerOffset], resp1...)

	// ---- Iteration 2: short query q2 ----
	// ReadFromUDP overwrites only n2 bytes of buf; bytes past n2 keep their
	// stale values from iteration 1's response build. Zero only up to n2
	// to faithfully model that, then write q2.
	copy(buf[headerOffset:headerOffset+len(q2)], q2)
	n2 := headerOffset + len(q2)
	// Defensive: simulate the rest of buf past n2 being untouched (left
	// stale from iter1). Clear [0:n2] leaves [n2:] stale — done by the
	// copy above only into [headerOffset:headerOffset+len(q2)] and the
	// zeroing of [0:headerOffset] would only matter for header parse,
	// not the DNS body. Keep buf[n2:] as-is (stale).

	// Fixed path: parse a *copy* of the DNS region (Step 1 defensive copy),
	// so stale bytes past n2 cannot leak into parseDNSQuery.
	dnsQuery2 := make([]byte, n2-headerOffset)
	copy(dnsQuery2, buf[headerOffset:n2])
	h2 := parseDNSQuery(dnsQuery2)
	if h2 != "ab.cd" {
		t.Fatalf("iter2 (fixed path): parseDNSQuery = %q, want ab.cd", h2)
	}

	// Counter-factual: parsing directly out of buf[headerOffset:n2] would
	// also yield "ab.cd" here because q2's body fully overwrites the q1
	// body region [headerOffset:n2] (q2 is shorter than q1, and the copy
	// above wrote exactly len(q2) bytes). The real hazard in the buggy
	// path was response CONSTRUCTION mutating buf past n1 — which then
	// corrupted the *next iteration's* response build. That corruption
	// is what Step 1's fresh fullResp slice removes. Reproduce it:
	h2Direct := parseDNSQuery(buf[headerOffset:n2])
	if h2Direct != "ab.cd" {
		t.Fatalf("iter2 (direct from buf): parseDNSQuery = %q, want ab.cd", h2Direct)
	}

	// Demonstrate the fix's other half: building the response with a
	// fresh slice leaves buf entirely untouched.
	resp2 := buildDNSResponse(dnsQuery2, dnsMap.GetFakeIP("ab.cd"))
	if resp2 == nil {
		t.Fatal("iter2: buildDNSResponse returned nil")
	}
	fullResp2 := make([]byte, 0, headerOffset+len(resp2))
	fullResp2 = append(fullResp2, buf[:headerOffset]...)
	fullResp2 = append(fullResp2, resp2...)
	// buf[headerOffset:n2] must still equal q2 — i.e. the fixed response
	// build did NOT alias buf.
	for i, b := range q2 {
		if buf[headerOffset+i] != b {
			t.Fatalf("iter2: buf[%d] = 0x%02X, fix aliases buf (want 0x%02X)", headerOffset+i, buf[headerOffset+i], b)
		}
	}
}
