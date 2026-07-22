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
