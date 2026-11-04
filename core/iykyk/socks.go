package iykyk

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

// socksDialFunc opens a connection through an in-process engine (Mieru, AmneziaWG).
type socksDialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// socksServer is a minimal SOCKS5 server on 127.0.0.1 that sing-box uses to
// hand TCP connections to an in-process engine. It requires username/password
// authentication (RFC 1929) so other apps on the device cannot use it.
// UDP ASSOCIATE is not supported: UDP apps fall back to TCP (QUIC → HTTPS)
// and DNS goes over DNS-over-HTTPS.
type socksServer struct {
	listener net.Listener
	user     string
	pass     string
	dial     socksDialFunc

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
}

func listenSocks(port int, user, pass string, dial socksDialFunc) (*socksServer, error) {
	listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	s := &socksServer{listener: listener, user: user, pass: pass, dial: dial, conns: map[net.Conn]struct{}{}}
	go s.serve()
	return s, nil
}

func (s *socksServer) serve() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		if !s.track(conn, true) {
			conn.Close()
			return
		}
		go func() {
			defer s.track(conn, false)
			defer conn.Close()
			s.handle(conn)
		}()
	}
}

func (s *socksServer) track(conn net.Conn, add bool) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if add {
		if s.closed {
			return false
		}
		s.conns[conn] = struct{}{}
	} else {
		delete(s.conns, conn)
	}
	return true
}

// Close stops listening and closes every open connection.
func (s *socksServer) Close() error {
	s.mu.Lock()
	s.closed = true
	conns := s.conns
	s.conns = map[net.Conn]struct{}{}
	s.mu.Unlock()
	err := s.listener.Close()
	for conn := range conns {
		conn.Close()
	}
	return err
}

const (
	socksReplySucceeded           = 0x00
	socksReplyGeneralFailure      = 0x01
	socksReplyHostUnreachable     = 0x04
	socksReplyCommandNotSupported = 0x07
	socksReplyAddressNotSupported = 0x08
)

func (s *socksServer) handle(conn net.Conn) {
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	r := bufio.NewReader(conn)

	// Greeting: VER NMETHODS METHODS...
	header := make([]byte, 2)
	if _, err := io.ReadFull(r, header); err != nil || header[0] != 5 {
		return
	}
	methods := make([]byte, header[1])
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	hasPassword := false
	for _, m := range methods {
		if m == 2 {
			hasPassword = true
		}
	}
	if !hasPassword {
		_, _ = conn.Write([]byte{5, 0xff})
		return
	}
	if _, err := conn.Write([]byte{5, 2}); err != nil {
		return
	}

	// RFC 1929: VER ULEN UNAME PLEN PASSWD
	user, pass, err := readSocksCredentials(r)
	if err != nil {
		return
	}
	if user != s.user || pass != s.pass {
		_, _ = conn.Write([]byte{1, 1})
		return
	}
	if _, err := conn.Write([]byte{1, 0}); err != nil {
		return
	}

	// Request: VER CMD RSV ATYP DST.ADDR DST.PORT
	request := make([]byte, 4)
	if _, err := io.ReadFull(r, request); err != nil || request[0] != 5 {
		return
	}
	host, err := readSocksAddress(r, request[3])
	if err != nil {
		writeSocksReply(conn, socksReplyAddressNotSupported)
		return
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(r, portBytes); err != nil {
		return
	}
	if request[1] != 1 { // only CONNECT
		writeSocksReply(conn, socksReplyCommandNotSupported)
		return
	}
	address := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(portBytes))))

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	remote, err := s.dial(ctx, "tcp", address)
	cancel()
	if err != nil {
		writeSocksReply(conn, socksReplyHostUnreachable)
		return
	}
	defer remote.Close()
	if !writeSocksReply(conn, socksReplySucceeded) {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	relay(conn, r, remote)
}

func readSocksCredentials(r *bufio.Reader) (string, string, error) {
	version, err := r.ReadByte()
	if err != nil || version != 1 {
		return "", "", errors.New("bad auth version")
	}
	readString := func() (string, error) {
		n, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		b := make([]byte, n)
		_, err = io.ReadFull(r, b)
		return string(b), err
	}
	user, err := readString()
	if err != nil {
		return "", "", err
	}
	pass, err := readString()
	return user, pass, err
}

func readSocksAddress(r *bufio.Reader, addressType byte) (string, error) {
	switch addressType {
	case 1:
		ip := make([]byte, 4)
		_, err := io.ReadFull(r, ip)
		return net.IP(ip).String(), err
	case 4:
		ip := make([]byte, 16)
		_, err := io.ReadFull(r, ip)
		return net.IP(ip).String(), err
	case 3:
		n, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		domain := make([]byte, n)
		_, err = io.ReadFull(r, domain)
		return string(domain), err
	}
	return "", errors.New("unknown address type")
}

func writeSocksReply(conn net.Conn, code byte) bool {
	_, err := conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err == nil
}

// relay copies data both ways; clientReader holds bytes already buffered from conn.
func relay(conn net.Conn, clientReader io.Reader, remote net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, clientReader)
		closeWrite(remote)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, remote)
		closeWrite(conn)
		done <- struct{}{}
	}()
	<-done
	<-done
}

func closeWrite(conn net.Conn) {
	if c, ok := conn.(interface{ CloseWrite() error }); ok {
		_ = c.CloseWrite()
	} else {
		_ = conn.Close()
	}
}
