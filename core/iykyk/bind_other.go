//go:build !windows

package iykyk

// Only the Windows app binds sockets itself; Android protects them through
// VpnService, and other desktops are not supported yet.
func bindToInterface(fd uintptr, index int) error { return nil }
