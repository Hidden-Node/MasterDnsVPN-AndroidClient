package tun

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync"
)

// relayBufPool provides 64KB-class scratch buffers for bulk TCP relay
// (io.CopyBuffer) and for the UDP-associate receive loop. A single pool
// serves both: 65535-byte slices are large enough for the 64KB UDP
// datagrams and double as roomy copy buffers for the relay path.
var relayBufPool = sync.Pool{
	New: func() any { return make([]byte, 65535) },
}

type FakeDNSProxy struct {
	RealSocksAddr string
	dnsMap        *DNSMapper
	listener      net.Listener
	ctx           context.Context
	cancel        context.CancelFunc
	wg            sync.WaitGroup
}

func NewFakeDNSProxy(realSocksAddr string, dnsMap *DNSMapper) *FakeDNSProxy {
	ctx, cancel := context.WithCancel(context.Background())
	return &FakeDNSProxy{
		RealSocksAddr: realSocksAddr,
		dnsMap:        dnsMap,
		ctx:           ctx,
		cancel:        cancel,
	}
}

func (p *FakeDNSProxy) Start() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	p.listener = l

	p.wg.Add(1)
	go p.acceptLoop()

	return l.Addr().String(), nil
}

func (p *FakeDNSProxy) Stop() {
	p.cancel()
	if p.listener != nil {
		p.listener.Close()
	}
}

func (p *FakeDNSProxy) acceptLoop() {
	defer p.wg.Done()
	for {
		conn, err := p.listener.Accept()
		if err != nil {
			if p.ctx.Err() != nil {
				return
			}
			continue
		}
		p.wg.Add(1)
		go func(c net.Conn) {
			defer p.wg.Done()
			p.handleConnection(c)
		}(conn)
	}
}

