package iykyk

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"

	mieruclient "github.com/enfein/mieru/v3/apis/client"
	mierucommon "github.com/enfein/mieru/v3/apis/common"
	mierumodel "github.com/enfein/mieru/v3/apis/model"
	"github.com/enfein/mieru/v3/pkg/appctl"
	"google.golang.org/protobuf/proto"
)

type mieruUDPSession struct {
	tunnel *mierucommon.PacketOverStreamTunnel
}

func (m *mieruUDPSession) Send(datagram []byte) error {
	_, err := m.tunnel.Write(datagram)
	return err
}

func (m *mieruUDPSession) Receive(buf []byte) (int, error) { return m.tunnel.Read(buf) }

func (m *mieruUDPSession) Close() error { return m.tunnel.Close() }

type mieruEngine struct {
	client mieruclient.Client
	socks  *socksServer
}

func (m *mieruEngine) Close() error {
	err := m.socks.Close()
	return errors.Join(err, m.client.Stop())
}

// startMieru runs a Mieru client from a mierus:// link behind a loopback SOCKS5 bridge.
func startMieru(link string, port int, user, pass string) (io.Closer, error) {
	profile, err := appctl.URLToClientProfile(link)
	if err != nil {
		return nil, fmt.Errorf("mieru: %w", err)
	}
	if profile.GetProfileName() == "" {
		profile.ProfileName = proto.String("default")
	}
	client := mieruclient.NewClient()
	if err := client.Store(&mieruclient.ClientConfig{
		Profile:      profile,
		Dialer:       protectedDialer(),
		PacketDialer: protectedPacketDialer{},
		Resolver:     &net.Resolver{},
	}); err != nil {
		return nil, fmt.Errorf("mieru: %w", err)
	}
	if err := client.Start(); err != nil {
		return nil, fmt.Errorf("mieru: %w", err)
	}
	// UDP: Mieru carries SOCKS5 UDP datagrams as they are, inside a stream
	// opened with the UDP associate command (as in Mieru's own API example).
	udp := func(ctx context.Context) (udpSession, error) {
		conn, err := client.DialContext(ctx, mierumodel.NetAddrSpec{
			AddrSpec: mierumodel.AddrSpec{IP: net.IPv4zero, Port: 0},
			Net:      "udp",
		})
		if err != nil {
			return nil, err
		}
		return &mieruUDPSession{tunnel: mierucommon.NewPacketOverStreamTunnel(conn)}, nil
	}
	socks, err := listenSocksUDP(port, user, pass, func(ctx context.Context, _ string, address string) (net.Conn, error) {
		host, portText, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		destPort, err := strconv.Atoi(portText)
		if err != nil {
			return nil, err
		}
		spec := mierumodel.AddrSpec{Port: destPort}
		if ip := net.ParseIP(host); ip != nil {
			spec.IP = ip
		} else {
			spec.FQDN = host
		}
		return client.DialContext(ctx, mierumodel.NetAddrSpec{AddrSpec: spec, Net: "tcp"})
	}, udp)
	if err != nil {
		_ = client.Stop()
		return nil, err
	}
	return &mieruEngine{client: client, socks: socks}, nil
}
