package iykyk

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"syscall"
)

// SocketProtector excludes a socket from the VPN. Implemented by the app.
type SocketProtector interface {
	Protect(fd int32) bool
}

type protectorHolder struct {
	protector SocketProtector
}

var currentProtector atomic.Pointer[protectorHolder]

func protectSocket(_, _ string, conn syscall.RawConn) error {
	holder := currentProtector.Load()
	if holder == nil {
		return nil
	}
	var protectErr error
	err := conn.Control(func(fd uintptr) {
		if !holder.protector.Protect(int32(fd)) {
			protectErr = errors.New("iykyk: failed to protect socket")
		}
	})
	if err != nil {
		return err
	}
	return protectErr
}

// SetSocketProtector sets the protector used for every socket Xray dials.
// Pass nil to clear it.
func SetSocketProtector(protector SocketProtector) {
	if protector == nil {
		currentProtector.Store(nil)
		return
	}
	currentProtector.Store(&protectorHolder{protector: protector})
}

// protectedDialer dials with sockets excluded from the VPN.
func protectedDialer() *net.Dialer {
	return &net.Dialer{Control: protectSocket}
}

type protectedPacketDialer struct{}

func (protectedPacketDialer) ListenPacket(ctx context.Context, network, laddr, _ string) (net.PacketConn, error) {
	lc := net.ListenConfig{Control: protectSocket}
	return lc.ListenPacket(ctx, network, laddr)
}
