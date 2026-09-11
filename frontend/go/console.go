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
	// True once the operator has deliberately cleared the key, so the pairing code says
	// token= with an empty value. An absent parameter leaves the phone's old key alone,
	// which is right at first pairing and wrong when the point is to remove it.
	//
	// This is the one piece of key state held in memory. The key itself is not: see
	// currentToken.
	tokenDeclared bool
	lastPair      map[string]any
	lastError     string
	// Why the live view is not running, in the words the operator needs. An <img> that
	// fails tells the page nothing but "failed", and the console is the only party that
	// saw the phone's answer. Empty while the stream is healthy.
	streamError string
	// The app this console hands to a phone at /deskcam.apk, or empty, in which case the
	// install code points at the latest release instead.
	apk string
}

func newConsoleState(cfg Config, port int) (*consoleState, error) {
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
	if err := s.newNonce(); err != nil {
		return nil, err
	}
	return s, nil
}

// currentToken reads the key from the file every time it is needed.
//
// It used to be read once at startup and kept. `deskcam token new` writes the same file,
// so a console that had been running since before that command kept serving a QR carrying
// the old key, kept sending the old key to the phone, and kept reporting that a key was
// set. One identity, two places, and no way for the second to learn about the first.
func (s *consoleState) currentToken() string { return readTrimmed(tokenFile()) }

// newNonce replaces the pairing code. The caller must hold s.mu.
//
// It used to panic when the system had no randomness. net/http recovers a handler panic
// per connection, so the process survived with s.mu still locked and the next request
// touching state blocked for ever: a hang rather than a crash, which is worse and much
// harder to diagnose. Mechanism returns the error, and the caller decides the policy.
// At startup that policy is to refuse to run at all, because a predictable pairing code
// is worse than no console.
func (s *consoleState) newNonce() error {
	raw := make([]byte, 9)
	if _, err := rand.Read(raw); err != nil {
		return fmt.Errorf("no randomness available for a pairing code: %w", err)
	}
	s.nonce = base64.RawURLEncoding.EncodeToString(raw)
	s.nonceBorn = time.Now()
	return nil
}

