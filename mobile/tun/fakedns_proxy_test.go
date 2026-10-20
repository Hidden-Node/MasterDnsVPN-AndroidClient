package tun

import (
	"encoding/binary"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// helper: build a DNS query for a hostname like "example.com" with an explicit QTYPE
func buildQueryWithType(t *testing.T, hostname string, qtype uint16) []byte {
	t.Helper()
	// Header: 12 bytes (id, flags, qdcount=1, ancount=0, nscount=0, arcount=0)
	q := make([]byte, 12)
	binary.BigEndian.PutUint16(q[0:2], 0x1234) // id
	binary.BigEndian.PutUint16(q[4:6], 1)      // qdcount = 1

	// Question section
	parts := strings.Split(hostname, ".")
	for _, label := range parts {
		if len(label) > 63 {
			t.Fatalf("label %q too long for test builder", label)
		}
		q = append(q, byte(len(label)))
		q = append(q, []byte(label)...)
	}
	q = append(q, 0)                           // terminator
	q = append(q, byte(qtype>>8), byte(qtype)) // QTYPE
	q = append(q, 0, 1)                        // QCLASS = IN
	return q
}

// buildQuery builds a type-A query; thin wrapper over buildQueryWithType.
func buildQuery(t *testing.T, hostname string) []byte {
	t.Helper()
	return buildQueryWithType(t, hostname, 1)
}

// buildQueryWithOPT appends a minimal EDNS OPT pseudo-record (TYPE 41) and
// sets ARCOUNT=1, mimicking Android netd queries.
func buildQueryWithOPT(t *testing.T, hostname string, qtype uint16) []byte {
	t.Helper()
	q := buildQueryWithType(t, hostname, qtype)
	binary.BigEndian.PutUint16(q[10:12], 1) // arcount = 1
	// Minimal OPT: NAME=root, TYPE=41, CLASS=4096 (requestor UDP size),
	// TTL=0 (extended RCODE + flags), RDLEN=0.
	q = append(q, 0, 0, 41, 16, 0, 0, 0, 0, 0, 0, 0)
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

// TestParseDNSQueryEquivalence is the plan 047 tripwire for the QNAME
// parser rewrite: every input must produce a byte-identical result before
// and after. Proven by running this test on the stashed baseline
// (`git stash push -- mobile/tun/fakedns_proxy.go`) and on the rewritten
// parser — it must PASS on both trees.
func TestParseDNSQueryEquivalence(t *testing.T) {
	long63 := strings.Repeat("a", 63)
	long64 := strings.Repeat("b", 64)

	valid := buildQuery(t, "example.com")
	truncatedTail := valid[:15] // header + [7]ex: length 7 claims 7 bytes, 2 remain
	noTerminator := append(append([]byte{}, valid[:12]...), 3, 'f', 'o', 'o')

	overlong := make([]byte, 12)
	binary.BigEndian.PutUint16(overlong[4:6], 1)
	overlong = append(overlong, byte(64))
	overlong = append(overlong, []byte(long64)...)
	overlong = append(overlong, 0, 0, 1, 0, 1)

	emptyQname := append(append([]byte{}, valid[:12]...), 0, 0, 1, 0, 1)

	cases := []struct {
		name  string
		query []byte
		want  string
	}{
		{"valid", valid, "example.com"},
		{"multiLabel", buildQuery(t, "a.bb.ccc.dddd.example.com"), "a.bb.ccc.dddd.example.com"},
		{"singleLabel", buildQuery(t, "localhost"), "localhost"},
		{"empty", []byte{}, ""},
		{"headerOnly", make([]byte, 12), ""},
		{"tooShort", []byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, ""},
		{"truncatedTail", truncatedTail, ""},
		{"truncatedNoTerminator", noTerminator, "foo"},
		{"overlongLabel", overlong, ""},
		{"label63OK", buildQuery(t, long63+".example"), long63 + ".example"},
		{"emptyQname", emptyQname, ""},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := parseDNSQuery(tc.query); got != tc.want {
				t.Fatalf("parseDNSQuery(%s) = %q, want %q", tc.name, got, tc.want)
			}
		})
	}
}

