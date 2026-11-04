package iykyk

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// Hardened HTTPS for fetching subscriptions on filtered networks: the server
// name is resolved over DNS-over-HTTPS (past ISP DNS spoofing) and the TLS
// ClientHello is split into small TCP segments (past DPI that blocks by SNI).

// dohResolvers are reached by IP, so resolving them needs no DNS.
var dohResolvers = []string{
	"https://1.1.1.1/dns-query?type=A&name=", // Cloudflare JSON API
	"https://8.8.8.8/resolve?type=A&name=",   // Google JSON API
}

var dohClient = &http.Client{
	Timeout: 5 * time.Second,
	Transport: &http.Transport{
		DialContext:       protectedDialer().DialContext,
		ForceAttemptHTTP2: true,
	},
}

func dohLookup(ctx context.Context, host string) ([]string, error) {
	var lastErr error
	for _, resolver := range dohResolvers {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, resolver+url.QueryEscape(host), nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept", "application/dns-json")
		resp, err := dohClient.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		var answer struct {
			Answer []struct {
				Type int    `json:"type"`
				Data string `json:"data"`
			} `json:"Answer"`
		}
		err = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&answer)
		resp.Body.Close()
		if err != nil {
			lastErr = err
			continue
		}
		var ips []string
		for _, a := range answer.Answer {
			if a.Type == 1 && net.ParseIP(a.Data) != nil {
				ips = append(ips, a.Data)
			}
		}
		if len(ips) > 0 {
			return ips, nil
		}
		lastErr = fmt.Errorf("no A records for %s", host)
	}
	if lastErr == nil {
		lastErr = errors.New("DoH lookup failed")
	}
	return nil, lastErr
}

// fragmentConn splits the first write (the TLS ClientHello) into small pieces
// sent with short pauses; later writes pass through.
type fragmentConn struct {
	net.Conn
	once sync.Once
}

func (c *fragmentConn) Write(b []byte) (int, error) {
	fragmented := false
	var n int
	var err error
	c.once.Do(func() {
		fragmented = true
		for len(b) > 0 && err == nil {
			size := 20 + rand.IntN(40)
			if size > len(b) {
				size = len(b)
			}
			var written int
			written, err = c.Conn.Write(b[:size])
			n += written
			b = b[size:]
			if len(b) > 0 {
				time.Sleep(time.Duration(5+rand.IntN(10)) * time.Millisecond)
			}
		}
	})
	if fragmented {
		return n, err
	}
	return c.Conn.Write(b)
}

// hardenedClient resolves names over DoH and fragments the TLS ClientHello.
// redirect is the redirect policy (the provider lock).
func hardenedClient(redirect func(*http.Request, []*http.Request) error) *http.Client {
	dial := func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		ips := []string{host}
		if net.ParseIP(host) == nil {
			if ips, err = dohLookup(ctx, host); err != nil {
				return nil, err
			}
		}
		var lastErr error
		for _, ip := range ips {
			conn, err := protectedDialer().DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
			if err != nil {
				lastErr = err
				continue
			}
			if tcp, ok := conn.(*net.TCPConn); ok {
				_ = tcp.SetNoDelay(true) // keep the pieces as separate segments
			}
			return &fragmentConn{Conn: conn}, nil
		}
		return nil, lastErr
	}
	return &http.Client{
		Timeout:       20 * time.Second,
		CheckRedirect: redirect,
		Transport: &http.Transport{
			DialContext:         dial,
			TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
			TLSHandshakeTimeout: 15 * time.Second,
			ForceAttemptHTTP2:   true,
		},
	}
}
