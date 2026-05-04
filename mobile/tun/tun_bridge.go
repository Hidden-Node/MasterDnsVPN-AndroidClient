// Package tun provides TUN interface handling with DNS interception
// This is a separate module that doesn't modify the original Go code
package tun

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	// DNS server that will be advertised to Android
	TunDNSServer = "172.19.0.2"
	
	// TUN interface addresses
	TunClientIP = "172.19.0.1"
	TunRouterIP = "172.19.0.2"
	
	// Fake DNS range
	FakeDNSStart = "198.18.0.0"
	FakeDNSEnd   = "198.19.255.255"
	
	// Buffer sizes
	tunMTU       = 1500
	relayBufSize = 8192
)

var (
	tunBytesUp   atomic.Int64
	tunBytesDown atomic.Int64
)

// Bridge manages the TUN interface and DNS interception
type Bridge struct {
	tunFd      int
	socksAddr  string
	dnsMap     *DNSMapper
	tcpHandler *TCPHandler
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	protectFn  func(fd int) bool
	udpDropLogNextNs atomic.Int64
	udpDropCount     atomic.Uint64
}

// DNSMapper handles fake DNS mapping
type DNSMapper struct {
	mu              sync.RWMutex
	hostnameToIP    map[string]string
	ipToHostname    map[string]string
	counter         uint32
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
	
	// Generate fake IP: 198.18.x.x
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

// NewBridge creates a new TUN bridge
func NewBridge(tunFd int, socksAddr string, protectFn func(int) bool) *Bridge {
	ctx, cancel := context.WithCancel(context.Background())
	return &Bridge{
		tunFd:      tunFd,
		socksAddr:  socksAddr,
		dnsMap:     NewDNSMapper(),
		tcpHandler: nil,
		ctx:        ctx,
		cancel:     cancel,
		protectFn:  protectFn,
	}
}

// Start starts the TUN bridge
func (b *Bridge) Start() error {
	log.Printf("[TUN-BRIDGE] Starting bridge: tunFd=%d socksAddr=%s", b.tunFd, b.socksAddr)
	
	tunBytesUp.Store(0)
	tunBytesDown.Store(0)
	
	// Create TCP handler
	b.tcpHandler = NewTCPHandler(b, b.socksAddr)
	log.Printf("[TUN-BRIDGE] TCP handler created")
	
	// Start packet processing
	b.wg.Add(1)
	go b.processPackets()
	
	return nil
}

// Stop stops the TUN bridge
func (b *Bridge) Stop() {
	log.Printf("[TUN-BRIDGE] Stopping bridge")
	b.cancel()
	
	if b.tcpHandler != nil {
		b.tcpHandler.Close()
	}
	
	b.wg.Wait()
}

// GetBandwidth returns upload and download bytes
func (b *Bridge) GetBandwidth() (up, down int64) {
	return tunBytesUp.Load(), tunBytesDown.Load()
}

// processPackets reads packets from TUN and handles them
func (b *Bridge) processPackets() {
	defer b.wg.Done()
	
	tunFile := &tunFile{fd: b.tunFd}
	buf := make([]byte, tunMTU+4) // +4 for packet info
	
	for {
		select {
		case <-b.ctx.Done():
			return
		default:
		}
		
		// Read packet from TUN
		n, err := tunFile.Read(buf)
		if err != nil {
			if b.ctx.Err() != nil {
				return
			}
			// Non-blocking TUN reads commonly return EAGAIN/EWOULDBLOCK when idle.
			if errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.EWOULDBLOCK) {
				time.Sleep(10 * time.Millisecond)
				continue
			}
			log.Printf("[TUN-BRIDGE] Read error: %v", err)
			continue
		}
		
		if n < 20 { // Minimum IP header size
			continue
		}
		
		tunBytesUp.Add(int64(n))
		
		// Parse IP packet
		packet := buf[:n]
		b.handlePacket(packet)
	}
}

// handlePacket processes a single IP packet
func (b *Bridge) handlePacket(packet []byte) {
	if len(packet) < 20 {
		return
	}
	
	// Check IP version
	version := packet[0] >> 4
	if version != 4 {
		// IPv6 not supported yet
		return
	}
	
	// Get protocol
	protocol := packet[9]
	
	// Get source and destination IPs
	srcIP := net.IPv4(packet[12], packet[13], packet[14], packet[15])
	dstIP := net.IPv4(packet[16], packet[17], packet[18], packet[19])
	
	// Handle UDP (DNS)
	if protocol == 17 { // UDP
		b.handleUDP(packet, srcIP, dstIP)
		return
	}
	
	// Handle TCP
	if protocol == 6 { // TCP
		b.tcpHandler.HandleTCP(packet, srcIP, dstIP)
		return
	}
}

