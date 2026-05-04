package tun

import "syscall"

// Read reads from TUN file descriptor
func (t *tunFile) Read(p []byte) (n int, err error) {
	n, err = syscall.Read(t.fd, p)
	return
}

// Write writes to TUN file descriptor
func (t *tunFile) Write(p []byte) (n int, err error) {
	n, err = syscall.Write(t.fd, p)
	return
}

// Protect protects a socket file descriptor from VPN routing
func Protect(fd int, protectFn func(int) bool) bool {
	if protectFn != nil {
		return protectFn(fd)
	}
	return false
}

// SetNonblock sets a file descriptor to non-blocking mode
func SetNonblock(fd int, nonblocking bool) error {
	return syscall.SetNonblock(fd, nonblocking)
}

// GetSockOpt gets socket option
func GetSockOpt(fd, level, opt int) (int, error) {
	return syscall.GetsockoptInt(fd, level, opt)
}
