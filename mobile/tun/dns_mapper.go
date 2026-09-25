package tun

import (
	"fmt"
	"log"
	"sync"
)

type DNSMapper struct {
	mu           sync.RWMutex
	hostnameToIP map[string]string
	ipToHostname map[string]string
	counter      uint32
}

func NewDNSMapper() *DNSMapper {
	return &DNSMapper{
		hostnameToIP: make(map[string]string),
		ipToHostname: make(map[string]string),
		counter:      1,
	}
}

func (d *DNSMapper) GetFakeIP(hostname string) string {
	d.mu.RLock()
	if ip, ok := d.hostnameToIP[hostname]; ok {
		d.mu.RUnlock()
		return ip
	}
	d.mu.RUnlock()

	d.mu.Lock()
	// Re-check under the write lock: another goroutine may have
	// inserted this hostname while we upgraded from read to write.
	if ip, ok := d.hostnameToIP[hostname]; ok {
		d.mu.Unlock()
		return ip
	}

	d.counter++
	counter := d.counter
	if counter > 65535 {
		d.counter = 1
		counter = 1
	}

	octet3 := byte(counter >> 8)
	octet4 := byte(counter & 0xFF)
	fakeIP := fmt.Sprintf("198.18.%d.%d", octet3, octet4)

	d.hostnameToIP[hostname] = fakeIP
	d.ipToHostname[fakeIP] = hostname
	d.mu.Unlock()

	log.Printf("[TUN-DNS] Mapped %s -> %s", hostname, fakeIP)
	return fakeIP
}

func (d *DNSMapper) GetHostname(fakeIP string) (string, bool) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	hostname, ok := d.ipToHostname[fakeIP]
	return hostname, ok
}