func TestBuildDNSResponse_ValidIPReturnsAResponse(t *testing.T) {
	q := buildQuery(t, "example.com")
	// RD=1, RCODE=0 — mirrors the 065 tests' 0x0103 setup with a clear RCODE.
	binary.BigEndian.PutUint16(q[2:4], 0x0100)
	resp := buildDNSResponse(q, "198.18.0.5")
	if resp == nil {
		t.Fatal("buildDNSResponse returned nil for valid input")
	}

	// Unified synthetic-answer flags (plan 070): QR+RD+RA, RCODE cleared.
	if flags := binary.BigEndian.Uint16(resp[2:4]); flags != 0x8180 {
		t.Fatalf("response flags = 0x%04X, want 0x8180 (QR+RD+RA, RCODE cleared)", flags)
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

func TestBuildDNSResponse_ClearsNonzeroRcode(t *testing.T) {
	q := buildQuery(t, "example.com")
	// RD=1, RCODE=3: the legacy OR-idiom echoed the nonzero
	// RCODE; the unified idiom must clear it to 0x8180.
	binary.BigEndian.PutUint16(q[2:4], 0x0103)
	resp := buildDNSResponse(q, "198.18.0.5")
	if resp == nil {
		t.Fatal("buildDNSResponse returned nil for valid input")
	}
	if flags := binary.BigEndian.Uint16(resp[2:4]); flags != 0x8180 {
		t.Fatalf("response flags = 0x%04X, want 0x8180 (QR+RD+RA, RCODE cleared)", flags)
	}
	if ancount := binary.BigEndian.Uint16(resp[6:8]); ancount != 1 {
		t.Fatalf("ancount = %d, want 1", ancount)
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

func TestTCPRelayForwardsDomainTypedReplyIntact(t *testing.T) {
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("upstream listen: %v", err)
	}
	defer upstream.Close()

	go func() {
		c, err := upstream.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		greeting := make([]byte, 3)
		io.ReadFull(c, greeting)
		c.Write([]byte{5, 0})
		req := make([]byte, 10)
		io.ReadFull(c, req)
		// Domain-typed bind reply: 05 00 00 03 0B "example.com" 1F 90
		c.Write(append([]byte{5, 0, 0, 3, 11}, append([]byte("example.com"), 0x1F, 0x90)...))
		// Daemon-style: block until the test closes the stub.
		buf := make([]byte, 512)
		for {
			if _, err := c.Read(buf); err != nil {
				return
			}
		}
	}()

	// plan 028 override: production always passes a real mapper (tun_api.go), so the test must too — nil would panic at fakedns_proxy.go:130.
	dnsMap := NewDNSMapper()
	proxy := NewFakeDNSProxy(upstream.Addr().String(), dnsMap)
	addr, err := proxy.Start()
	if err != nil {
		t.Fatalf("proxy.Start: %v", err)
	}
	defer proxy.Stop()

	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := c.Write([]byte{5, 1, 0}); err != nil {
		t.Fatalf("write greeting: %v", err)
	}
	auth := make([]byte, 2)
	if _, err := io.ReadFull(c, auth); err != nil {
		t.Fatalf("read auth: %v", err)
	}
	if _, err := c.Write([]byte{5, 1, 0, 1, 8, 8, 8, 8, 0x1F, 0x90}); err != nil {
		t.Fatalf("write request: %v", err)
	}

	reply := make([]byte, 4)
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatalf("read reply header: %v", err)
	}
	if reply[3] != 3 {
		t.Fatalf("reply ATYP = %d, want 3", reply[3])
	}
	l := make([]byte, 1)
	if _, err := io.ReadFull(c, l); err != nil {
		t.Fatalf("read length byte: %v", err)
	}
	if l[0] != 11 {
		t.Fatalf("domain length byte = %d, want 11 (buggy code returns 'e'=101)", l[0])
	}
	dom := make([]byte, 11)
	if _, err := io.ReadFull(c, dom); err != nil {
		t.Fatalf("read domain: %v", err)
	}
	if string(dom) != "example.com" {
		t.Fatalf("domain = %q, want example.com", dom)
	}
	port := make([]byte, 2)
	if _, err := io.ReadFull(c, port); err != nil {
		t.Fatalf("read port: %v", err)
	}
	if port[0] != 0x1F || port[1] != 0x90 {
		t.Fatalf("port bytes = %02X %02X, want 1F 90", port[0], port[1])
	}
}

func TestDNSQuestionType_ParsesAAAAAndHTTPS(t *testing.T) {
	for _, qtype := range []uint16{28, 65} {
		q := buildQueryWithType(t, "example.com", qtype)
		got, qEnd, ok := dnsQuestionType(q)
		if !ok {
			t.Fatalf("dnsQuestionType(qtype=%d): ok=false, want true", qtype)
		}
		if got != qtype {
			t.Fatalf("dnsQuestionType(qtype=%d) = %d, want %d", qtype, got, qtype)
		}
		if qEnd != len(q) {
			t.Fatalf("dnsQuestionType(qtype=%d): qEnd=%d, want len(query)=%d", qtype, qEnd, len(q))
		}
	}
}

func TestDNSQuestionType_RejectsTruncated(t *testing.T) {
	valid := buildQuery(t, "example.com")

	// Cut mid-QNAME: first label claims 7 bytes but only 2 remain.
	if _, _, ok := dnsQuestionType(valid[:15]); ok {
		t.Fatal("dnsQuestionType cut mid-QNAME: ok=true, want false")
	}

	// Terminator present but QTYPE/QCLASS truncated (1-3 trailing bytes).
	term := 12
	for valid[term] != 0 {
		term += 1 + int(valid[term])
	}
	for _, trailing := range []int{1, 2, 3} {
		if _, _, ok := dnsQuestionType(valid[:term+1+trailing]); ok {
			t.Fatalf("dnsQuestionType truncated QTYPE/QCLASS (%d trailing bytes): ok=true, want false", trailing)
		}
	}

	// QDCOUNT=0 and QDCOUNT=2 must not parse.
	for _, qdcount := range []uint16{0, 2} {
		bad := append([]byte{}, valid...)
		binary.BigEndian.PutUint16(bad[4:6], qdcount)
		if _, _, ok := dnsQuestionType(bad); ok {
			t.Fatalf("dnsQuestionType QDCOUNT=%d: ok=true, want false", qdcount)
		}
	}
}

func TestDNSResponseKind_PinsAllThreeArms(t *testing.T) {
	cases := []struct {
		qtype uint16
		ok    bool
		want  string
	}{
		{1, true, "A"},
		{28, true, "NODATA"},
		{65, true, "NODATA"},
		{1, false, "DROP"},
		{0, false, "DROP"},
	}
	for _, tc := range cases {
		if got := dnsResponseKind(tc.qtype, tc.ok); got != tc.want {
			t.Fatalf("dnsResponseKind(%d, %v) = %q, want %q", tc.qtype, tc.ok, got, tc.want)
		}
	}
}

func TestBuildNODATAResponse_PreservesTxidAndOPT(t *testing.T) {
	q := buildQueryWithOPT(t, "example.com", 28)
	// RD=1, RCODE=3: the response must preserve RD while clearing RCODE.
	binary.BigEndian.PutUint16(q[2:4], 0x0103)
	resp := buildNODATAResponse(q)
	if resp == nil {
		t.Fatal("buildNODATAResponse returned nil for valid OPT query")
	}
	if len(resp) != len(q) {
		t.Fatalf("len(resp)=%d, want len(query)=%d (no answer bytes appended)", len(resp), len(q))
	}
	if binary.BigEndian.Uint16(resp[0:2]) != binary.BigEndian.Uint16(q[0:2]) {
		t.Fatal("buildNODATAResponse did not preserve TXID")
	}
	if flags := binary.BigEndian.Uint16(resp[2:4]); flags != 0x8180 {
		t.Fatalf("response flags = 0x%04X, want 0x8180 (QR+RD+RA, RCODE cleared)", flags)
	}
	if ancount := binary.BigEndian.Uint16(resp[6:8]); ancount != 0 {
		t.Fatalf("ANCOUNT = %d, want 0", ancount)
	}
	if arcount := binary.BigEndian.Uint16(resp[10:12]); arcount != 1 {
		t.Fatalf("ARCOUNT = %d, want 1 (OPT preserved)", arcount)
	}
	if string(resp[4:]) != string(q[4:]) {
		t.Fatal("buildNODATAResponse did not preserve question+OPT bytes verbatim")
	}
}

func TestNonAQueriesGetNODATAEndToEnd(t *testing.T) {
	for _, qtype := range []uint16{28, 65} {
		q := buildQueryWithType(t, "example.com", qtype)
		qt, _, ok := dnsQuestionType(q)
		if kind := dnsResponseKind(qt, ok); kind != "NODATA" {
			t.Fatalf("qtype=%d: kind = %q, want NODATA", qtype, kind)
		}
		resp := buildNODATAResponse(q)
		if resp == nil {
			t.Fatalf("qtype=%d: buildNODATAResponse returned nil", qtype)
		}
		if ancount := binary.BigEndian.Uint16(resp[6:8]); ancount != 0 {
			t.Fatalf("qtype=%d: ANCOUNT = %d, want 0", qtype, ancount)
		}
	}

	q := buildQuery(t, "example.com")
	qt, _, ok := dnsQuestionType(q)
	if kind := dnsResponseKind(qt, ok); kind != "A" {
		t.Fatalf("qtype=A: kind = %q, want A", kind)
	}
	resp := buildDNSResponse(q, "198.18.0.5")
	if resp == nil {
		t.Fatal("qtype=A: buildDNSResponse returned nil")
	}
	if ancount := binary.BigEndian.Uint16(resp[6:8]); ancount != 1 {
		t.Fatalf("qtype=A: ANCOUNT = %d, want 1", ancount)
	}
}

func TestAAAAQueryDoesNotConsumeFakeIP(t *testing.T) {
	dnsMap := NewDNSMapper()
	q := buildQueryWithType(t, "example.com", 28)
	qtype, _, ok := dnsQuestionType(q)
	if kind := dnsResponseKind(qtype, ok); kind != "NODATA" {
		t.Fatalf("AAAA query kind = %q, want NODATA", kind)
	}
	// The NODATA path answers from buildNODATAResponse without calling
	// GetFakeIP, so the map must stay empty.
	if resp := buildNODATAResponse(q); resp == nil {
		t.Fatal("buildNODATAResponse returned nil for AAAA query")
	}
	dnsMap.mu.RLock()
	size := len(dnsMap.hostnameToIP)
	dnsMap.mu.RUnlock()
	if size != 0 {
		t.Fatalf("map size = %d, want 0 (AAAA decision allocates no fake IP)", size)
	}
}

func TestAWithOPTGetsStrippedValidA(t *testing.T) {
	dnsMap := NewDNSMapper()
	q := buildQueryWithOPT(t, "example.com", 1)
	// RD=1, RCODE=3: the stripped response must preserve RD, clear RCODE.
	binary.BigEndian.PutUint16(q[2:4], 0x0103)
	qtype, qEnd, ok := dnsQuestionType(q)
	if !ok || qtype != 1 {
		t.Fatalf("dnsQuestionType OPT A query: qtype=%d ok=%v, want 1 true", qtype, ok)
	}
	if kind := dnsResponseKind(qtype, ok); kind != "A" {
		t.Fatalf("OPT A query kind = %q, want A", kind)
	}
	fakeIP := dnsMap.GetFakeIP("example.com")
	resp := buildStrippedAResponse(q, qEnd, fakeIP)
	if resp == nil {
		t.Fatal("buildStrippedAResponse returned nil for OPT A query")
	}
	if len(resp) != qEnd+16 {
		t.Fatalf("len(resp)=%d, want qEnd+16=%d", len(resp), qEnd+16)
	}
	if flags := binary.BigEndian.Uint16(resp[2:4]); flags != 0x8180 {
		t.Fatalf("response flags = 0x%04X, want 0x8180 (QR+RD+RA, RCODE cleared)", flags)
	}
	if ancount := binary.BigEndian.Uint16(resp[6:8]); ancount != 1 {
		t.Fatalf("ANCOUNT = %d, want 1", ancount)
	}
	if arcount := binary.BigEndian.Uint16(resp[10:12]); arcount != 0 {
		t.Fatalf("ARCOUNT = %d, want 0 (OPT stripped)", arcount)
	}
	// Answer trailer: compression pointer + type A + class IN + ttl 60 +
	// rdlen 4 + the allocated fake IP.
	if resp[qEnd] != 0xC0 || resp[qEnd+1] != 0x0C {
		t.Fatalf("answer pointer = 0x%02X%02X, want 0xC00C", resp[qEnd], resp[qEnd+1])
	}
	if answerType := binary.BigEndian.Uint16(resp[qEnd+2 : qEnd+4]); answerType != 1 {
		t.Fatalf("answer type = %d, want 1 (A)", answerType)
	}
	if ip := net.IP(resp[len(resp)-4:]).String(); ip != fakeIP {
		t.Fatalf("answer IP = %s, want %s", ip, fakeIP)
	}
}

// proxyConnectV4To greets the proxy at proxyAddr and sends a TCP CONNECT to
// ip:port (atyp v4), returning the raw SOCKS reply bytes. Modeled on
// TestTCPRelayForwardsDomainTypedReplyIntact; 5s deadlines throughout.
func proxyConnectV4To(t *testing.T, proxyAddr string, ip net.IP, port uint16) []byte {
	t.Helper()
	c, err := net.Dial("tcp", proxyAddr)
	if err != nil {
		t.Fatalf("dial proxy: %v", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := c.Write([]byte{5, 1, 0}); err != nil {
		t.Fatalf("write greeting: %v", err)
	}
	auth := make([]byte, 2)
	if _, err := io.ReadFull(c, auth); err != nil {
		t.Fatalf("read auth: %v", err)
	}
	v4 := ip.To4()
	if v4 == nil {
		t.Fatalf("test ip %v is not IPv4", ip)
	}
	req := []byte{5, 1, 0, 1, v4[0], v4[1], v4[2], v4[3], byte(port >> 8), byte(port)}
	if _, err := c.Write(req); err != nil {
		t.Fatalf("write request: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatalf("read reply: %v", err)
	}
	return reply
}

// proxyConnectV4 is proxyConnectV4To fixed at 8.8.8.8 (pre-067 callers).
func proxyConnectV4(t *testing.T, proxyAddr string, port uint16) []byte {
	t.Helper()
	return proxyConnectV4To(t, proxyAddr, net.ParseIP("8.8.8.8"), port)
}

func TestTCPDNSPort53FailsFast(t *testing.T) {
	var dials int64
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("upstream listen: %v", err)
	}
	defer upstream.Close()
	go func() {
		for {
			c, err := upstream.Accept()
			if err != nil {
				return
			}
			atomic.AddInt64(&dials, 1)
			c.Close()
		}
	}()

	proxy := NewFakeDNSProxy(upstream.Addr().String(), NewDNSMapper())
	addr, err := proxy.Start()
	if err != nil {
		t.Fatalf("proxy.Start: %v", err)
	}
	defer proxy.Stop()

	reply := proxyConnectV4(t, addr, 53)
	if reply[0] != 5 || reply[1] != 1 {
		t.Fatalf("reply = %02X %02X, want 05 01 (general SOCKS failure)", reply[0], reply[1])
	}
	// The guard returns before any net.Dial, so the stub must see nothing.
	// Sleep past localhost accept latency to catch a late async dial.
	time.Sleep(200 * time.Millisecond)
	if n := atomic.LoadInt64(&dials); n != 0 {
		t.Fatalf("upstream dials = %d, want 0 (port 53 must fail before any net.Dial)", n)
	}
}

func TestTCPDotPort853FailsFast(t *testing.T) {
	var dials int64
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("upstream listen: %v", err)
	}
	defer upstream.Close()
	go func() {
		for {
			c, err := upstream.Accept()
			if err != nil {
				return
			}
			atomic.AddInt64(&dials, 1)
			c.Close()
		}
	}()

	proxy := NewFakeDNSProxy(upstream.Addr().String(), NewDNSMapper())
	addr, err := proxy.Start()
	if err != nil {
		t.Fatalf("proxy.Start: %v", err)
	}
	defer proxy.Stop()

	reply := proxyConnectV4(t, addr, 853)
	if reply[0] != 5 || reply[1] != 1 {
		t.Fatalf("reply = %02X %02X, want 05 01 (general SOCKS failure)", reply[0], reply[1])
	}
	time.Sleep(200 * time.Millisecond)
	if n := atomic.LoadInt64(&dials); n != 0 {
		t.Fatalf("upstream dials = %d, want 0 (port 853 must fail before any net.Dial)", n)
	}
}

func TestTCP443StillForwards(t *testing.T) {
	var dials int64
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("upstream listen: %v", err)
	}
	defer upstream.Close()
	go func() {
		c, err := upstream.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		atomic.AddInt64(&dials, 1)
		greeting := make([]byte, 3)
		if _, err := io.ReadFull(c, greeting); err != nil {
			return
		}
		if _, err := c.Write([]byte{5, 0}); err != nil {
			return
		}
		req := make([]byte, 10)
		if _, err := io.ReadFull(c, req); err != nil {
			return
		}
		if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
			return
		}
		buf := make([]byte, 512)
		for {
			if _, err := c.Read(buf); err != nil {
				return
			}
		}
	}()

	proxy := NewFakeDNSProxy(upstream.Addr().String(), NewDNSMapper())
	addr, err := proxy.Start()
	if err != nil {
		t.Fatalf("proxy.Start: %v", err)
	}
	defer proxy.Stop()

	reply := proxyConnectV4(t, addr, 443)
	if reply[0] != 5 || reply[1] != 0 {
		t.Fatalf("reply = %02X %02X, want 05 00 (success — non-DNS ports must forward)", reply[0], reply[1])
	}
	if n := atomic.LoadInt64(&dials); n != 1 {
		t.Fatalf("upstream dials = %d, want 1 (port 443 must reach the stub)", n)
	}
}

func TestBlockedSOCKSTargetTable(t *testing.T) {
	cases := []struct {
		host string
		want bool
	}{
		{"10.10.34.35", true},
		{"10.10.34.36", true},
		{"192.168.1.1", true},
		{"127.0.0.1", true},
		{"0.0.0.0", true},
		{"198.18.0.5", true},
		{"100.64.0.1", true},
		{"224.0.0.1", true},
		{"::1", true},
		{"8.8.8.8", false},
		{"172.217.114.4", false},
		{"m.youtube.com", false},
		{"", true},
		{"localhost", true},
	}
	for _, tc := range cases {
		if got := gvisorIsBlockedSOCKSTarget(tc.host); got != tc.want {
			t.Errorf("gvisorIsBlockedSOCKSTarget(%q) = %v, want %v", tc.host, got, tc.want)
		}
	}
}

func TestPoisonIPFailsWithoutUpstreamDial(t *testing.T) {
	var dials int64
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("upstream listen: %v", err)
	}
	defer upstream.Close()
	go func() {
		for {
			c, err := upstream.Accept()
			if err != nil {
				return
			}
			atomic.AddInt64(&dials, 1)
			c.Close()
		}
	}()

	proxy := NewFakeDNSProxy(upstream.Addr().String(), NewDNSMapper())
	addr, err := proxy.Start()
	if err != nil {
		t.Fatalf("proxy.Start: %v", err)
	}
	defer proxy.Stop()

	reply := proxyConnectV4To(t, addr, net.ParseIP("10.10.34.35"), 443)
	if reply[0] != 5 || reply[1] != 2 {
		t.Fatalf("reply = %02X %02X, want 05 02 (ruleset denied)", reply[0], reply[1])
	}
	time.Sleep(200 * time.Millisecond)
	if n := atomic.LoadInt64(&dials); n != 0 {
		t.Fatalf("upstream dials = %d, want 0 (poison IP must fail before any net.Dial)", n)
	}
}

