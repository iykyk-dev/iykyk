package iykyk

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

// TestSocksServer runs a CONNECT through the bridge to a local echo server.
func TestSocksServer(t *testing.T) {
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()

	var dialed string
	port, _ := freeLoopbackPort()
	srv, err := listenSocks(port, "u", "p", func(ctx context.Context, network, address string) (net.Conn, error) {
		dialed = address
		var d net.Dialer
		return d.DialContext(ctx, network, echo.Addr().String())
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	c, err := net.DialTimeout("tcp", srv.listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	mustWrite := func(b []byte) {
		if _, err := c.Write(b); err != nil {
			t.Fatal(err)
		}
	}
	mustRead := func(n int) []byte {
		b := make([]byte, n)
		if _, err := io.ReadFull(c, b); err != nil {
			t.Fatal(err)
		}
		return b
	}

	mustWrite([]byte{5, 1, 2})
	if got := mustRead(2); got[1] != 2 {
		t.Fatalf("method = %v", got)
	}
	mustWrite([]byte{1, 1, 'u', 1, 'p'})
	if got := mustRead(2); got[1] != 0 {
		t.Fatalf("auth = %v", got)
	}
	domain := "example.org"
	req := append([]byte{5, 1, 0, 3, byte(len(domain))}, domain...)
	req = binary.BigEndian.AppendUint16(req, 443)
	mustWrite(req)
	if got := mustRead(10); got[1] != 0 {
		t.Fatalf("reply = %v", got)
	}
	if dialed != "example.org:443" {
		t.Fatalf("dialed %q", dialed)
	}
	mustWrite([]byte("ping"))
	if got := string(mustRead(4)); got != "ping" {
		t.Fatalf("echo = %q", got)
	}
}

func TestSocksServerRejectsWrongPassword(t *testing.T) {
	port, _ := freeLoopbackPort()
	srv, err := listenSocks(port, "u", "p", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	c, err := net.Dial("tcp", srv.listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_, _ = c.Write([]byte{5, 1, 2})
	b := make([]byte, 2)
	_, _ = io.ReadFull(c, b)
	_, _ = c.Write([]byte{1, 1, 'u', 1, 'x'})
	_, _ = io.ReadFull(c, b)
	if b[1] == 0 {
		t.Fatal("wrong password accepted")
	}
}

// echoSession returns every datagram it is sent, as if the destination echoed it.
type echoSession struct {
	ch   chan []byte
	done chan struct{}
	once sync.Once
}

func newEchoSession() *echoSession {
	return &echoSession{ch: make(chan []byte, 4), done: make(chan struct{})}
}

func (e *echoSession) Send(d []byte) error {
	select {
	case e.ch <- append([]byte(nil), d...):
		return nil
	case <-e.done:
		return io.EOF
	}
}

func (e *echoSession) Receive(buf []byte) (int, error) {
	select {
	case d := <-e.ch:
		return copy(buf, d), nil
	case <-e.done:
		return 0, io.EOF
	}
}

func (e *echoSession) Close() error {
	e.once.Do(func() { close(e.done) })
	return nil
}

func TestSocksUDPAssociate(t *testing.T) {
	port, _ := freeLoopbackPort()
	srv, err := listenSocksUDP(port, "u", "p", nil, func(ctx context.Context) (udpSession, error) {
		return newEchoSession(), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	c, err := net.Dial("tcp", srv.listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	read := func(n int) []byte {
		b := make([]byte, n)
		if _, err := io.ReadFull(c, b); err != nil {
			t.Fatal(err)
		}
		return b
	}
	_, _ = c.Write([]byte{5, 1, 2})
	read(2)
	_, _ = c.Write([]byte{1, 1, 'u', 1, 'p'})
	read(2)
	_, _ = c.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}) // UDP ASSOCIATE 0.0.0.0:0
	reply := read(10)
	if reply[1] != 0 {
		t.Fatalf("associate reply %v", reply)
	}
	relay := &net.UDPAddr{IP: net.IP(reply[4:8]), Port: int(binary.BigEndian.Uint16(reply[8:10]))}

	u, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	datagram := appendSocksUDP(nil, net.IPv4(8, 8, 8, 8), 53, []byte("query"))
	if _, err := u.Write(datagram); err != nil {
		t.Fatal(err)
	}
	_ = u.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 1500)
	n, err := u.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	host, dport, payload, err := parseSocksUDP(buf[:n])
	if err != nil || host != "8.8.8.8" || dport != 53 || string(payload) != "query" {
		t.Fatalf("echo %q %d %q %v", host, dport, payload, err)
	}
}
