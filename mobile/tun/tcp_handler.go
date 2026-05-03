package tun

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"time"
)

// TCPHandler manages TCP connections through SOCKS5
type TCPHandler struct {
	bridge    *Bridge
	mu        sync.RWMutex
	sessions  map[string]*TCPSession
	socksAddr string
}

// TCPSession represents a TCP connection
type TCPSession struct {
	id         string
	srcIP      net.IP
	srcPort    uint16
	dstIP      net.IP
	dstPort    uint16
	socksConn  net.Conn
	state      TCPState
	seqNum     uint32
	ackNum     uint32
	lastActive time.Time
	mu         sync.Mutex
}

type TCPState int

const (
	TCPStateClosed TCPState = iota
	TCPStateSynSent
	TCPStateSynReceived
	TCPStateEstablished
	TCPStateFinWait1
	TCPStateFinWait2
	TCPStateClosing
	TCPStateTimeWait
	TCPStateCloseWait
	TCPStateLastAck
)

func NewTCPHandler(bridge *Bridge, socksAddr string) *TCPHandler {
	h := &TCPHandler{
		bridge:    bridge,
		sessions:  make(map[string]*TCPSession),
		socksAddr: socksAddr,
	}
	
	// Start cleanup goroutine
	go h.cleanupLoop()
	
	return h
}

// HandleTCP handles a TCP packet
func (h *TCPHandler) HandleTCP(packet []byte, srcIP, dstIP net.IP) {
	if len(packet) < 40 { // IP header (20) + TCP header (20)
		return
	}
	
	// Parse TCP header
	srcPort := binary.BigEndian.Uint16(packet[20:22])
	dstPort := binary.BigEndian.Uint16(packet[22:24])
	seqNum := binary.BigEndian.Uint32(packet[24:28])
	ackNum := binary.BigEndian.Uint32(packet[28:32])
	
	tcpHeaderLen := int((packet[32] >> 4) * 4)
	if tcpHeaderLen < 20 || 20+tcpHeaderLen > len(packet) {
		return
	}
	
	flags := packet[33]
	flagFIN := (flags & 0x01) != 0
	flagSYN := (flags & 0x02) != 0
	flagRST := (flags & 0x04) != 0
	flagACK := (flags & 0x10) != 0
	
	// Get payload
	payload := packet[20+tcpHeaderLen:]
	
	// Session ID
	sessionID := fmt.Sprintf("%s:%d->%s:%d", srcIP, srcPort, dstIP, dstPort)
	
	// Handle based on flags
	if flagRST {
		h.handleRST(sessionID)
		return
	}
	
	if flagSYN && !flagACK {
		h.handleSYN(sessionID, srcIP, srcPort, dstIP, dstPort, seqNum)
		return
	}
	
	if flagFIN {
		h.handleFIN(sessionID, seqNum, ackNum)
		return
	}
	
	if flagACK && len(payload) > 0 {
		h.handleData(sessionID, payload, seqNum, ackNum)
		return
	}
	
	if flagACK {
		h.handleACK(sessionID, ackNum)
		return
	}
}

// handleSYN handles SYN packet (new connection)
func (h *TCPHandler) handleSYN(sessionID string, srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16, seqNum uint32) {
	h.mu.Lock()
	defer h.mu.Unlock()
	
	// Check if session already exists
	if _, exists := h.sessions[sessionID]; exists {
		return
	}
	
	// Check if destination is fake IP
	dstIPStr := dstIP.String()
	hostname, isFake := h.bridge.dnsMap.GetHostname(dstIPStr)
	
	var targetAddr string
	if isFake {
		targetAddr = fmt.Sprintf("%s:%d", hostname, dstPort)
		log.Printf("[TCP] SYN to fake IP: %s -> %s (%s)", sessionID, hostname, dstIPStr)
	} else {
		targetAddr = fmt.Sprintf("%s:%d", dstIPStr, dstPort)
		log.Printf("[TCP] SYN to real IP: %s", sessionID)
	}
	
	// Connect to SOCKS5
	socksConn, err := h.connectSOCKS5(targetAddr)
	if err != nil {
		log.Printf("[TCP] SOCKS5 connect failed for %s: %v", sessionID, err)
		h.sendRST(srcIP, srcPort, dstIP, dstPort, seqNum+1, 0)
		return
	}
	
	// Create session
	session := &TCPSession{
		id:         sessionID,
		srcIP:      srcIP,
		srcPort:    srcPort,
		dstIP:      dstIP,
		dstPort:    dstPort,
		socksConn:  socksConn,
		state:      TCPStateEstablished,
		seqNum:     1000, // Initial server seq
		ackNum:     seqNum + 1,
		lastActive: time.Now(),
	}
	
	h.sessions[sessionID] = session
	
	// Send SYN-ACK
	h.sendSYNACK(session)
	
	// Start reading from SOCKS5
	go h.readFromSOCKS5(session)
}

