package client

import (
	"errors"
	"net"
	"testing"
)

type listenerTempError struct {
	timeout   bool
	temporary bool
}

func (e listenerTempError) Error() string   { return "listener temp error" }
func (e listenerTempError) Timeout() bool   { return e.timeout }
func (e listenerTempError) Temporary() bool { return e.temporary }

func TestListenerShouldRetryAccept(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want bool
	}{
		{name: "nil", err: nil, want: false},
		{name: "closed", err: net.ErrClosed, want: false},
		{name: "timeout", err: listenerTempError{timeout: true}, want: true},
		{name: "temporary", err: listenerTempError{temporary: true}, want: true},
		{name: "permanent", err: errors.New("permission denied"), want: false},
	}

	for _, tt := range tests {
		if got := listenerShouldRetryAccept(tt.err); got != tt.want {
			t.Fatalf("%s: got %v want %v", tt.name, got, tt.want)
		}
	}
}
