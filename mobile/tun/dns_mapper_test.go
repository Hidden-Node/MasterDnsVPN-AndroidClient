package tun

import (
	"sync"
	"testing"
)

func TestDNSMapper_GetFakeIP_AssignsSequentialIPsIn198_18Range(t *testing.T) {
	d := NewDNSMapper()

	first := d.GetFakeIP("a.example")
	if first != "198.18.0.2" {
		// counter starts at 1; first AddUint32 -> 2 in NewDNSMapper's setup
		t.Fatalf("first fake IP = %q, want 198.18.0.2", first)
	}

	second := d.GetFakeIP("b.example")
	if second != "198.18.0.3" {
		t.Fatalf("second fake IP = %q, want 198.18.0.3", second)
	}
}

func TestDNSMapper_GetFakeIP_StableForSameHostname(t *testing.T) {
	d := NewDNSMapper()

	first := d.GetFakeIP("dup.example")
	second := d.GetFakeIP("dup.example")
	if first != second {
		t.Fatalf("duplicate hostname mapped to %q then %q", first, second)
	}
}

func TestDNSMapper_GetHostname_RoundTrips(t *testing.T) {
	d := NewDNSMapper()

	const host = "roundtrip.example"
	ip := d.GetFakeIP(host)

	got, ok := d.GetHostname(ip)
	if !ok {
		t.Fatalf("GetHostname(%q) returned ok=false", ip)
	}
	if got != host {
		t.Fatalf("GetHostname(%q) = %q, want %q", ip, got, host)
	}
}

func TestDNSMapper_GetHostname_UnknownIPOKFalse(t *testing.T) {
	d := NewDNSMapper()
	if _, ok := d.GetHostname("198.18.99.99"); ok {
		t.Fatal("GetHostname returned ok=true for unmapped IP")
	}
}

func TestDNSMapper_GetFakeIP_ConcurrentHitStorm(t *testing.T) {
	d := NewDNSMapper()

	const goroutines = 16
	const perGoroutine = 50
	results := make([]string, goroutines*perGoroutine)
	var wg sync.WaitGroup
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for i := 0; i < perGoroutine; i++ {
				results[g*perGoroutine+i] = d.GetFakeIP("storm.example")
			}
		}(g)
	}
	wg.Wait()

	first := results[0]
	for i, got := range results {
		if got != first {
			t.Fatalf("results[%d] = %q, want %q (all identical)", i, got, first)
		}
	}

	d.mu.RLock()
	size := len(d.hostnameToIP)
	d.mu.RUnlock()
	if size != 1 {
		t.Fatalf("map size = %d, want 1 (single insert under storm)", size)
	}
}

func TestDNSMapper_GetFakeIP_CounterWrapsToOne(t *testing.T) {
	d := NewDNSMapper()

	d.counter = 65534
	if got := d.GetFakeIP("wrap-a.example"); got != "198.18.255.255" {
		t.Fatalf("pre-wrap mapping = %q, want 198.18.255.255", got)
	}
	if got := d.GetFakeIP("wrap-b.example"); got != "198.18.0.1" {
		t.Fatalf("wrapped mapping = %q, want 198.18.0.1", got)
	}
	// Counter must have wrapped back to 1, so the next fresh
	// hostname continues from 2.
	if got := d.GetFakeIP("wrap-c.example"); got != "198.18.0.2" {
		t.Fatalf("post-wrap mapping = %q, want 198.18.0.2", got)
	}
}