// connectSOCKS5 connects to SOCKS5 proxy
func (h *TCPHandler) connectSOCKS5(targetAddr string) (net.Conn, error) {
	// Connect to SOCKS5 proxy
	conn, err := net.DialTimeout("tcp", h.socksAddr, 5*time.Second)
	if err != nil {
		return nil, fmt.Errorf("dial SOCKS5: %v", err)
	}
	
	// Protect socket if function is available
	if h.bridge.protectFn != nil {
		if tcpConn, ok := conn.(*net.TCPConn); ok {
			file, err := tcpConn.File()
			if err == nil {
				h.bridge.protectFn(int(file.Fd()))
				file.Close()
			}
		}
	}
	
	// SOCKS5 handshake
	// Send greeting: VER(5) NMETHODS(1) METHODS(0=no auth)
	if _, err := conn.Write([]byte{5, 1, 0}); err != nil {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 greeting: %v", err)
	}
	
	// Read response: VER(5) METHOD(0)
	resp := make([]byte, 2)
	if _, err := io.ReadFull(conn, resp); err != nil {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 greeting response: %v", err)
	}
	
	if resp[0] != 5 || resp[1] != 0 {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 auth failed: %v", resp)
	}
	
	// Parse target address
	host, portStr, err := net.SplitHostPort(targetAddr)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("invalid target: %v", err)
	}
	
	port := uint16(0)
	fmt.Sscanf(portStr, "%d", &port)
	
	// Build CONNECT request
	// VER(5) CMD(1=CONNECT) RSV(0) ATYP(3=domain)
	hostBytes := []byte(host)
	request := make([]byte, 7+len(hostBytes))
	request[0] = 5 // VER
	request[1] = 1 // CMD: CONNECT
	request[2] = 0 // RSV
	request[3] = 3 // ATYP: domain name
	request[4] = byte(len(hostBytes))
	copy(request[5:], hostBytes)
	binary.BigEndian.PutUint16(request[5+len(hostBytes):], port)
	
	// Send CONNECT request
	if _, err := conn.Write(request); err != nil {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 connect: %v", err)
	}
	
	// Read response
	respHeader := make([]byte, 4)
	if _, err := io.ReadFull(conn, respHeader); err != nil {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 connect response: %v", err)
	}
	
	if respHeader[1] != 0 {
		conn.Close()
		return nil, fmt.Errorf("SOCKS5 connect failed: code %d", respHeader[1])
	}
	
	// Read bound address (we don't need it, just consume it)
	atyp := respHeader[3]
	switch atyp {
	case 1: // IPv4
		io.ReadFull(conn, make([]byte, 6)) // 4 bytes IP + 2 bytes port
	case 3: // Domain
		lenBuf := make([]byte, 1)
		io.ReadFull(conn, lenBuf)
		io.ReadFull(conn, make([]byte, int(lenBuf[0])+2)) // domain + port
	case 4: // IPv6
		io.ReadFull(conn, make([]byte, 18)) // 16 bytes IP + 2 bytes port
	}
	
	return conn, nil
}

// sendSYNACK sends SYN-ACK packet
func (h *TCPHandler) sendSYNACK(session *TCPSession) {
	packet := h.buildTCPPacket(
		session.dstIP, session.dstPort,
		session.srcIP, session.srcPort,
		session.seqNum, session.ackNum,
		0x12, // SYN + ACK
		nil,
	)
	
	h.bridge.writeTUN(packet)
	session.seqNum++
}

