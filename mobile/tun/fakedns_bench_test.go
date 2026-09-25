package tun

import (
	"encoding/binary"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
)

// buildBenchQuery mirrors buildQuery without needing a *testing.T so it
// can be used from benchmarks.
func buildBenchQuery(hostname string) []byte {
	q := make([]byte, 12)
	binary.BigEndian.PutUint16(q[0:2], 0x1234) // id
	binary.BigEndian.PutUint16(q[4:6], 1)       // qdcount = 1
	for _, label := range strings.Split(hostname, ".") {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	q = append(q, 0)    // terminator
	q = append(q, 0, 1) // QTYPE = A
	q = append(q, 0, 1) // QCLASS = IN
	return q
}

// benchRelayPool is a local stand-in for the production buffer pool so
// these benchmarks compile and run identically on the pre-pool baseline
// (via `git stash`) and on the pooled production code. It measures the
// mechanism (pool checkout vs fresh make), not the production symbol.
var benchRelayPool = sync.Pool{
	New: func() any { return make([]byte, 65535) },
}

// BenchmarkHandleConnectionHandshake (plan 047 step 2, sub-change 1):
// end-to-end SOCKS5 CONNECT handshake through handleConnection against a
// stub upstream. Captures the ~12 small per-handshake framing allocs.
func BenchmarkHandleConnectionHandshake(b *testing.B) {
	b.ReportAllocs()

	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		b.Fatalf("upstream listen: %v", err)
	}
	defer upstream.Close()
	go func() {
		for {
			c, err := upstream.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				greet := make([]byte, 3)
				if _, err := io.ReadFull(c, greet); err != nil {
					return
				}
				if _, err := c.Write([]byte{5, 0}); err != nil {
					return
				}
				// CONNECT IPv4: 4-byte header + 4-byte addr + 2-byte port.
				req := make([]byte, 10)
				if _, err := io.ReadFull(c, req); err != nil {
					return
				}
				if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
					return
				}
				// Handshake complete: close immediately so the proxy's
				// bulk-relay io.Copy(conn, realConn) gets EOF and
				// handleConnection returns. (Draining here would block
				// that Copy forever and hang the benchmark.)
				return
			}(c)
		}
	}()

	dnsMap := NewDNSMapper()
	proxy := NewFakeDNSProxy(upstream.Addr().String(), dnsMap)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		client, server := net.Pipe()
		done := make(chan struct{})
		go func() {
			defer close(done)
			proxy.handleConnection(server)
		}()

		if _, err := client.Write([]byte{5, 1, 0}); err != nil {
			b.Fatalf("iter %d: write greeting: %v", i, err)
		}
		auth := make([]byte, 2)
		if _, err := io.ReadFull(client, auth); err != nil {
			b.Fatalf("iter %d: read auth: %v", i, err)
		}
		// CONNECT 93.184.216.34:80 — absent from the FakeDNS map, so it
		// takes the plain-forward path.
		if _, err := client.Write([]byte{5, 1, 0, 1, 93, 184, 216, 34, 0, 80}); err != nil {
			b.Fatalf("iter %d: write request: %v", i, err)
		}
		reply := make([]byte, 10)
		if _, err := io.ReadFull(client, reply); err != nil {
			b.Fatalf("iter %d: read reply: %v", i, err)
		}
		client.Close()
		<-done
		server.Close()
	}
}

// BenchmarkDNSQueryParse (plan 047 step 2, sub-change 4): QNAME parsing.
func BenchmarkDNSQueryParse(b *testing.B) {
	b.ReportAllocs()
	q := buildBenchQuery("www.example.com")
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if got := parseDNSQuery(q); got != "www.example.com" {
			b.Fatalf("parseDNSQuery = %q, want www.example.com", got)
		}
	}
}

// BenchmarkDNSReplyBuild (plan 047 step 2, sub-change 3): DNS reply
// assembly. Compares the old shape (fresh slice per reply) against the
// new shape (pooled scratch) with a local pool so both sub-benchmarks
// run on the baseline and on the changed tree.
func BenchmarkDNSReplyBuild(b *testing.B) {
	q := buildBenchQuery("www.example.com")
	header := []byte{3, 1, 0, 0, 127, 0, 0, 1, 0, 53} // 10-byte SOCKS5 UDP prefix

	b.Run("freshSlice", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			resp := buildDNSResponse(q, "198.18.0.5")
			fullResp := make([]byte, 0, len(header)+len(resp))
			fullResp = append(fullResp, header...)
			fullResp = append(fullResp, resp...)
			_ = fullResp
		}
	})

	b.Run("pooledScratch", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			resp := buildDNSResponse(q, "198.18.0.5")
			scratch := benchRelayPool.Get().([]byte)
			need := len(header) + len(resp)
			var fullResp []byte
			if need <= len(scratch) {
				fullResp = scratch[:need]
			} else {
				fullResp = make([]byte, need)
			}
			copy(fullResp, header)
			copy(fullResp[len(header):], resp)
			_ = fullResp
			benchRelayPool.Put(scratch)
		}
	})
}

// BenchmarkBulkRelayCopy (plan 047 step 2, sub-change 2): bulk byte
// pumping. Compares default io.Copy against io.CopyBuffer with a pooled
// buffer, using a local pool so both run on either tree.
func BenchmarkBulkRelayCopy(b *testing.B) {
	const payload = 256 * 1024
	data := make([]byte, payload)
	for i := range data {
		data[i] = byte(i)
	}

	run := func(b *testing.B, copyFn func(dst io.Writer, src io.Reader, buf []byte) (int64, error)) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			r, w := net.Pipe()
			go func() {
				_, _ = w.Write(data)
				w.Close()
			}()
			buf := benchRelayPool.Get().([]byte)
			n, err := copyFn(io.Discard, r, buf)
			benchRelayPool.Put(buf)
			r.Close()
			if err != nil {
				b.Fatalf("iter %d: copy: %v", i, err)
			}
			if n != payload {
				b.Fatalf("iter %d: copied %d bytes, want %d", i, n, payload)
			}
		}
	}

	b.Run("stdCopy", func(b *testing.B) {
		run(b, func(dst io.Writer, src io.Reader, _ []byte) (int64, error) {
			return io.Copy(dst, src)
		})
	})

	b.Run("pooledCopy", func(b *testing.B) {
		run(b, func(dst io.Writer, src io.Reader, buf []byte) (int64, error) {
			return io.CopyBuffer(dst, src, buf)
		})
	})
}