// handleUDP handles UDP packets (mainly DNS)
func (b *Bridge) handleUDP(packet []byte, srcIP, dstIP net.IP) {
	if len(packet) < 28 { // IP header (20) + UDP header (8)
		return
	}
	
	// Parse UDP header
	srcPort := binary.BigEndian.Uint16(packet[20:22])
	dstPort := binary.BigEndian.Uint16(packet[22:24])
	
	// Check if it's DNS (port 53)
	if dstPort == 53 {
		b.handleDNS(packet, srcIP, srcPort)
		return
	}

	// Other UDP traffic: actively reject to speed up app fallback to TCP.
	b.sendICMPPortUnreachable(packet, srcIP, dstIP)

	// Rate-limit noisy logs while still exposing signal.
	b.udpDropCount.Add(1)
	nowNs := time.Now().UnixNano()
	nextNs := b.udpDropLogNextNs.Load()
	if nowNs >= nextNs && b.udpDropLogNextNs.CompareAndSwap(nextNs, nowNs+int64(2*time.Second)) {
		count := b.udpDropCount.Swap(0)
		log.Printf("[TUN-BRIDGE] Dropped non-DNS UDP packets: %d (latest %s:%d -> %s:%d)",
			count, srcIP, srcPort, dstIP, dstPort)
	}
}

// handleDNS handles DNS queries
func (b *Bridge) handleDNS(packet []byte, srcIP net.IP, srcPort uint16) {
	if len(packet) < 28 {
		return
	}
	
	// Extract DNS query
	dnsQuery := packet[28:]
	
	// Parse DNS query to get hostname
	hostname := b.parseDNSQuery(dnsQuery)
	if hostname == "" {
		return
	}
	
	// Get fake IP for hostname
	fakeIP := b.dnsMap.GetFakeIP(hostname)
	
	// Build DNS response
	response := b.buildDNSResponse(dnsQuery, fakeIP)
	if response == nil {
		return
	}
	
	// Send response back through TUN
	b.sendDNSResponse(packet, srcIP, srcPort, response)
}

// parseDNSQuery extracts hostname from DNS query
func (b *Bridge) parseDNSQuery(query []byte) string {
	if len(query) < 12 {
		return ""
	}
	
	pos := 12 // Skip DNS header
	labels := []string{}
	
	for pos < len(query) {
		length := int(query[pos])
		if length == 0 {
			break
		}
		if length > 63 || pos+1+length > len(query) {
			return ""
		}
		
		pos++
		label := string(query[pos : pos+length])
		labels = append(labels, label)
		pos += length
	}
	
	if len(labels) == 0 {
		return ""
	}
	
	hostname := ""
	for i, label := range labels {
		if i > 0 {
			hostname += "."
		}
		hostname += label
	}
	
	return hostname
}

// buildDNSResponse builds a DNS response with fake IP
func (b *Bridge) buildDNSResponse(query []byte, fakeIP string) []byte {
	if len(query) < 12 {
		return nil
	}
	
	// Copy query
	response := make([]byte, len(query)+16) // +16 for answer section
	copy(response, query)
	
	// Modify flags: QR=1 (response), AA=1 (authoritative)
	flags := binary.BigEndian.Uint16(response[2:4])
	flags |= 0x8400
	binary.BigEndian.PutUint16(response[2:4], flags)
	
	// Set answer count to 1
	binary.BigEndian.PutUint16(response[6:8], 1)
	
	// Add answer section at end of query
	pos := len(query)
	
	// Name pointer to question (0xC00C)
	response[pos] = 0xC0
	response[pos+1] = 0x0C
	pos += 2
	
	// Type A (1), Class IN (1)
	binary.BigEndian.PutUint16(response[pos:pos+2], 1)
	binary.BigEndian.PutUint16(response[pos+2:pos+4], 1)
	pos += 4
	
	// TTL (60 seconds)
	binary.BigEndian.PutUint32(response[pos:pos+4], 60)
	pos += 4
	
	// Data length (4 bytes for IPv4)
	binary.BigEndian.PutUint16(response[pos:pos+2], 4)
	pos += 2
	
	// IP address
	ip := net.ParseIP(fakeIP).To4()
	if ip == nil {
		return nil
	}
	copy(response[pos:pos+4], ip)
	pos += 4
	
	return response[:pos]
}

