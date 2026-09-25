package tun

// Plan 047 step 3 parity harness (CI-runnable, lives in mobile/tun because
// package mobile cannot be imported here — cycle: mobile imports tun).
// It duplicates BOTH counting shapes:
//
//   - tracking shape: handleTracking body from mobile/mobile.go with the
//     net.Dial side replaced by a net.Pipe pair, both directions pumped
//     through the 2-slot errc idiom, client side wrapped with a
//     trackingConn-equivalent that counts per-byte via atomics.
//   - direct shape: the same relay topology but the client side wrapped
//     with parityDirectConn, a duplicate of mobile.go's directConn
//     (8 shards, round-robin shard assignment, local deltas, 32KB flush
//     or on close).
//
// The test pumps a deterministic 10MB each direction with chunk sizes
// {1024, 16384, 65536} through each shape and asserts the folded totals
// are EXACTLY equal to each other and to the fixture volume.

import (
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
)

const (
	parityShardCount = 8
	parityFlushBytes = 32 * 1024
	parityVolume     = 10 * 1024 * 1024 // 10MB each direction
)

type parityTrackingConn struct {
	net.Conn
	onRead  func(int64)
	onWrite func(int64)
}

func (t *parityTrackingConn) Read(b []byte) (int, error) {
	n, err := t.Conn.Read(b)
	if n > 0 && t.onRead != nil {
		t.onRead(int64(n))
	}
	return n, err
}

func (t *parityTrackingConn) Write(b []byte) (int, error) {
	n, err := t.Conn.Write(b)
	if n > 0 && t.onWrite != nil {
		t.onWrite(int64(n))
	}
	return n, err
}

// parityDirectConn duplicates mobile.go directConn shape line-for-line:
// Read counts up, Write counts down, local deltas flush every 32KB or on
// Close, shard assigned round-robin.
type parityDirectConn struct {
	net.Conn
	shard   int
	upBuf   int64
	downBuf int64
	closed  int32
	mu      sync.Mutex
	shardsU *[parityShardCount]int64
	shardsD *[parityShardCount]int64
}

var parityRR uint64

func newParityDirectConn(c net.Conn, u, d *[parityShardCount]int64) *parityDirectConn {
	shard := int(atomic.AddUint64(&parityRR, 1) % parityShardCount)
	return &parityDirectConn{Conn: c, shard: shard, shardsU: u, shardsD: d}
}

func (d *parityDirectConn) Read(b []byte) (int, error) {
	n, err := d.Conn.Read(b)
	if n > 0 {
		d.mu.Lock()
		d.upBuf += int64(n)
		if d.upBuf >= parityFlushBytes {
			atomic.AddInt64(&d.shardsU[d.shard], d.upBuf)
			d.upBuf = 0
		}
		d.mu.Unlock()
	}
	return n, err
}

func (d *parityDirectConn) Write(b []byte) (int, error) {
	n, err := d.Conn.Write(b)
	if n > 0 {
		d.mu.Lock()
		d.downBuf += int64(n)
		if d.downBuf >= parityFlushBytes {
			atomic.AddInt64(&d.shardsD[d.shard], d.downBuf)
			d.downBuf = 0
		}
		d.mu.Unlock()
	}
	return n, err
}

func (d *parityDirectConn) Close() error {
	err := d.Conn.Close()
	if atomic.CompareAndSwapInt32(&d.closed, 0, 1) {
		d.mu.Lock()
		if d.upBuf != 0 {
			atomic.AddInt64(&d.shardsU[d.shard], d.upBuf)
			d.upBuf = 0
		}
		if d.downBuf != 0 {
			atomic.AddInt64(&d.shardsD[d.shard], d.downBuf)
			d.downBuf = 0
		}
		d.mu.Unlock()
	}
	return err
}

func foldParity(u, d *[parityShardCount]int64) (int64, int64) {
	var up, down int64
	for i := 0; i < parityShardCount; i++ {
		up += atomic.LoadInt64(&u[i])
		down += atomic.LoadInt64(&d[i])
	}
	return up, down
}

