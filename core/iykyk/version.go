package iykyk

import (
	C "github.com/sagernet/sing-box/constant"
)

// CoreVersion is set at build time by core/build.sh.
var CoreVersion = "dev"

// Versions returns a human-readable summary of the bundled cores.
func Versions() string {
	return "iykyk-core " + CoreVersion + "\nsing-box " + C.Version + "\nXray " + XrayVersion()
}
