package tun

import (
	"fmt"
	"log"
	"sync"
	"sync/atomic"
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
	d.mu.Lock()
	defer d.mu.Unlock()

	if ip, ok := d.hostnameToIP[hostname]; ok {
		return ip
	}

	counter := atomic.AddUint32(&d.counter, 1)
	if counter > 65535 {
		atomic.StoreUint32(&d.counter, 1)
		counter = 1
	}

	octet3 := byte(counter >> 8)
	octet4 := byte(counter & 0xFF)
	fakeIP := fmt.Sprintf("198.18.%d.%d", octet3, octet4)

	d.hostnameToIP[hostname] = fakeIP
	d.ipToHostname[fakeIP] = hostname

	log.Printf("[TUN-DNS] Mapped %s -> %s", hostname, fakeIP)
	return fakeIP
}

func (d *DNSMapper) GetHostname(fakeIP string) (string, bool) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	hostname, ok := d.ipToHostname[fakeIP]
	return hostname, ok
}