// spendNonce checks a code and burns it in one step, so it works exactly once and two
// requests cannot race on the same one.
func (s *consoleState) spendNonce(candidate string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	ok := candidate != "" && candidate == s.nonce && time.Since(s.nonceBorn) < nonceTTL
	if ok && s.newNonce() != nil {
		// Fail closed. An empty code matches nothing, so pairing stops until the console
		// is restarted, rather than continuing with a code that has already been spent.
		s.nonce = ""
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
	return s.phone, s.currentToken(), s.nonce
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
	token, declared := s.currentToken(), s.tokenDeclared
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
	defer func() { _ = conn.Close() }()
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
	defer func() { _ = resp.Body.Close() }()
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

func serve(cfg Config, port int, apk string) int {
	state, err := newConsoleState(cfg, port)
	if err != nil {
		// A console that cannot make an unpredictable pairing code is worse than none.
		return fail("%v", err)
	}
	state.apk = apk
	mux := http.NewServeMux()
	state.routes(mux)

	// Bound to every interface on purpose: the phone has to reach /p/ to pair, and it is
	// not on the loopback. Every other route is refused from anywhere but this machine,
	// because the QR carries the access key and the roll serves files. See routes.
	server := &http.Server{
		Addr:              fmt.Sprintf("0.0.0.0:%d", port),
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	fmt.Printf("DeskCam console on http://%s:%d\n", lanAddress(), port)
	fmt.Printf("  local:  http://127.0.0.1:%d\n", port)
	fmt.Printf("  shots:  %s\n", state.shots)
	fmt.Printf("  the page, the roll and the pairing code are served to this machine only;\n")
	fmt.Printf("  the phone is offered /p/ and /deskcam.apk and nothing else\n")
	installAt, installFrom := state.installURL()
	fmt.Printf("  install: %s (%s)\n", installAt, installFrom)
	if phone, _, _ := state.snapshot(); phone != "" {
		fmt.Printf("  phone:  %s\n", phone)
	}
	if err = server.ListenAndServe(); err != nil {
		return fail("%v", err)
	}
	return 0
}

// routes wires the console.
//
// Two routes are offered to the network: /p/, the callback the phone makes to finish
// pairing, and /deskcam.apk, the app the install code points at. Neither carries a
// secret. Everything else is the operator's own browser and is refused from anywhere but
// the loopback interface.
//
// This is not belt and braces. /qr.svg renders the pairing code, which carries the access
// key and the live nonce, and this server binds every interface because the phone has to
// reach /p/. Before this, any machine on the network could fetch that image, decode it,
// and have both. A comment two lines from the route claimed the console answered with no
// secret; it was describing an intention rather than the code.
func (s *consoleState) routes(mux *http.ServeMux) {
	mux.HandleFunc("/p/", s.handlePair)         // the phone, from the network
	mux.HandleFunc("/deskcam.apk", s.handleAPK) // the phone, from the network

	local := func(h http.HandlerFunc) http.HandlerFunc { return s.loopbackOnly(h) }
	// Reading routes: the operator's browser, loopback only.
	mux.HandleFunc("/", local(s.handlePage))
	mux.HandleFunc("/qr.svg", local(s.handleQR))
	mux.HandleFunc("/install.svg", local(s.handleInstallQR))
	mux.HandleFunc("/api/install", local(s.handleInstall))
	mux.HandleFunc("/api/state", local(s.handleState))
	mux.HandleFunc("/api/roll", local(s.handleRoll))
	mux.HandleFunc("/api/stream", local(s.handleStream))
	mux.HandleFunc("/img/", local(s.handleFile))
	mux.HandleFunc("/thumb/", local(s.handleFile))
	mux.HandleFunc("/sidecar/", local(s.handleFile))
	// Routes that change something: loopback, and a POST the page has to mean.
	mux.HandleFunc("/api/cam", local(s.mutating(s.handleCam)))
	mux.HandleFunc("/api/newcode", local(s.mutating(s.handleNewCode)))
	mux.HandleFunc("/api/token", local(s.mutating(s.handleToken)))
}

// loopbackOnly refuses a request that did not come from this machine.
func (s *consoleState) loopbackOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		ip := net.ParseIP(host)
		if ip == nil || !ip.IsLoopback() {
			http.Error(w, "this console answers the browser on the machine it runs on; "+
				"only pairing is offered to the network", http.StatusForbidden)
			return
		}
		next(w, r)
	}
}

// mutating guards a route that changes state against a request the operator did not make.
//
// Loopback alone is not enough here. A page in the operator's own browser is on loopback,
// so any website they visit could fire <img src="http://127.0.0.1:9000/api/token?do=clear">
// and make the next pairing tell the phone to forget its key. A POST carrying a header
// cannot be forged that way: a form or an image cannot set it, and a cross-origin fetch
// that tries needs a preflight this server never answers.
func (s *consoleState) mutating(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", http.MethodPost)
			http.Error(w, "this changes something, so it needs a POST",
				http.StatusMethodNotAllowed)
			return
		}
		if r.Header.Get(consoleHeader) != "1" {
			http.Error(w, "missing "+consoleHeader+"; this request did not come from the "+
				"console page", http.StatusForbidden)
			return
		}
		next(w, r)
	}
}

// The header the console page sets on anything that changes state. Its only job is to be
// impossible for a cross-origin form or image to send.
const consoleHeader = "X-DeskCam-Console"

