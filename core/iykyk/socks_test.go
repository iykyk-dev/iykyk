package iykyk

import (
	"context"
	"encoding/binary"
	"io"
	"net"
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