// pumpBothStreams pushes volume bytes engineSide->proxySide (up) and
// volume bytes proxySide->engineSide (down) concurrently with chunk-sized
// writes, verifying every byte arrives. All four goroutines run
// concurrently so synchronous net.Pipe pairs never deadlock.
func pumpBothStreams(t *testing.T, engineSide, proxySide net.Conn, chunk, volume int) {
	t.Helper()

	upFill, downFill := byte(0xAB), byte(0xCD)
	var wg sync.WaitGroup
	errCh := make(chan error, 4)
	wg.Add(4)

	// up writer
	go func() {
		defer wg.Done()
		wbuf := make([]byte, chunk)
		for i := range wbuf {
			wbuf[i] = upFill
		}
		remaining := volume
		for remaining > 0 {
			n := chunk
			if n > remaining {
				n = remaining
			}
			if _, err := engineSide.Write(wbuf[:n]); err != nil {
				errCh <- err
				return
			}
			remaining -= n
		}
	}()

	// up reader
	go func() {
		defer wg.Done()
		rbuf := make([]byte, chunk)
		remaining := volume
		for remaining > 0 {
			n, err := proxySide.Read(rbuf)
			if n > 0 {
				for _, b := range rbuf[:n] {
					if b != upFill {
						t.Errorf("up stream byte = 0x%02X, want 0x%02X", b, upFill)
					}
				}
				remaining -= n
			}
			if err != nil {
				if remaining != 0 {
					errCh <- err
				}
				return
			}
		}
	}()

	// down writer
	go func() {
		defer wg.Done()
		wbuf := make([]byte, chunk)
		for i := range wbuf {
			wbuf[i] = downFill
		}
		remaining := volume
		for remaining > 0 {
			n := chunk
			if n > remaining {
				n = remaining
			}
			if _, err := proxySide.Write(wbuf[:n]); err != nil {
				errCh <- err
				return
			}
			remaining -= n
		}
	}()

	// down reader
	go func() {
		defer wg.Done()
		rbuf := make([]byte, chunk)
		remaining := volume
		for remaining > 0 {
			n, err := engineSide.Read(rbuf)
			if n > 0 {
				for _, b := range rbuf[:n] {
					if b != downFill {
						t.Errorf("down stream byte = 0x%02X, want 0x%02X", b, downFill)
					}
				}
				remaining -= n
			}
			if err != nil {
				if remaining != 0 {
					errCh <- err
				}
				return
			}
		}
	}()

	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatalf("pump error (chunk=%d): %v", chunk, err)
	}
}

func runTrackingShape(t *testing.T, chunk, volume int) (up, down int64) {
	t.Helper()

	engineSide, relayClientRaw := net.Pipe()
	relayServer, proxySide := net.Pipe()

	var trackedUp, trackedDown int64
	tc := &parityTrackingConn{
		Conn:    relayClientRaw,
		onRead:  func(n int64) { atomic.AddInt64(&trackedUp, n) },
		onWrite: func(n int64) { atomic.AddInt64(&trackedDown, n) },
	}

	errc := make(chan error, 2)
	go func() {
		_, err := io.Copy(relayServer, tc)
		errc <- err
	}()
	go func() {
		_, err := io.Copy(tc, relayServer)
		errc <- err
	}()

	pumpBothStreams(t, engineSide, proxySide, chunk, volume)

	engineSide.Close()
	proxySide.Close()
	relayClientRaw.Close()
	relayServer.Close()
	<-errc
	<-errc

	return atomic.LoadInt64(&trackedUp), atomic.LoadInt64(&trackedDown)
}

func runDirectShape(t *testing.T, chunk, volume int) (up, down int64) {
	t.Helper()

	engineSide, relayClientRaw := net.Pipe()
	relayServer, proxySide := net.Pipe()

	var shardsU, shardsD [parityShardCount]int64
	dc := newParityDirectConn(relayClientRaw, &shardsU, &shardsD)

	errc := make(chan error, 2)
	go func() {
		_, err := io.Copy(relayServer, dc)
		errc <- err
	}()
	go func() {
		_, err := io.Copy(dc, relayServer)
		errc <- err
	}()

	pumpBothStreams(t, engineSide, proxySide, chunk, volume)

	engineSide.Close()
	proxySide.Close()
	dc.Close()
	relayServer.Close()
	<-errc
	<-errc

	return foldParity(&shardsU, &shardsD)
}

func TestBandwidthParityDirectVsTracking(t *testing.T) {
	chunks := []int{1024, 16384, 65536}
	for _, chunk := range chunks {
		t.Run("", func(t *testing.T) {
			atomic.StoreUint64(&parityRR, 0)
			trUp, trDown := runTrackingShape(t, chunk, parityVolume)
			atomic.StoreUint64(&parityRR, 0)
			diUp, diDown := runDirectShape(t, chunk, parityVolume)

			if trUp != int64(parityVolume) || trDown != int64(parityVolume) {
				t.Fatalf("chunk %d: tracking shape = (%d,%d), want (%d,%d)",
					chunk, trUp, trDown, parityVolume, parityVolume)
			}
			if diUp != int64(parityVolume) || diDown != int64(parityVolume) {
				t.Fatalf("chunk %d: direct shape = (%d,%d), want (%d,%d)",
					chunk, diUp, diDown, parityVolume, parityVolume)
			}
			if trUp != diUp || trDown != diDown {
				t.Fatalf("chunk %d: tracking (%d,%d) != direct (%d,%d)",
					chunk, trUp, trDown, diUp, diDown)
			}
		})
	}
}