// handleData handles data packet
func (h *TCPHandler) handleData(sessionID string, data []byte, seqNum, ackNum uint32) {
	h.mu.RLock()
	session, exists := h.sessions[sessionID]
	h.mu.RUnlock()
	
	if !exists {
		return
	}
	
	session.mu.Lock()
	session.lastActive = time.Now()
	session.mu.Unlock()
	
	// Write to SOCKS5
	if session.socksConn != nil {
		_, err := session.socksConn.Write(data)
		if err != nil {
			log.Printf("[TCP] Write to SOCKS5 failed for %s: %v", sessionID, err)
			h.closeSession(sessionID)
			return
		}
		
		tunBytesUp.Add(int64(len(data)))
	}
	
	// Send ACK
	session.mu.Lock()
	session.ackNum = seqNum + uint32(len(data))
	session.mu.Unlock()
	
	h.sendACK(session)
}

// readFromSOCKS5 reads data from SOCKS5 and sends to TUN
func (h *TCPHandler) readFromSOCKS5(session *TCPSession) {
	defer h.closeSession(session.id)
	
	buf := make([]byte, relayBufSize)
	for {
		n, err := session.socksConn.Read(buf)
		if err != nil {
			if err != io.EOF {
				log.Printf("[TCP] Read from SOCKS5 failed for %s: %v", session.id, err)
			}
			return
		}
		
		if n == 0 {
			continue
		}
		
		session.mu.Lock()
		session.lastActive = time.Now()
		session.mu.Unlock()
		
		// Send data to TUN
		h.sendData(session, buf[:n])
		
		tunBytesDown.Add(int64(n))
	}
}

// sendData sends data packet to TUN
func (h *TCPHandler) sendData(session *TCPSession, data []byte) {
	session.mu.Lock()
	seqNum := session.seqNum
	ackNum := session.ackNum
	session.seqNum += uint32(len(data))
	session.mu.Unlock()
	
	packet := h.buildTCPPacket(
		session.dstIP, session.dstPort,
		session.srcIP, session.srcPort,
		seqNum, ackNum,
		0x18, // PSH + ACK
		data,
	)
	
	h.bridge.writeTUN(packet)
}

// sendACK sends ACK packet
func (h *TCPHandler) sendACK(session *TCPSession) {
	session.mu.Lock()
	seqNum := session.seqNum
	ackNum := session.ackNum
	session.mu.Unlock()
	
	packet := h.buildTCPPacket(
		session.dstIP, session.dstPort,
		session.srcIP, session.srcPort,
		seqNum, ackNum,
		0x10, // ACK
		nil,
	)
	
	h.bridge.writeTUN(packet)
}

// handleFIN handles FIN packet
func (h *TCPHandler) handleFIN(sessionID string, seqNum, ackNum uint32) {
	h.mu.RLock()
	session, exists := h.sessions[sessionID]
	h.mu.RUnlock()
	
	if !exists {
		return
	}
	
	// Send FIN-ACK
	session.mu.Lock()
	session.ackNum = seqNum + 1
	session.mu.Unlock()
	
	h.sendFINACK(session)
	
	// Close session
	h.closeSession(sessionID)
}

// sendFINACK sends FIN-ACK packet
func (h *TCPHandler) sendFINACK(session *TCPSession) {
	session.mu.Lock()
	seqNum := session.seqNum
	ackNum := session.ackNum
	session.seqNum++
	session.mu.Unlock()
	
	packet := h.buildTCPPacket(
		session.dstIP, session.dstPort,
		session.srcIP, session.srcPort,
		seqNum, ackNum,
		0x11, // FIN + ACK
		nil,
	)
	
	h.bridge.writeTUN(packet)
}

// handleRST handles RST packet
func (h *TCPHandler) handleRST(sessionID string) {
	h.closeSession(sessionID)
}

// handleACK handles ACK packet
func (h *TCPHandler) handleACK(sessionID string, ackNum uint32) {
	h.mu.RLock()
	session, exists := h.sessions[sessionID]
	h.mu.RUnlock()
	
	if exists {
		session.mu.Lock()
		session.lastActive = time.Now()
		session.mu.Unlock()
	}
}

// sendRST sends RST packet
func (h *TCPHandler) sendRST(srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16, seqNum, ackNum uint32) {
	packet := h.buildTCPPacket(
		dstIP, dstPort,
		srcIP, srcPort,
		seqNum, ackNum,
		0x04, // RST
		nil,
	)
	
	h.bridge.writeTUN(packet)
}