// sendDNSResponse sends DNS response back through TUN
func (b *Bridge) sendDNSResponse(originalPacket []byte, srcIP net.IP, srcPort uint16, dnsResponse []byte) {
	// Build UDP packet
	udpLen := 8 + len(dnsResponse)
	ipLen := 20 + udpLen
	
	packet := make([]byte, ipLen)
	
	// IP header
	packet[0] = 0x45 // Version 4, header length 5
	packet[1] = 0x00 // DSCP, ECN
	binary.BigEndian.PutUint16(packet[2:4], uint16(ipLen))
	binary.BigEndian.PutUint16(packet[4:6], 0) // ID
	binary.BigEndian.PutUint16(packet[6:8], 0) // Flags, fragment offset
	packet[8] = 64 // TTL
	packet[9] = 17 // Protocol: UDP
	
	// Source IP (TUN router)
	routerIP := net.ParseIP(TunRouterIP).To4()
	copy(packet[12:16], routerIP)
	
	// Destination IP (original source)
	copy(packet[16:20], srcIP.To4())
	
	// Calculate IP checksum
	checksum := b.calculateChecksum(packet[:20])
	binary.BigEndian.PutUint16(packet[10:12], checksum)
	
	// UDP header
	binary.BigEndian.PutUint16(packet[20:22], 53) // Source port
	binary.BigEndian.PutUint16(packet[22:24], srcPort) // Destination port
	binary.BigEndian.PutUint16(packet[24:26], uint16(udpLen)) // Length
	binary.BigEndian.PutUint16(packet[26:28], 0) // Checksum (optional for IPv4)
	
	// DNS response
	copy(packet[28:], dnsResponse)
	
	// Write to TUN
	b.writeTUN(packet)
}

// sendICMPPortUnreachable sends ICMP destination unreachable (port unreachable).
func (b *Bridge) sendICMPPortUnreachable(originalPacket []byte, srcIP, dstIP net.IP) {
	if len(originalPacket) < 28 {
		return
	}

	quotedLen := 28
	if len(originalPacket) < quotedLen {
		quotedLen = len(originalPacket)
	}

	icmpLen := 8 + quotedLen
	ipLen := 20 + icmpLen
	packet := make([]byte, ipLen)

	// Outer IPv4 header.
	packet[0] = 0x45
	packet[1] = 0x00
	binary.BigEndian.PutUint16(packet[2:4], uint16(ipLen))
	binary.BigEndian.PutUint16(packet[4:6], 0)
	binary.BigEndian.PutUint16(packet[6:8], 0)
	packet[8] = 64
	packet[9] = 1 // ICMP
	copy(packet[12:16], dstIP.To4()) // reply source = original destination
	copy(packet[16:20], srcIP.To4()) // reply destination = original source
	binary.BigEndian.PutUint16(packet[10:12], b.calculateChecksum(packet[:20]))

	// ICMP header + quoted packet.
	icmpStart := 20
	packet[icmpStart] = 3     // Destination Unreachable
	packet[icmpStart+1] = 3   // Port Unreachable
	packet[icmpStart+2] = 0   // checksum hi
	packet[icmpStart+3] = 0   // checksum lo
	packet[icmpStart+4] = 0   // unused
	packet[icmpStart+5] = 0   // unused
	packet[icmpStart+6] = 0   // unused
	packet[icmpStart+7] = 0   // unused
	copy(packet[icmpStart+8:], originalPacket[:quotedLen])
	binary.BigEndian.PutUint16(packet[icmpStart+2:icmpStart+4], b.calculateChecksum(packet[icmpStart:]))

	b.writeTUN(packet)
}

// writeTUN writes a packet to TUN interface
func (b *Bridge) writeTUN(packet []byte) {
	tunFile := &tunFile{fd: b.tunFd}
	n, err := tunFile.Write(packet)
	if err != nil {
		log.Printf("[TUN-BRIDGE] Write error: %v", err)
		return
	}
	
	tunBytesDown.Add(int64(n))
}

// calculateChecksum calculates IP checksum
func (b *Bridge) calculateChecksum(data []byte) uint16 {
	sum := uint32(0)
	for i := 0; i < len(data)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for sum > 0xFFFF {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}

// tunFile wraps TUN file descriptor
type tunFile struct {
	fd int
}
