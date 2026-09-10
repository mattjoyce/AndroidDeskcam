package main

import (
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// handlePair turns a scanned code into a paired phone.
//
// The address comes from where the request came FROM, never from what it says about
// itself. The shell version took an `addr` from the query, and read it before it checked
// the code, so two requests could point the console at another machine, which then
// received the access key through /api/cam. Only the port is taken from the caller,
// because only the app knows which port it bound. Card 29.
func (s *consoleState) handlePair(w http.ResponseWriter, r *http.Request) {
	nonce := strings.TrimPrefix(r.URL.Path, "/p/")

	// One code, one pairing. Spending it here also closes the window in which two
	// requests could race on the same code.
	if !s.spendNonce(nonce) {
		phonePage(w, http.StatusGone, "Code expired",
			"Load the console page again to get a new code.", false)
		return
	}

	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !ipOK(host) {
		s.setError(fmt.Sprintf("the pairing request came from %q, which is not an address", host))
		phonePage(w, http.StatusBadRequest, "Cannot pair",
			"This workstation could not read the address you came from.", false)
		return
	}

	port := 8080
	if v := r.URL.Query().Get("port"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 1 && n <= 65535 {
			port = n
		}
	}

	_, token, _ := s.snapshot()
	status := probePhone(host, port, token, 4*time.Second)
	if status == nil {
		s.setError(fmt.Sprintf("Saw the phone at %s, but could not reach "+
			"http://%s:%d/api/status. Is DeskCam running?", host, host, port))
		phonePage(w, http.StatusOK, "Almost",
			fmt.Sprintf("This workstation saw you at %s, but the DeskCam service did not "+
				"answer on port %d. Open DeskCam and press Start, then scan the code "+
				"again.", host, port), false)
		return
	}

	target := "http://" + net.JoinHostPort(host, strconv.Itoa(port))
	s.mu.Lock()
	s.phone = target
	s.lastError = ""
	s.lastPair = map[string]any{
		"at":     time.Now().Format("2006-01-02 15:04:05"),
		"url":    target,
		"camera": str(sub(status, "settings"), "camera"),
		"state":  str(status, "state"),
	}
	s.mu.Unlock()

	if err := writeConfig(urlFile(), target); err != nil {
		fmt.Println("deskcam: could not save the phone address:", err)
	}
	fmt.Println("deskcam: paired with", target)
	phonePage(w, http.StatusOK, "Paired",
		"This phone is now the camera at "+target+". You can close this page.", true)
}

func (s *consoleState) setError(message string) {
	s.mu.Lock()
	s.lastError = message
	s.mu.Unlock()
	fmt.Println("deskcam:", message)
}

// handleToken is card 17: the console makes the key, keeps it, and sends it to the phone
// inside the next pairing code. Nobody types it on the phone.
func (s *consoleState) handleToken(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Query().Get("do") {
	case "new":
		value, err := newToken()
		if err != nil {
			writeJSON(w, http.StatusInternalServerError,
				map[string]any{"ok": false, "error": err.Error()})
			return
		}
		if err := saveToken(value); err != nil {
			writeJSON(w, http.StatusInternalServerError,
				map[string]any{"ok": false, "error": err.Error()})
			return
		}
		// The file is the key's only home, so nothing here caches it. The old pairing
		// code carries the old key and must stop working.
		s.mu.Lock()
		s.tokenDeclared = true
		nonceErr := s.newNonce()
		s.mu.Unlock()
		if nonceErr != nil {
			writeJSON(w, http.StatusInternalServerError,
				map[string]any{"ok": false, "error": nonceErr.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true,
			"message": "New key made. Scan the code again so the phone learns it. Until " +
				"you do, the phone still expects the old one.",
		})

	case "clear":
		if err := clearToken(); err != nil {
			writeJSON(w, http.StatusInternalServerError,
				map[string]any{"ok": false, "error": err.Error()})
			return
		}
		s.mu.Lock()
		s.tokenDeclared = true
		nonceErr := s.newNonce()
		s.mu.Unlock()
		if nonceErr != nil {
			writeJSON(w, http.StatusInternalServerError,
				map[string]any{"ok": false, "error": nonceErr.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true,
			"message": "Key removed here. Scan the code again to clear it on the phone " +
				"too, which opens the camera to anyone on the network.",
		})

	default:
		writeJSON(w, http.StatusBadRequest,
			map[string]any{"ok": false, "error": "do must be new or clear"})
	}
}

func phonePage(w http.ResponseWriter, code int, title, body string, good bool) {
	mark, colour := "&#33;", "#d29922"
	if good {
		mark, colour = "&#10003;", "#3fb950"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_, _ = fmt.Fprintf(w, `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>DeskCam</title>
<style>
 body{margin:0;font:16px/1.5 system-ui,sans-serif;background:#0d1117;color:#e6edf3;
      display:flex;align-items:center;justify-content:center;min-height:100vh;padding:24px}
 .c{max-width:420px;text-align:center}
 .m{font-size:56px;color:%s;line-height:1}
 h1{font-size:22px;margin:16px 0 8px} p{color:#8b949e;margin:0}
</style></head><body><div class="c">
<div class="m">%s</div><h1>%s</h1><p>%s</p>
</div></body></html>`, colour, mark, title, body)
}