func (p *FakeDNSProxy) handleConnection(conn net.Conn) {
	defer conn.Close()

	// Read greeting (stack framing: 2-byte header, N methods).
	var hdrBuf [2]byte
	if _, err := io.ReadFull(conn, hdrBuf[:]); err != nil {
		return
	}
	nMethods := int(hdrBuf[1])
	if nMethods > 0 {
		var methodsBuf [256]byte
		var methods []byte
		if nMethods <= len(methodsBuf) {
			methods = methodsBuf[:nMethods]
		} else {
			methods = make([]byte, nMethods)
		}
		if _, err := io.ReadFull(conn, methods); err != nil {
			return
		}
	}

	// Send auth response: NO_AUTH
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}

	// Read request (stack framing: 4-byte header).
	var reqHdrBuf [4]byte
	if _, err := io.ReadFull(conn, reqHdrBuf[:]); err != nil {
		return
	}

	cmd := reqHdrBuf[1]
	atyp := reqHdrBuf[3]

	var targetAddr []byte
	var ipv4Buf [4]byte
	var ipv6Buf [16]byte
	var domLenBuf [1]byte
	if atyp == 1 { // IPV4
		if _, err := io.ReadFull(conn, ipv4Buf[:]); err != nil {
			return
		}
		targetAddr = ipv4Buf[:]
	} else if atyp == 3 { // DOMAIN
		if _, err := io.ReadFull(conn, domLenBuf[:]); err != nil {
			return
		}
		dom := make([]byte, domLenBuf[0])
		if _, err := io.ReadFull(conn, dom); err != nil {
			return
		}
		targetAddr = make([]byte, 1+len(dom))
		targetAddr[0] = domLenBuf[0]
		copy(targetAddr[1:], dom)
	} else if atyp == 4 { // IPV6
		if _, err := io.ReadFull(conn, ipv6Buf[:]); err != nil {
			return
		}
		targetAddr = ipv6Buf[:]
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(conn, portBuf[:]); err != nil {
		return
	}
	targetPort := portBuf[:]

	// FakeDNS Interception for TCP CONNECT
	if cmd == 1 && atyp == 1 {
		ipStr := net.IP(targetAddr).String()
		if hostname, ok := p.dnsMap.GetHostname(ipStr); ok {
			atyp = 3
			l := byte(len(hostname))
			targetAddr = append([]byte{l}, []byte(hostname)...)
		}
	}

	if cmd == 3 { // UDP ASSOCIATE
		p.handleUDPAssociate(conn, atyp, targetAddr, targetPort)
		return
	}

	// Dial Real SOCKS
	realConn, err := net.Dial("tcp", p.RealSocksAddr)
	if err != nil {
		conn.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer realConn.Close()

	if _, err := realConn.Write([]byte{5, 1, 0}); err != nil {
		return
	}
	var authBuf [2]byte
	if _, err := io.ReadFull(realConn, authBuf[:]); err != nil {
		return
	}

	req := []byte{5, 1, 0, atyp}
	req = append(req, targetAddr...)
	req = append(req, targetPort...)
	if _, err := realConn.Write(req); err != nil {
		return
	}

	var replyHdrBuf [4]byte
	if _, err := io.ReadFull(realConn, replyHdrBuf[:]); err != nil {
		return
	}
	if _, err := conn.Write(replyHdrBuf[:]); err != nil {
		return
	}

	var bndAddr []byte
	var bndIPv4Buf [4]byte
	var bndIPv6Buf [16]byte
	var bndDomLenBuf [1]byte
	if replyHdrBuf[3] == 1 {
		bndAddr = bndIPv4Buf[:]
	} else if replyHdrBuf[3] == 3 {
		if _, err := io.ReadFull(realConn, bndDomLenBuf[:]); err != nil {
			return
		}
		dom := make([]byte, bndDomLenBuf[0])
		if _, err := io.ReadFull(realConn, dom); err != nil {
			return
		}
		combined := make([]byte, 1+len(dom))
		combined[0] = bndDomLenBuf[0]
		copy(combined[1:], dom)
		bndAddr = combined
	} else if replyHdrBuf[3] == 4 {
		bndAddr = bndIPv6Buf[:]
	}
	if len(bndAddr) > 0 {
		// ATYP=3 already populated bndAddr above (length+domain); reading
		// again here would consume the port bytes and desync the framing.
		if replyHdrBuf[3] != 3 {
			if _, err := io.ReadFull(realConn, bndAddr); err != nil {
				return
			}
		}
		conn.Write(bndAddr)
	}

	var bndPortBuf [2]byte
	if _, err := io.ReadFull(realConn, bndPortBuf[:]); err != nil {
		return
	}
	conn.Write(bndPortBuf[:])

	relayUp := relayBufPool.Get().([]byte)
	relayDown := relayBufPool.Get().([]byte)
	go func() {
		defer relayBufPool.Put(relayUp)
		_, _ = io.CopyBuffer(realConn, conn, relayUp)
	}()
	defer relayBufPool.Put(relayDown)
	_, _ = io.CopyBuffer(conn, realConn, relayDown)
}

func (p *FakeDNSProxy) handleUDPAssociate(tcpConn net.Conn, atyp byte, targetAddr []byte, targetPort []byte) {
	realConn, err := net.Dial("tcp", p.RealSocksAddr)
	if err != nil {
		tcpConn.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer realConn.Close()

	if _, err := realConn.Write([]byte{5, 1, 0}); err != nil {
		return
	}
	var udpAuthBuf [2]byte
	if _, err := io.ReadFull(realConn, udpAuthBuf[:]); err != nil {
		return
	}

	req := []byte{5, 3, 0, atyp}
	req = append(req, targetAddr...)
	req = append(req, targetPort...)
	if _, err := realConn.Write(req); err != nil {
		return
	}

	var udpReplyHdrBuf [4]byte
	if _, err := io.ReadFull(realConn, udpReplyHdrBuf[:]); err != nil {
		return
	}

	var bndAddr []byte
	var udpBndIPv4Buf [4]byte
	var udpBndIPv6Buf [16]byte
	var udpBndDomLenBuf [1]byte
	if udpReplyHdrBuf[3] == 1 {
		bndAddr = udpBndIPv4Buf[:]
	} else if udpReplyHdrBuf[3] == 3 {
		io.ReadFull(realConn, udpBndDomLenBuf[:])
		dom := make([]byte, udpBndDomLenBuf[0])
		io.ReadFull(realConn, dom)
		combined := make([]byte, 1+len(dom))
		combined[0] = udpBndDomLenBuf[0]
		copy(combined[1:], dom)
		bndAddr = combined
	} else if udpReplyHdrBuf[3] == 4 {
		bndAddr = udpBndIPv6Buf[:]
	}
	if len(bndAddr) > 0 {
		io.ReadFull(realConn, bndAddr)
	}

	var udpBndPortBuf [2]byte
	io.ReadFull(realConn, udpBndPortBuf[:])
	bndPortBuf := udpBndPortBuf[:]

	var realUdpAddr *net.UDPAddr
	if udpReplyHdrBuf[3] == 1 {
		realUdpAddr = &net.UDPAddr{IP: net.IP(bndAddr), Port: int(binary.BigEndian.Uint16(bndPortBuf))}
	} else if udpReplyHdrBuf[3] == 4 {
		realUdpAddr = &net.UDPAddr{IP: net.IP(bndAddr), Port: int(binary.BigEndian.Uint16(bndPortBuf))}
	}
	if realUdpAddr != nil && realUdpAddr.IP.IsUnspecified() {
		host, _, _ := net.SplitHostPort(p.RealSocksAddr)
		realUdpAddr.IP = net.ParseIP(host)
	}

	localUdp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		tcpConn.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer localUdp.Close()

	localPort := localUdp.LocalAddr().(*net.UDPAddr).Port

	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1}
	var portScratch [2]byte
	binary.BigEndian.PutUint16(portScratch[:], uint16(localPort))
	reply = append(reply, portScratch[:]...)
	if _, err := tcpConn.Write(reply); err != nil {
		return
	}

	go func() {
		buf := relayBufPool.Get().([]byte)
		if len(buf) < 65535 {
			buf = make([]byte, 65535)
		} else {
			buf = buf[:65535]
		}
		defer relayBufPool.Put(buf)
		var tun2socksAddr *net.UDPAddr
		for {
			n, rAddr, err := localUdp.ReadFromUDP(buf)
			if err != nil {
				return
			}

			if realUdpAddr != nil && rAddr.IP.Equal(realUdpAddr.IP) && rAddr.Port == realUdpAddr.Port {
				if tun2socksAddr != nil {
					localUdp.WriteToUDP(buf[:n], tun2socksAddr)
				}
				continue
			}

			tun2socksAddr = rAddr

			if n < 4 || buf[2] != 0 {
				continue
			}
			frag := buf[2]
			if frag != 0 {
				continue
			}

			atyp := buf[3]
			var offset int
			var tPort uint16

			if atyp == 1 {
				offset = 10
				if n < offset {
					continue
				}
				tPort = binary.BigEndian.Uint16(buf[8:10])
			} else if atyp == 3 {
				l := int(buf[4])
				offset = 5 + l + 2
				if n < offset {
					continue
				}
				tPort = binary.BigEndian.Uint16(buf[5+l : offset])
			} else if atyp == 4 {
				offset = 22
				if n < offset {
					continue
				}
				tPort = binary.BigEndian.Uint16(buf[20:22])
			} else {
				continue
			}

			if tPort == 53 {
				// Plan 015: previously `append(buf[:offset], resp...)` reused
				// the receive buffer's backing array. On the next ReadFromUDP,
				// only `n` bytes overwrite buf, leaving stale response bytes
				// past `n` — parseDNSQuery then read corrupted offsets from
				// the previous iteration's reply. Build in a pooled scratch
				// that is NOT the receive buffer (aliasing guard).
				dnsQuery := make([]byte, n-offset)
				copy(dnsQuery, buf[offset:n])
				hostname := parseDNSQuery(dnsQuery)
				if hostname != "" {
					fakeIP := p.dnsMap.GetFakeIP(hostname)
					resp := buildDNSResponse(dnsQuery, fakeIP)
					if resp != nil {
						need := offset + len(resp)
						scratch := relayBufPool.Get().([]byte)
						var fullResp []byte
						if need <= len(scratch) {
							fullResp = scratch[:need]
						} else {
							fullResp = make([]byte, need)
							relayBufPool.Put(scratch)
							scratch = nil
						}
						copy(fullResp, buf[:offset])
						copy(fullResp[offset:], resp)
						localUdp.WriteToUDP(fullResp, rAddr)
						if scratch != nil {
							relayBufPool.Put(scratch)
						}
					}
				}
				continue
			}

			// ponytail: non-DNS UDP (QUIC, etc.) dropped, not forwarded.
			// Upstream SOCKS5 UDP_ASSOCIATE rejects non-53 targets
			// (socks_manager.go:665), forwarding would close the association
			// and break subsequent DNS queries. Dropping lets the browser's
			// QUIC probe time out fast and fall back to TCP (issue #32).
		}
	}()

	// Keep TCP connection open
	io.Copy(io.Discard, tcpConn)
}

func parseDNSQuery(query []byte) string {
	if len(query) < 12 {
		return ""
	}
	// First pass: validate bounds and measure the final hostname length
	// (label bytes + one dot per gap). Single allocation below.
	pos := 12
	total := 0
	labels := 0
	for pos < len(query) {
		length := int(query[pos])
		if length == 0 {
			break
		}
		if length > 63 || pos+1+length > len(query) {
			return ""
		}
		if labels > 0 {
			total++ // dot separator
		}
		total += length
		labels++
		pos += 1 + length
	}
	if labels == 0 {
		return ""
	}
	// Second pass: copy labels with dots into the single buffer.
	out := make([]byte, total)
	pos = 12
	off := 0
	for pos < len(query) {
		length := int(query[pos])
		if length == 0 {
			break
		}
		pos++
		if off > 0 {
			out[off] = '.'
			off++
		}
		copy(out[off:off+length], query[pos:pos+length])
		off += length
		pos += length
	}
	return string(out)
}

func buildDNSResponse(query []byte, fakeIP string) []byte {
	if len(query) < 12 {
		return nil
	}
	response := make([]byte, len(query)+16)
	copy(response, query)
	flags := binary.BigEndian.Uint16(response[2:4])
	flags |= 0x8400
	binary.BigEndian.PutUint16(response[2:4], flags)
	binary.BigEndian.PutUint16(response[6:8], 1)
	pos := len(query)
	response[pos] = 0xC0
	response[pos+1] = 0x0C
	pos += 2
	binary.BigEndian.PutUint16(response[pos:pos+2], 1)
	binary.BigEndian.PutUint16(response[pos+2:pos+4], 1)
	pos += 4
	binary.BigEndian.PutUint32(response[pos:pos+4], 60)
	pos += 4
	binary.BigEndian.PutUint16(response[pos:pos+2], 4)
	pos += 2
	ip := net.ParseIP(fakeIP).To4()
	if ip == nil {
		return nil
	}
	copy(response[pos:pos+4], ip)
	pos += 4
	return response[:pos]
}
