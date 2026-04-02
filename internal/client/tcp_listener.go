// ==============================================================================
// MasterDnsVPN
// Author: MasterkinG32
// Github: https://github.com/masterking32
// Year: 2026
// ==============================================================================
package client

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"
)

type TCPListener struct {
	client       *Client
	protocolType string
	listener     net.Listener
	stopChan     chan struct{}
	stopOnce     sync.Once
}

func NewTCPListener(c *Client, protocolType string) *TCPListener {
	return &TCPListener{
		client:       c,
		protocolType: protocolType,
		stopChan:     make(chan struct{}),
	}
}

func (l *TCPListener) Start(ctx context.Context, ip string, port int) error {
	addr := fmt.Sprintf("%s:%d", ip, port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	l.listener = listener

	l.client.log.Infof("🚀 <green>%s Proxy server is listening on <cyan>%s</cyan></green>", l.protocolType, addr)

	go func(activeListener net.Listener) {
		for {
			conn, err := activeListener.Accept()
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					return
				}
				select {
				case <-l.stopChan:
					return
				case <-ctx.Done():
					return
				default:
					if listenerShouldRetryAccept(err) {
						time.Sleep(100 * time.Millisecond)
						continue
					}
					if l.client != nil && l.client.log != nil {
						l.client.log.Warnf("⚠️ <yellow>%s listener stopped after accept error: %v</yellow>", l.protocolType, err)
					}
					return
				}
			}
			go l.handleConnection(ctx, conn, l.protocolType)
		}
	}(listener)

	return nil
}

func (l *TCPListener) Stop() {
	if l == nil {
		return
	}
	l.stopOnce.Do(func() {
		close(l.stopChan)
		if l.listener != nil {
			_ = l.listener.Close()
			l.listener = nil
		}
	})
}

func listenerShouldRetryAccept(err error) bool {
	if err == nil {
		return false
	}
	var netErr net.Error
	if errors.As(err, &netErr) {
		if netErr.Timeout() {
			return true
		}
		type temporary interface {
			Temporary() bool
		}
		if tempErr, ok := any(netErr).(temporary); ok && tempErr.Temporary() {
			return true
		}
	}
	return false
}

// handleConnection manages the local proxy/TCP forwarding handshake and requests.
func (l *TCPListener) handleConnection(ctx context.Context, conn net.Conn, protocolType string) {
	if protocolType == "SOCKS5" {
		l.client.HandleSOCKS5(ctx, conn)
		return
	}

	l.client.HandleTCPConnect(ctx, conn)
}
