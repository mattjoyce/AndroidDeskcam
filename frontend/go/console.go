package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"rsc.io/qr"
)

// How long a pairing code lives. Ten minutes is long enough to walk to the phone.
const nonceTTL = 10 * time.Minute

// consoleState is everything the console knows, guarded because request threads mutate it.
type consoleState struct {
	mu        sync.Mutex
	port      int
	shots     string
	nonce     string
	nonceBorn time.Time
	phone     string
	token     string
	// True once the operator has deliberately cleared the key, so the pairing code says
	// token= with an empty value. An absent parameter leaves the phone's old key alone,
	// which is right at first pairing and wrong when the point is to remove it.
	tokenDeclared bool
	lastPair      map[string]any
	lastError     string
}

func newConsoleState(cfg Config, port int) *consoleState {
	s := &consoleState{port: port, shots: cfg.Shots}
	if saved := readTrimmed(urlFile()); saved != "" {
		if phoneURLOK(saved) {
			s.phone = saved
		} else {
			// A file on disk is not a trusted input either. Everything downstream builds a
			// URL out of this, and a value that is not an address of a phone could point
			// the console, and the key it carries, somewhere else.
			fmt.Fprintf(os.Stderr, "deskcam: ignoring the saved phone address %q, "+
				"which is not an http address of an IP\n", saved)
		}
	}
	s.token = readTrimmed(tokenFile())
	s.newNonce()
	return s
}

func (s *consoleState) newNonce() string {
	raw := make([]byte, 9)
	if _, err := rand.Read(raw); err != nil {
		// Without a random code, pairing is not safe to offer at all.
		panic("no randomness available for a pairing code: " + err.Error())
	}
	s.nonce = base64.RawURLEncoding.EncodeToString(raw)
	s.nonceBorn = time.Now()
	return s.nonce
}

// spendNonce checks a code and burns it in one step, so it works exactly once and two
// requests cannot race on the same one.
func (s *consoleState) spendNonce(candidate string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	ok := candidate != "" && candidate == s.nonce && time.Since(s.nonceBorn) < nonceTTL
	if ok {
		s.newNonce()
	}
	return ok
}

func (s *consoleState) nonceAge() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return int(time.Since(s.nonceBorn).Seconds())
}

func (s *consoleState) snapshot() (phone, token, nonce string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.phone, s.token, s.nonce
}

// pairText is what the QR code holds.
//
// A custom scheme, not an http URL. Vanadium enforces HTTPS first and refuses to load a
// plain http address, so a browser cannot carry the pairing. This opens the app directly
// and skips the browser entirely.
//
// It carries the access key, which is why it lives in a picture on the operator's screen
// and never in JSON from an endpoint anyone on the network can read. Card 29.
func (s *consoleState) pairText() string {
	q := url.Values{"cb": {s.pairURL()}}
	s.mu.Lock()
	token, declared := s.token, s.tokenDeclared
	s.mu.Unlock()
	if token != "" {
		q.Set("token", token)
	} else if declared {
		// An explicit empty value, which is how the phone is told to forget its key. An
		// absent parameter would leave the old one in place.
		q.Set("token", "")
	}
	return "deskcam://pair?" + q.Encode()
}

func (s *consoleState) pairURL() string {
	s.mu.Lock()
	nonce := s.nonce
	s.mu.Unlock()
	return fmt.Sprintf("http://%s:%d/p/%s", lanAddress(), s.port, nonce)
}

// lanAddress is this machine's address on the route out, not a docker bridge.
func lanAddress() string {
	conn, err := net.Dial("udp", "8.8.8.8:80") // no packet is sent, this only picks a route
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	host, _, err := net.SplitHostPort(conn.LocalAddr().String())
	if err != nil {
		return "127.0.0.1"
	}
	return host
}

// ipOK is true for a literal IP address. Names, schemes and paths are not addresses.
func ipOK(host string) bool { return net.ParseIP(host) != nil }

