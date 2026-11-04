//go:build tools

// Packages that gomobile binds or needs but our code does not import directly.
// Listing them here keeps `go mod tidy` from dropping their dependencies.
package tools

import (
	_ "github.com/sagernet/gomobile"
	_ "github.com/sagernet/sing-box/experimental/libbox"
)
