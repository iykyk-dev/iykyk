package iykyk

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"time"

	awgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"
	awgdevice "github.com/amnezia-vpn/amneziawg-go/v3/device"
	awgtun "github.com/amnezia-vpn/amneziawg-go/v3/tun"
	wgtun "golang.zx2c4.com/wireguard/tun"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
)

// AmneziaWG runs entirely in user space: amneziawg-go talks to the server,
// and a gVisor network stack (from wireguard-go) turns the tunnel into
// ordinary TCP connections that the loopback SOCKS5 bridge hands out.

type amneziaEngine struct {
	device *awgdevice.Device
	socks  *socksServer
}

func (a *amneziaEngine) Close() error {
	err := a.socks.Close()
	a.device.Close()
	return err
}

func startAmnezia(s *server, port int, user, pass string) (io.Closer, error) {
	var local []netip.Addr
	for _, address := range s.Addresses {
		prefix, err := netip.ParsePrefix(address)
		if err != nil {
			return nil, fmt.Errorf("amneziawg: invalid address %q", address)
		}
		local = append(local, prefix.Addr())
	}
	mtu := s.MTU
	if mtu == 0 {
		mtu = 1280
	}
	// DNS inside the tunnel, used for names the apps connect to.
	dns := []netip.Addr{netip.MustParseAddr(remoteDNSServer)}
	netTun, tnet, err := wgnetstack.CreateNetTUN(local, dns, mtu)
	if err != nil {
		return nil, fmt.Errorf("amneziawg: %w", err)
	}

	endpoint, err := resolveEndpoint(s.Host, s.Port)
	if err != nil {
		netTun.Close()
		return nil, err
	}
	uapi, err := amneziaUAPI(s, endpoint)
	if err != nil {
		netTun.Close()
		return nil, err
	}

	device := awgdevice.NewDevice(
		newTunAdapter(netTun),
		&protectedBind{Bind: awgconn.NewDefaultBind()},
		awgdevice.NewLogger(awgdevice.LogLevelError, "amneziawg: "),
	)
	if err := device.IpcSet(uapi); err != nil {
		device.Close()
		return nil, fmt.Errorf("amneziawg: %w", err)
	}
	if err := device.Up(); err != nil {
		device.Close()
		return nil, fmt.Errorf("amneziawg: %w", err)
	}
	socks, err := listenSocks(port, user, pass, tnet.DialContext)
	if err != nil {
		device.Close()
		return nil, err
	}
	return &amneziaEngine{device: device, socks: socks}, nil
}

// resolveEndpoint turns the peer host into an IP: the WireGuard config format
// only accepts addresses. Runs before the tunnel exists, so DNS goes direct.
func resolveEndpoint(host string, port int) (string, error) {
	if ip := net.ParseIP(host); ip != nil {
		return net.JoinHostPort(host, strconv.Itoa(port)), nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	addrs, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil || len(addrs) == 0 {
		return "", fmt.Errorf("amneziawg: cannot resolve %s: %w", host, err)
	}
	best := addrs[0]
	for _, a := range addrs {
		if a.Unmap().Is4() {
			best = a.Unmap()
			break
		}
	}
	return net.JoinHostPort(best.String(), strconv.Itoa(port)), nil
}

// protectedBind excludes the tunnel's UDP sockets from the VPN every time they
// are (re)opened, including after network changes.
type protectedBind struct {
	awgconn.Bind
}

func (b *protectedBind) Open(port uint16) ([]awgconn.ReceiveFunc, uint16, error) {
	fns, actualPort, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	if peek, ok := b.Bind.(awgconn.PeekLookAtSocketFd); ok {
		if fd, err := peek.PeekLookAtSocketFd4(); err == nil {
			protectFD(fd)
		}
		if fd, err := peek.PeekLookAtSocketFd6(); err == nil {
			protectFD(fd)
		}
	}
	return fns, actualPort, nil
}

func protectFD(fd int) {
	if holder := currentProtector.Load(); holder != nil {
		holder.protector.Protect(int32(fd))
	}
}

// tunAdapter makes wireguard-go's netstack device usable by amneziawg-go:
// the two forks define identical tun interfaces except for the Event type.
type tunAdapter struct {
	wgtun.Device
	events chan awgtun.Event
}

func newTunAdapter(device wgtun.Device) *tunAdapter {
	a := &tunAdapter{Device: device, events: make(chan awgtun.Event, 4)}
	go func() {
		for event := range device.Events() {
			a.events <- awgtun.Event(event)
		}
		close(a.events)
	}()
	return a
}

func (a *tunAdapter) Events() <-chan awgtun.Event {
	return a.events
}
