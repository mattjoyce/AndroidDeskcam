package main

import (
	_ "embed"
	"net/http"

	"deskcam/panel"
)

// The console's page. Kept as a file rather than a quoted string so it stays editable and
// so nothing has to be escaped twice.
//
//go:embed page.html
var consolePage string

// The camera page is compiled from the APK asset, never copied or loaded from disk.
func (s *consoleState) handleCamera(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "frame-ancestors 'self'")
	w.Write([]byte(panel.HTML))
}
