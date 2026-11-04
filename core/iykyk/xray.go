// Package iykyk is the app-specific part of the IYKYK core, bound for Android and iOS
// with gomobile next to sing-box's libbox.
//
// sing-box (libbox) owns the TUN device, DNS and routing. Protocols where Xray is the
// reference implementation (VLESS with REALITY / XTLS-Vision / XHTTP, VMess, Trojan,
// Shadowsocks) run in an in-process Xray instance that listens on a loopback SOCKS
// inbound; sing-box forwards those connections to it.
//
// Sockets Xray opens to proxy servers must bypass the VPN, otherwise they would loop
// back into the TUN. The app passes a SocketProtector (VpnService.protect on Android)
// that is applied to every socket Xray dials.
package iykyk

import (
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

// SocketProtector excludes a socket from the VPN. Implemented by the app.
type SocketProtector interface {
	Protect(fd int32) bool
}

type protectorHolder struct {
	protector SocketProtector
}

var (
	currentProtector atomic.Pointer[protectorHolder]

	xrayMu       sync.Mutex
	xrayInstance *core.Instance
)

func init() {
	// Registered once; it is a no-op until the app sets a protector.
	_ = internet.RegisterDialerController(protectSocket)
}

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

// XrayVersion returns the version of the bundled Xray core.
func XrayVersion() string {
	return core.Version()
}

// CheckXrayConfig validates an Xray JSON config without starting it.
func CheckXrayConfig(configJSON string) error {
	_, err := core.LoadConfig("json", strings.NewReader(configJSON))
	return err
}

// StartXray starts Xray with a JSON config, replacing any running instance.
func StartXray(configJSON string) error {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	if xrayInstance != nil {
		_ = xrayInstance.Close()
		xrayInstance = nil
	}
	instance, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		return err
	}
	xrayInstance = instance
	return nil
}

// StopXray stops the running Xray instance, if any.
func StopXray() error {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	if xrayInstance == nil {
		return nil
	}
	err := xrayInstance.Close()
	xrayInstance = nil
	return err
}

// IsXrayRunning reports whether an Xray instance is running.
func IsXrayRunning() bool {
	xrayMu.Lock()
	defer xrayMu.Unlock()
	return xrayInstance != nil
}