// buildTCPPacket builds a TCP packet
func (h *TCPHandler) buildTCPPacket(srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16, seqNum, ackNum uint32, flags byte, data []byte) []byte {
	tcpHeaderLen := 20
	ipHeaderLen := 20
	totalLen := ipHeaderLen + tcpHeaderLen + len(data)
	
	packet := make([]byte, totalLen)
	
	// IP header
	packet[0] = 0x45 // Version 4, header length 5
	packet[1] = 0x00 // DSCP, ECN
	binary.BigEndian.PutUint16(packet[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(packet[4:6], 0) // ID
	binary.BigEndian.PutUint16(packet[6:8], 0x4000) // Flags: DF
	packet[8] = 64 // TTL
	packet[9] = 6  // Protocol: TCP
	
	// Source and destination IPs
	copy(packet[12:16], srcIP.To4())
	copy(packet[16:20], dstIP.To4())
	
	// Calculate IP checksum
	ipChecksum := h.bridge.calculateChecksum(packet[:20])
	binary.BigEndian.PutUint16(packet[10:12], ipChecksum)
	
	// TCP header
	binary.BigEndian.PutUint16(packet[20:22], srcPort)
	binary.BigEndian.PutUint16(packet[22:24], dstPort)
	binary.BigEndian.PutUint32(packet[24:28], seqNum)
	binary.BigEndian.PutUint32(packet[28:32], ackNum)
	packet[32] = 0x50 // Data offset: 5 (20 bytes)
	packet[33] = flags
	binary.BigEndian.PutUint16(packet[34:36], 65535) // Window size
	binary.BigEndian.PutUint16(packet[36:38], 0)     // Checksum (will calculate)
	binary.BigEndian.PutUint16(packet[38:40], 0)     // Urgent pointer
	
	// Copy data
	if len(data) > 0 {
		copy(packet[40:], data)
	}
	
	// Calculate TCP checksum
	tcpChecksum := h.calculateTCPChecksum(packet[12:16], packet[16:20], packet[20:])
	binary.BigEndian.PutUint16(packet[36:38], tcpChecksum)
	
	return packet
}

// calculateTCPChecksum calculates TCP checksum
func (h *TCPHandler) calculateTCPChecksum(srcIP, dstIP, tcpSegment []byte) uint16 {
	// Pseudo header
	pseudoHeader := make([]byte, 12)
	copy(pseudoHeader[0:4], srcIP)
	copy(pseudoHeader[4:8], dstIP)
	pseudoHeader[8] = 0
	pseudoHeader[9] = 6 // TCP protocol
	binary.BigEndian.PutUint16(pseudoHeader[10:12], uint16(len(tcpSegment)))
	
	// Combine pseudo header and TCP segment
	data := append(pseudoHeader, tcpSegment...)
	
	return h.bridge.calculateChecksum(data)
}

// closeSession closes a TCP session
func (h *TCPHandler) closeSession(sessionID string) {
	h.mu.Lock()
	session, exists := h.sessions[sessionID]
	if exists {
		delete(h.sessions, sessionID)
	}
	h.mu.Unlock()
	
	if exists && session.socksConn != nil {
		session.socksConn.Close()
		log.Printf("[TCP] Session closed: %s", sessionID)
	}
}

// cleanupLoop periodically cleans up idle sessions
func (h *TCPHandler) cleanupLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	
	for range ticker.C {
		now := time.Now()
		h.mu.Lock()
		for id, session := range h.sessions {
			session.mu.Lock()
			idle := now.Sub(session.lastActive)
			session.mu.Unlock()
			
			if idle > 5*time.Minute {
				log.Printf("[TCP] Cleaning up idle session: %s (idle: %v)", id, idle)
				if session.socksConn != nil {
					session.socksConn.Close()
				}
				delete(h.sessions, id)
			}
		}
		h.mu.Unlock()
	}
}

// Close closes all sessions
func (h *TCPHandler) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	
	for id, session := range h.sessions {
		if session.socksConn != nil {
			session.socksConn.Close()
		}
		delete(h.sessions, id)
	}
}
