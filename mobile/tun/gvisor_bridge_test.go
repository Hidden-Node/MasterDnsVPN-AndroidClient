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
	"testing"
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
