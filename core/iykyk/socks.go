package iykyk

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

func bytesReader(b []byte) *bytes.Reader { return bytes.NewReader(b) }

// socksDialFunc opens a connection through an in-process engine (Mieru, AmneziaWG).
type socksDialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// udpSession carries SOCKS5 UDP datagrams (RFC 1928 section 7, header included)
// through an engine for one UDP ASSOCIATE.
type udpSession interface {
	Send(datagram []byte) error
	Receive(buf []byte) (int, error)
	Close() error
}

// socksUDPFunc opens a UDP session through an engine; nil means no UDP support.
type socksUDPFunc func(ctx context.Context) (udpSession, error)

// socksServer is a minimal SOCKS5 server on 127.0.0.1 that sing-box uses to
// hand TCP connections to an in-process engine. It requires username/password
// authentication (RFC 1929) so other apps on the device cannot use it.
// UDP ASSOCIATE is supported when the engine provides a socksUDPFunc.
type socksServer struct {
	listener net.Listener
	user     string
	pass     string
	dial     socksDialFunc
	udp      socksUDPFunc

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
}

func listenSocks(port int, user, pass string, dial socksDialFunc) (*socksServer, error) {
	return listenSocksUDP(port, user, pass, dial, nil)
}

func listenSocksUDP(port int, user, pass string, dial socksDialFunc, udp socksUDPFunc) (*socksServer, error) {
	listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	s := &socksServer{listener: listener, user: user, pass: pass, dial: dial, udp: udp, conns: map[net.Conn]struct{}{}}
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
	if request[1] == 3 && s.udp != nil { // UDP ASSOCIATE
		s.associate(conn, r)
		return
	}
	if request[1] != 1 { // CONNECT
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

// associate serves one UDP ASSOCIATE: datagrams from the client's UDP port go
// through the engine and back, until the control TCP connection closes.
func (s *socksServer) associate(conn net.Conn, r *bufio.Reader) {
	udpConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		writeSocksReply(conn, socksReplyGeneralFailure)
		return
	}
	defer udpConn.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	session, err := s.udp(ctx)
	if err != nil {
		writeSocksReply(conn, socksReplyGeneralFailure)
		return
	}
	defer session.Close()

	port := udpConn.LocalAddr().(*net.UDPAddr).Port
	reply := []byte{5, socksReplySucceeded, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)}
	if _, err := conn.Write(reply); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})

	var client atomic.Pointer[net.UDPAddr]
	go func() {
		buf := make([]byte, 65535)
		for {
			n, addr, err := udpConn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			// Only the local client may use this association; fragments are not supported.
			if !addr.IP.IsLoopback() || n < 4 || buf[2] != 0 {
				continue
			}
			client.Store(addr)
			if err := session.Send(buf[:n]); err != nil {
				return
			}
		}
	}()
	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := session.Receive(buf)
			if err != nil {
				return
			}
			if addr := client.Load(); addr != nil {
				_, _ = udpConn.WriteToUDP(buf[:n], addr)
			}
		}
	}()
	// The association lives as long as the TCP connection (RFC 1928).
	_, _ = io.Copy(io.Discard, r)
}

// parseSocksUDP splits a SOCKS5 UDP datagram into destination and payload.
func parseSocksUDP(datagram []byte) (host string, port int, payload []byte, err error) {
	if len(datagram) < 4 {
		return "", 0, nil, errors.New("short datagram")
	}
	r := bufio.NewReader(bytesReader(datagram[4:]))
	host, err = readSocksAddress(r, datagram[3])
	if err != nil {
		return "", 0, nil, err
	}
	portBytes := make([]byte, 2)
	if _, err = io.ReadFull(r, portBytes); err != nil {
		return "", 0, nil, err
	}
	payload, err = io.ReadAll(r)
	return host, int(binary.BigEndian.Uint16(portBytes)), payload, err
}

// appendSocksUDP builds a SOCKS5 UDP datagram from a source address and payload.
func appendSocksUDP(buf []byte, ip net.IP, port int, payload []byte) []byte {
	buf = append(buf[:0], 0, 0, 0)
	if v4 := ip.To4(); v4 != nil {
		buf = append(append(buf, 1), v4...)
	} else {
		buf = append(append(buf, 4), ip.To16()...)
	}
	buf = append(buf, byte(port>>8), byte(port))
	return append(buf, payload...)
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