// phoneURLOK is true for http://IP:PORT and nothing else.
func phoneURLOK(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "http" || u.Path != "" || u.RawQuery != "" || u.User != nil {
		return false
	}
	host, port, err := net.SplitHostPort(u.Host)
	if err != nil || !ipOK(host) {
		return false
	}
	n, err := strconv.Atoi(port)
	return err == nil && n >= 1 && n <= 65535
}

// probePhone asks a candidate address for its status, which confirms it really is DeskCam.
func probePhone(ip string, port int, token string, timeout time.Duration) map[string]any {
	if !ipOK(ip) || port < 1 || port > 65535 {
		return nil
	}
	target := "http://" + net.JoinHostPort(ip, strconv.Itoa(port)) + "/api/status"
	if token != "" {
		target += "?token=" + url.QueryEscape(token)
	}
	client := &http.Client{Timeout: timeout}
	resp, err := client.Get(target)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil
	}
	var doc map[string]any
	if json.Unmarshal(body, &doc) != nil {
		return nil
	}
	return doc
}

// ------------------------------------------------------------------ handlers

func serve(cfg Config, port int) int {
	state := newConsoleState(cfg, port)
	mux := http.NewServeMux()
	state.routes(mux)

	// Bound to every interface on purpose: the phone has to reach this to pair, and it is
	// not on the loopback. Nothing here answers with a secret, and the only files it
	// serves are the captures in the shots directory.
	server := &http.Server{
		Addr:              fmt.Sprintf("0.0.0.0:%d", port),
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	fmt.Printf("DeskCam console on http://%s:%d\n", lanAddress(), port)
	fmt.Printf("  local:  http://127.0.0.1:%d\n", port)
	fmt.Printf("  shots:  %s\n", state.shots)
	if phone, _, _ := state.snapshot(); phone != "" {
		fmt.Printf("  phone:  %s\n", phone)
	}
	if err := server.ListenAndServe(); err != nil {
		return fail("%v", err)
	}
	return 0
}

func (s *consoleState) routes(mux *http.ServeMux) {
	mux.HandleFunc("/", s.handlePage)
	mux.HandleFunc("/qr.svg", s.handleQR)
	mux.HandleFunc("/p/", s.handlePair)
	mux.HandleFunc("/api/state", s.handleState)
	mux.HandleFunc("/api/roll", s.handleRoll)
	mux.HandleFunc("/api/cam", s.handleCam)
	mux.HandleFunc("/api/newcode", s.handleNewCode)
	mux.HandleFunc("/api/token", s.handleToken)
	mux.HandleFunc("/img/", s.handleFile)
	mux.HandleFunc("/thumb/", s.handleFile)
	mux.HandleFunc("/sidecar/", s.handleFile)
}

func writeJSON(w http.ResponseWriter, code int, body any) {
	out, err := json.MarshalIndent(body, "", "  ")
	if err != nil {
		http.Error(w, "{}", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	w.Write(append(out, '\n'))
}

func (s *consoleState) handlePage(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" && r.URL.Path != "/index.html" {
		http.Error(w, "no such page", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	io.WriteString(w, consolePage)
}

func (s *consoleState) handleQR(w http.ResponseWriter, r *http.Request) {
	code, err := qr.Encode(s.pairText(), qr.M)
	if err != nil {
		http.Error(w, "cannot make a code", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "image/svg+xml")
	w.Header().Set("Cache-Control", "no-store")
	const scale, border = 6, 2
	side := (code.Size + border*2) * scale
	fmt.Fprintf(w, `<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" `+
		`viewBox="0 0 %d %d" shape-rendering="crispEdges">`, side, side, side, side)
	for y := 0; y < code.Size; y++ {
		for x := 0; x < code.Size; x++ {
			if code.Black(x, y) {
				fmt.Fprintf(w, `<rect x="%d" y="%d" width="%d" height="%d" fill="#e6edf3"/>`,
					(x+border)*scale, (y+border)*scale, scale, scale)
			}
		}
	}
	io.WriteString(w, "</svg>")
}

// handleState carries no secret. It used to return the pairing text, which holds the
// access key, and the pairing URL, which holds the nonce, from an endpoint with no
// password on a server bound to every interface. Card 29.
func (s *consoleState) handleState(w http.ResponseWriter, r *http.Request) {
	phone, token, _ := s.snapshot()
	out := map[string]any{
		"workstation":      lanAddress(),
		"port":             s.port,
		"code_age_seconds": s.nonceAge(),
		"phone":            phone,
		"token_set":        token != "",
		"shots":            s.shots,
		"online":           false,
	}
	s.mu.Lock()
	out["last_pair"] = s.lastPair
	out["last_error"] = s.lastError
	s.mu.Unlock()

	if phone != "" {
		if host, port, ok := splitPhone(phone); ok {
			if status := probePhone(host, port, token, 2*time.Second); status != nil {
				out["online"] = true
				for _, key := range []string{"settings", "measured", "orientation",
					"pipeline", "sensor", "state"} {
					if v, ok := status[key]; ok {
						out[key] = v
					}
				}
			}
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func splitPhone(raw string) (string, int, bool) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", 0, false
	}
	host, port, err := net.SplitHostPort(u.Host)
	if err != nil {
		return "", 0, false
	}
	n, err := strconv.Atoi(port)
	if err != nil {
		return "", 0, false
	}
	return host, n, true
}

func (s *consoleState) handleRoll(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"captures": roll(s.shots, 60)})
}

// handleNewCode says a new code exists. It does not say what the code is: this endpoint
// has no password either, and the pairing text carries both the nonce and the key.
func (s *consoleState) handleNewCode(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	s.newNonce()
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "code_age_seconds": 0})
}

func (s *consoleState) handleFile(w http.ResponseWriter, r *http.Request) {
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/"), "/", 2)
	if len(parts) != 2 {
		http.Error(w, "no such capture", http.StatusNotFound)
		return
	}
	kind, raw := parts[0], parts[1]
	name, err := url.PathUnescape(raw)
	if err != nil {
		http.Error(w, "no such capture", http.StatusNotFound)
		return
	}
	if kind == "sidecar" {
		name = strings.TrimSuffix(name, filepath.Ext(name)) + ".json"
	}
	path, ok := inShots(s.shots, name)
	if !ok {
		if kind == "sidecar" {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "no sidecar for " + name})
			return
		}
		http.Error(w, "no such capture", http.StatusNotFound)
		return
	}
	// The phone writes NAME.thumb.jpg beside the capture, so there is nothing to decode
	// here. A capture taken before that existed falls back to the full image.
	if kind == "thumb" {
		thumb := strings.TrimSuffix(path, filepath.Ext(path)) + ".thumb.jpg"
		if info, err := os.Stat(thumb); err == nil && !info.IsDir() {
			path = thumb
		}
	}
	body, err := os.ReadFile(path)
	if err != nil {
		http.Error(w, "no such capture", http.StatusNotFound)
		return
	}
	switch strings.ToLower(filepath.Ext(path)) {
	case ".jpg", ".jpeg":
		w.Header().Set("Content-Type", "image/jpeg")
	case ".json":
		w.Header().Set("Content-Type", "application/json")
	default:
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Write(body)
}

// handleCam forwards a control request to the phone, so the page only ever talks to the
// console. The live stream still comes straight from the phone, because relaying MJPEG
// would cost far more than it is worth.
func (s *consoleState) handleCam(w http.ResponseWriter, r *http.Request) {
	phone, token, _ := s.snapshot()
	if phone == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "no phone paired"})
		return
	}
	target := phone + "/api/set"
	if q := r.URL.RawQuery; q != "" {
		target += "?" + q
	}
	if token != "" {
		if strings.Contains(target, "?") {
			target += "&token=" + url.QueryEscape(token)
		} else {
			target += "?token=" + url.QueryEscape(token)
		}
	}
	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.Get(target)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	w.Write(body)
}
