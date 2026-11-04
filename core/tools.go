//go:build tools

// Keeps the gomobile binding support package in go.mod; gomobile bind needs it.
package tools

import (
	_ "github.com/sagernet/gomobile"
)
