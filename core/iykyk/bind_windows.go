//go:build windows

package iykyk

import (
	"encoding/binary"
	"unsafe"

	"golang.org/x/sys/windows"
)

// IP_UNICAST_IF / IPV6_UNICAST_IF: send through this interface regardless of
// the routing table, which with the VPN on points everything at the TUN.
const (
	ipUnicastIf   = 31
	ipv6UnicastIf = 31
)

func bindToInterface(fd uintptr, index int) error {
	if index <= 0 {
		return nil
	}
	handle := windows.Handle(fd)
	// IPv4 wants the index in network byte order, IPv6 in host order.
	var be [4]byte
	binary.BigEndian.PutUint32(be[:], uint32(index))
	v4 := *(*uint32)(unsafe.Pointer(&be[0]))
	err4 := windows.SetsockoptInt(handle, windows.IPPROTO_IP, ipUnicastIf, int(v4))
	err6 := windows.SetsockoptInt(handle, windows.IPPROTO_IPV6, ipv6UnicastIf, index)
	if err4 != nil && err6 != nil {
		return err4
	}
	return nil
}