func writeJSON(w http.ResponseWriter, code int, body any) {
	out, err := json.MarshalIndent(body, "", "  ")
	if err != nil {
		http.Error(w, "{}", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	// A client that has gone away cannot be told anything, so there is nothing to do with
	// this error but say that ignoring it is deliberate. Same at every write below.
	_, _ = w.Write(append(out, '\n'))
}

func (s *consoleState) handlePage(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" && r.URL.Path != "/index.html" {
		http.Error(w, "no such page", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.WriteString(w, consolePage)
}

func (s *consoleState) handleQR(w http.ResponseWriter, r *http.Request) {
	writeQR(w, s.pairText())
}

// writeQR draws text as a QR code in SVG, light on transparent for the dark page.
func writeQR(w http.ResponseWriter, text string) {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		http.Error(w, "cannot make a code", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "image/svg+xml")
	w.Header().Set("Cache-Control", "no-store")
	const scale, border = 6, 2
	side := (code.Size + border*2) * scale
	_, _ = fmt.Fprintf(w, `<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" `+
		`viewBox="0 0 %d %d" shape-rendering="crispEdges">`, side, side, side, side)
	for y := 0; y < code.Size; y++ {
		for x := 0; x < code.Size; x++ {
			if code.Black(x, y) {
				_, _ = fmt.Fprintf(w, `<rect x="%d" y="%d" width="%d" height="%d" fill="#e6edf3"/>`,
					(x+border)*scale, (y+border)*scale, scale, scale)
			}
		}
	}
	_, _ = io.WriteString(w, "</svg>")
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
	out["stream_error"] = s.streamError
	s.mu.Unlock()

	if phone != "" {
		if host, port, ok := splitPhone(phone); ok {
			if status := probePhone(host, port, token, 2*time.Second); status != nil {
				out["online"] = true
				// preview and device let the page show whether the camera is asleep
				// and how hot the phone is, rather than leaving both a mystery.
				for _, key := range []string{"settings", "measured", "orientation",
					"pipeline", "sensor", "state", "preview", "device"} {
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
	err := s.newNonce()
	s.mu.Unlock()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError,
			map[string]any{"ok": false, "error": err.Error()})
		return
	}
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
	// The thumbnail is written beside the capture, from the capture's own bytes, when the
	// capture is taken. There is nothing to decode here, and a capture taken before that
	// existed falls back to the full image.
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
	_, _ = w.Write(body)
}

// noteStream records why the live view is or is not running, for the page to read from
// /api/state. An <img> only ever learns that it failed.
func (s *consoleState) noteStream(reason string) {
	s.mu.Lock()
	s.streamError = reason
	s.mu.Unlock()
}

// streamClient is for the live view alone.
//
// No overall timeout: a stream is meant to stay open for as long as the page is watching,
// and http.Client.Timeout covers reading the body as well as reaching the phone.
// ResponseHeaderTimeout bounds the part that can hang without a single pixel arriving,
// and the request context ends the rest when the browser goes away.
var streamClient = &http.Client{
	Transport: &http.Transport{
		ResponseHeaderTimeout: 10 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	},
}

// handleStream relays the phone's live view to the page.
//
// The page used to point its <img> straight at the phone. That was the one place it went
// round the console, and it was where two correct decisions composed into a broken
// feature: /api/state withholds the access key from the browser on purpose (card 29), so
// the browser had none to send, so a phone with a key set answered 401 and the live view
// went black. The header still read "online", because the console had probed it with the
// key it holds.
//
// Relaying MJPEG was refused on cost. The cost is one goroutine and a 32 KiB buffer for
// one operator on the loopback, which is a great deal less than a live view is worth.
func (s *consoleState) handleStream(w http.ResponseWriter, r *http.Request) {
	phone, token, _ := s.snapshot()
	if phone == "" {
		s.noteStream("No phone is paired, so there is nothing to watch. Press Pair.")
		http.Error(w, "no phone paired", http.StatusBadGateway)
		return
	}

	// Only the frame rate is carried through. The phone refuses a camera parameter on a
	// stream, and a console route that forwards whatever it is handed is a way for a page
	// in this browser to reach the rest of the phone's query with the operator's key on
	// it. A reconnect must not change what an agent is about to capture.
	q := url.Values{}
	if raw := r.URL.Query().Get("fps"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > 60 {
			http.Error(w, "fps is a whole number of frames a second, 1 to 60",
				http.StatusBadRequest)
			return
		}
		q.Set("fps", strconv.Itoa(n))
	}
	if token != "" {
		q.Set("token", token)
	}
	target := phone + "/api/stream"
	if len(q) > 0 {
		target += "?" + q.Encode()
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
	if err != nil {
		// The message would quote the URL, and the URL carries the key. Say what is
		// wrong with the address instead of repeating it.
		s.noteStream("The paired address is not one the console can request. Pair again.")
		http.Error(w, "the paired address is not a URL", http.StatusInternalServerError)
		return
	}
	resp, err := streamClient.Do(req)
	if err != nil {
		if r.Context().Err() != nil {
			return // the page closed the stream; that is not a fault to report
		}
		// Not err.Error(). A transport failure quotes the URL it was given, and that URL
		// carries the access key, which would put the key in /api/state and on the page.
		reason := sentence(advice(err))
		s.noteStream(reason)
		http.Error(w, reason, http.StatusBadGateway)
		return
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
		failed := &HTTPError{Status: resp.StatusCode, Message: errorIn(body), Path: "/api/stream"}
		reason := fmt.Sprintf("HTTP %d from the phone's live view.", resp.StatusCode)
		if said := errorIn(body); said != "" {
			reason += " " + said
		}
		if next := advice(failed); next != "" {
			reason += "\n" + sentence(next)
		}
		s.noteStream(reason)
		http.Error(w, reason, http.StatusBadGateway)
		return
	}

	kind := resp.Header.Get("Content-Type")
	if kind == "" {
		kind = "multipart/x-mixed-replace"
	}
	w.Header().Set("Content-Type", kind)
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	s.noteStream("")

	// Copied by hand rather than with io.Copy, because each part has to reach the browser
	// as it arrives. Buffered until 2 KiB had accumulated, a 10 fps view would show its
	// frames late and out of step with the camera.
	flush := http.NewResponseController(w)
	buf := make([]byte, 32*1024)
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if _, err := w.Write(buf[:n]); err != nil {
				return // the browser has gone; nothing to say and nobody to say it to
			}
			_ = flush.Flush()
		}
		if readErr != nil {
			if readErr != io.EOF && r.Context().Err() == nil {
				s.noteStream("The live view stopped part way through. Press Restream.")
			}
			return
		}
	}
}

// handleCam forwards a control request to the phone, so the page only ever talks to the
// console. So does the live view, through handleStream.
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
	defer func() { _ = resp.Body.Close() }()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(body)
}

// ------------------------------------------------------------------ install

// releaseAPK is the newest published build. GitHub redirects this address to the asset of
// the latest release, so it never changes and an install code printed today still works.
const releaseAPK = "https://github.com/mattjoyce/AndroidDeskcam/releases/latest/download/deskcam.apk"

// installURL is where the install code sends the phone, and where that is, in words.
func (s *consoleState) installURL() (url, source string) {
	if s.apk != "" {
		return fmt.Sprintf("http://%s:%d/deskcam.apk", lanAddress(), s.port), "the build on this workstation"
	}
	return releaseAPK, "the latest release on GitHub"
}

// handleAPK hands the phone the app it is about to pair with. It is offered to the network
// because the phone has to reach it, and it carries no secret. It is plain HTTP, so a
// phone installing for the first time trusts whatever arrives: the same trusted-LAN stance
// as the rest of the tool, which the README states. The release is served over HTTPS.
func (s *consoleState) handleAPK(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/deskcam.apk" || s.apk == "" {
		http.Error(w, "this console has no app of its own to hand out; the install code points at the release",
			http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="deskcam.apk"`)
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, s.apk)
}

func (s *consoleState) handleInstallQR(w http.ResponseWriter, r *http.Request) {
	url, _ := s.installURL()
	writeQR(w, url)
}

func (s *consoleState) handleInstall(w http.ResponseWriter, r *http.Request) {
	url, source := s.installURL()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]string{"url": url, "source": source, "version": version})
}

// findAPK returns the app a console should hand out: the file named with --apk, or the one
// ./backend/build.sh leaves in a clone, looked for beside the binary and then in the
// current directory. Empty when there is none, and the install code then points at the
// release.
func findAPK(explicit string) (string, error) {
	if explicit != "" {
		st, err := os.Stat(explicit)
		if err != nil || st.IsDir() {
			return "", fmt.Errorf("no app at %s", explicit)
		}
		return filepath.Abs(explicit)
	}
	var candidates []string
	if exe, err := os.Executable(); err == nil {
		if real, err := filepath.EvalSymlinks(exe); err == nil {
			exe = real
		}
		candidates = append(candidates, filepath.Join(filepath.Dir(exe), "..", "..", "backend", "build", "deskcam.apk"))
	}
	candidates = append(candidates, filepath.Join("backend", "build", "deskcam.apk"))
	for _, c := range candidates {
		if st, err := os.Stat(c); err == nil && !st.IsDir() {
			return filepath.Abs(c)
		}
	}
	return "", nil
}