func TestRealIPStillForwards(t *testing.T) {
	var dials int64
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("upstream listen: %v", err)
	}
	defer upstream.Close()
	go func() {
		c, err := upstream.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		atomic.AddInt64(&dials, 1)
		greeting := make([]byte, 3)
		if _, err := io.ReadFull(c, greeting); err != nil {
			return
		}
		if _, err := c.Write([]byte{5, 0}); err != nil {
			return
		}
		req := make([]byte, 10)
		if _, err := io.ReadFull(c, req); err != nil {
			return
		}
		if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
			return
		}
		buf := make([]byte, 512)
		for {
			if _, err := c.Read(buf); err != nil {
				return
			}
		}
	}()

	proxy := NewFakeDNSProxy(upstream.Addr().String(), NewDNSMapper())
	addr, err := proxy.Start()
	if err != nil {
		t.Fatalf("proxy.Start: %v", err)
	}
	defer proxy.Stop()

	reply := proxyConnectV4To(t, addr, net.ParseIP("172.217.114.4"), 443)
	if reply[0] != 5 || reply[1] != 0 {
		t.Fatalf("reply = %02X %02X, want 05 00 (success — real IP must forward)", reply[0], reply[1])
	}
	if n := atomic.LoadInt64(&dials); n != 1 {
		t.Fatalf("upstream dials = %d, want 1 (real IP must reach the stub)", n)
	}
}
