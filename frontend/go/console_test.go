package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// A real console on a real listener, with its config isolated from the person running the
// tests. The Python suite learned that the hard way by reading the real ~/.config on its
// first run.
func testConsole(t *testing.T) (*consoleState, *httptest.Server, string) {
	t.Helper()
	dir := t.TempDir()
	shots := filepath.Join(dir, "shots")
	if err := os.MkdirAll(shots, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, "config"))
	t.Setenv("DESKCAM_JOURNAL", filepath.Join(dir, "journal"))

	state, err := newConsoleState(Config{Shots: shots}, 9999)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	state.routes(mux)
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return state, server, shots
}

func writeCapture(t *testing.T, shots, name string, settings map[string]any) string {
	t.Helper()
	if err := os.WriteFile(filepath.Join(shots, name), []byte("\xff\xd8\xff\xd9"), 0o644); err != nil {
		t.Fatal(err)
	}
	doc := map[string]any{
		"captured_at":  "2026-09-10T08:00:00+10:00",
		"capture_path": "camera_jpeg",
		"settings":     settings,
		"measured":     map[string]any{"exposure_human": "8.00ms (1/125)", "iso": 200.0},
		"orientation":  map[string]any{"tilt_degrees": 1.6, "samples": 32.0},
	}
	raw, _ := json.MarshalIndent(doc, "", "  ")
	side := filepath.Join(shots, strings.TrimSuffix(name, ".jpg")+".json")
	if err := os.WriteFile(side, raw, 0o644); err != nil {
		t.Fatal(err)
	}
	return journalIt(t, journalEntry{Operation: "snap", Ok: true}, filepath.Join(shots, name))
}

// setToken writes the key where the console reads it. Nothing caches it any more, so the
// file is the only way to set one.
func setToken(t *testing.T, value string) {
	t.Helper()
	if err := saveToken(value); err != nil {
		t.Fatal(err)
	}
}

// post is how the page reaches anything that changes state: a POST carrying the header a
// cross-origin form or image cannot set.
func post(t *testing.T, server *httptest.Server, path string) (int, string) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, server.URL+path, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set(consoleHeader, "1")
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	body := make([]byte, 1<<16)
	n, _ := resp.Body.Read(body)
	return resp.StatusCode, string(body[:n])
}

func get(t *testing.T, server *httptest.Server, path string) (int, string) {
	t.Helper()
	// The raw path, not a cleaned one. A client that wants to escape the shots directory
	// will not politely normalise its request first.
	req, err := http.NewRequest(http.MethodGet, server.URL+path, nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	body := make([]byte, 1<<16)
	n, _ := resp.Body.Read(body)
	return resp.StatusCode, string(body[:n])
}

// ------------------------------------------------------------------ pairing

func TestACodePairsOnceAndNoMore(t *testing.T) {
	state, _, _ := testConsole(t)
	code := state.nonce
	if !state.spendNonce(code) {
		t.Fatal("a fresh code must pair")
	}
	if state.spendNonce(code) {
		t.Fatal("a spent code must never work twice")
	}
}

func TestACodeDiesOfOldAge(t *testing.T) {
	state, _, _ := testConsole(t)
	state.nonceBorn = time.Now().Add(-nonceTTL - time.Second)
	if state.spendNonce(state.nonce) {
		t.Fatal("an expired code must not pair")
	}
}

func TestAWrongCodeNeverPairs(t *testing.T) {
	state, _, _ := testConsole(t)
	for _, candidate := range []string{"", "not-the-code", state.nonce + "x"} {
		if state.spendNonce(candidate) {
			t.Fatalf("%q must not pair", candidate)
		}
	}
}

func TestPairingWithADeadCodeIsRefused(t *testing.T) {
	state, server, _ := testConsole(t)
	state.nonceBorn = time.Now().Add(-nonceTTL - time.Second)
	code, body := get(t, server, "/p/"+state.nonce)
	if code != http.StatusGone {
		t.Fatalf("want 410, got %d", code)
	}
	if !strings.Contains(strings.ToLower(body), "expired") {
		t.Fatal("the page should say the code expired")
	}
}

// The address must come from where the request came from, never from what it claims.
// Two requests carrying an addr of another machine used to point the console, and the key
// it sends, at that machine. Card 29.
func TestPairingIgnoresTheAddressTheCallerClaims(t *testing.T) {
	state, server, _ := testConsole(t)
	// Port 9 is discard: nothing answers HTTP there, so the pairing fails and the message
	// names the address it actually used.
	code, body := get(t, server, "/p/"+state.nonce+"?addr=203.0.113.9&port=9")
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if strings.Contains(body, "203.0.113.9") {
		t.Fatal("the claimed address must never be used")
	}
	if !strings.Contains(body, "127.0.0.1") {
		t.Fatal("the source address should be the one reported")
	}
	if state.phone != "" {
		t.Fatal("a failed probe must not save a phone address")
	}
}

// ------------------------------------------------------------------ secrets

func TestTheStateEndpointGivesAwayNoSecret(t *testing.T) {
	state, server, _ := testConsole(t)
	setToken(t, "s3cret-token-value")
	code, body := get(t, server, "/api/state")
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	for _, forbidden := range []string{"s3cret-token-value", state.nonce, "pair_qr", "pair_url"} {
		if strings.Contains(body, forbidden) {
			t.Fatalf("/api/state leaked %q", forbidden)
		}
	}
	var doc map[string]any
	if err := json.Unmarshal([]byte(body), &doc); err != nil {
		t.Fatal(err)
	}
	if doc["token_set"] != true {
		t.Fatal("it should still say a key is set, because the page shows that")
	}
}

func TestANewCodeIsNotReturnedInTheReply(t *testing.T) {
	state, server, _ := testConsole(t)
	before := state.nonce
	_, body := post(t, server, "/api/newcode")
	if state.nonce == before {
		t.Fatal("the code should have changed")
	}
	if strings.Contains(body, state.nonce) {
		t.Fatal("the reply must not carry the new code")
	}
}

// The previous version of this test was called TestTheQRIsAnImageAndCarriesTheKey and
// asserted only that the token did not appear as literal text in the SVG. It passed, and
// passing it read as clearance, while the image it approved encoded the key and the live
// pairing nonce for anyone on the network who fetched the URL. A QR code is not an
// encryption. The property that matters is who can ask for the picture.
func TestTheQRIsServedToThisMachineAndNowhereElse(t *testing.T) {
	state, server, _ := testConsole(t)
	setToken(t, "s3cret-token-value")

	code, body := get(t, server, "/qr.svg")
	if code != http.StatusOK || !strings.HasPrefix(body, "<svg") {
		t.Fatalf("the operator's own browser should get the code, got %d %.40q", code, body)
	}
	if !strings.Contains(state.pairText(), "s3cret-token-value") {
		t.Fatal("the pairing code is supposed to carry the key; that is how the phone learns it")
	}

	// The same request from anywhere else.
	if got := statusFromLAN(t, state, "/qr.svg"); got != http.StatusForbidden {
		t.Fatalf("the network must not be able to fetch the pairing code, got %d", got)
	}
}

// statusFromLAN runs one request through the console's routing with a source address that
// is not this machine.
func statusFromLAN(t *testing.T, state *consoleState, path string) int {
	t.Helper()
	mux := http.NewServeMux()
	state.routes(mux)
	req := httptest.NewRequest(http.MethodGet, path, nil)
	req.RemoteAddr = "192.168.86.99:54321"
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec.Code
}

func TestOnlyPairingIsOfferedToTheNetwork(t *testing.T) {
	state, _, shots := testConsole(t)
	id := writeCapture(t, shots, "one.jpg", map[string]any{"zoom": 1.0})
	for _, path := range []string{
		"/", "/qr.svg", "/install.svg", "/api/install", "/api/state", "/api/roll",
		"/img/" + id + "/0", "/thumb/" + id + "/0", "/sidecar/" + id + "/0", "/api/stream?fps=10",
		"/api/op?do=snap", "/api/newcode", "/api/token?do=clear",
	} {
		if got := statusFromLAN(t, state, path); got != http.StatusForbidden {
			t.Errorf("%s answered the network with %d, want 403", path, got)
		}
	}
	// The one route the phone needs. It must still be reachable, and a stale code is a
	// 410 rather than a 403, which proves it was not refused for being off-machine.
	mux := http.NewServeMux()
	state.routes(mux)
	req := httptest.NewRequest(http.MethodGet, "/p/"+state.nonce, nil)
	req.RemoteAddr = "192.168.86.99:54321"
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code == http.StatusForbidden {
		t.Fatal("the phone must be able to reach the pairing callback")
	}
}

// A page in the operator's browser is on loopback, so loopback alone does not stop a
// website they happen to be visiting from firing a request at the console.
func TestAWebsiteCannotChangeAnythingThroughTheBrowser(t *testing.T) {
	_, server, _ := testConsole(t)
	for _, path := range []string{"/api/token?do=clear", "/api/newcode", "/api/op?do=snap"} {
		// What an <img> or a cross-origin form can send: a GET, or a POST with no header.
		if code, _ := get(t, server, path); code != http.StatusMethodNotAllowed {
			t.Errorf("GET %s should not be allowed to change anything, got %d", path, code)
		}
		req, err := http.NewRequest(http.MethodPost, server.URL+path, nil)
		if err != nil {
			t.Fatal(err)
		}
		resp, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("POST %s without the header should be refused, got %d", path, resp.StatusCode)
		}
	}
}

// -------------------------------------------------------------- serving files

func TestNoPathReachesOutsideTheShotsDirectory(t *testing.T) {
	_, server, _ := testConsole(t)
	for _, path := range []string{
		"/img/../main.go",
		"/img/../../etc/passwd",
		"/img/..%2f..%2fetc%2fpasswd",
		"/img/%2e%2e%2fmain.go",
		"/thumb/../main.go",
		"/sidecar/../main.go",
		"/img/main.go",
		"/img/.hidden.jpg",
		"/img/",
	} {
		code, body := get(t, server, path)
		if code == http.StatusOK {
			t.Errorf("%s was served", path)
		}
		if strings.Contains(body, "package main") {
			t.Errorf("%s leaked source", path)
		}
	}
}

// ------------------------------------------------------------- the live view

// A phone with an access key set. The stream is the one thing the page used to fetch
// straight from the phone, and the browser has no key by design, so this is the request
// that went dark the moment anyone turned a key on. Card 54.
func phoneWithAKey(t *testing.T, key string) (*httptest.Server, *int) {
	t.Helper()
	frames := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("token") != key {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = fmt.Fprint(w, `{"ok":false,"error":"wrong or missing token"}`)
			return
		}
		switch r.URL.Path {
		case "/api/stream":
			w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=frame")
			w.WriteHeader(http.StatusOK)
			frames++
			_, _ = fmt.Fprintf(w, "--frame\r\nContent-Type: image/jpeg\r\n\r\nJPEGBYTES\r\n")
		case "/api/status":
			_, _ = fmt.Fprint(w, `{"settings":{"zoom":1},"state":"running"}`)
		default:
			_, _ = fmt.Fprint(w, "{}")
		}
	}))
	t.Cleanup(server.Close)
	return server, &frames
}

// pairTo points the console at an address the way handlePair would.
func pairTo(state *consoleState, phone string) {
	state.mu.Lock()
	state.phone = phone
	state.mu.Unlock()
}

func TestTheLiveViewWorksWhenAnAccessKeyIsSet(t *testing.T) {
	state, server, _ := testConsole(t)
	setToken(t, "the-key")
	phone, frames := phoneWithAKey(t, "the-key")
	pairTo(state, phone.URL)

	resp, err := server.Client().Get(server.URL + "/api/stream?fps=10")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<12))
		t.Fatalf("the live view answered %d: %s", resp.StatusCode, body)
	}
	if !strings.HasPrefix(resp.Header.Get("Content-Type"), "multipart/x-mixed-replace") {
		t.Fatalf("the browser needs the phone's content type, got %q",
			resp.Header.Get("Content-Type"))
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<12))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(body), "JPEGBYTES") {
		t.Fatalf("the frames should reach the page, got %q", body)
	}
	if *frames != 1 {
		t.Fatalf("the phone served %d streams, want 1", *frames)
	}
	if strings.Contains(string(body), "the-key") {
		t.Fatal("the key must not reach the browser")
	}
}

// The page cannot read a status code off an <img>. The console saw the phone's answer, so
// it keeps the reason where the page can ask for it.
func TestAStreamThatFailsSaysWhy(t *testing.T) {
	state, server, _ := testConsole(t)
	phone, _ := phoneWithAKey(t, "the-key") // the console has no key, so this refuses
	pairTo(state, phone.URL)

	code, body := get(t, server, "/api/stream?fps=10")
	if code != http.StatusBadGateway {
		t.Fatalf("a refused stream should be a 502, got %d", code)
	}
	if !strings.Contains(body, "401") {
		t.Fatalf("the page should be told what the phone said, got %q", body)
	}

	_, state1 := get(t, server, "/api/state")
	var doc map[string]any
	if err := json.Unmarshal([]byte(state1), &doc); err != nil {
		t.Fatal(err)
	}
	reason, _ := doc["stream_error"].(string)
	if !strings.Contains(reason, "401") || !strings.Contains(reason, "access key") {
		t.Fatalf("the state should name the key as the problem, got %q", reason)
	}
}

func TestAStreamWithNoPhoneSaysSoRatherThanNothing(t *testing.T) {
	_, server, _ := testConsole(t)
	code, body := get(t, server, "/api/stream")
	if code != http.StatusBadGateway || !strings.Contains(body, "no phone") {
		t.Fatalf("want a 502 saying no phone is paired, got %d %q", code, body)
	}
}

// A page in this browser must not be able to use the console as a way to reach the rest
// of the phone's query with the operator's key attached to it.
func TestTheStreamCarriesNothingButTheFrameRate(t *testing.T) {
	state, server, _ := testConsole(t)
	setToken(t, "the-key")
	var seen string
	phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/stream" {
			seen = r.URL.RawQuery
		}
		_, _ = fmt.Fprint(w, "{}")
	}))
	defer phone.Close()
	pairTo(state, phone.URL)

	if code, _ := get(t, server, "/api/stream?fps=10&rotate=180&zoom=8&t=123"); code != http.StatusOK {
		t.Fatalf("the stream should have been relayed, got %d", code)
	}
	if seen != "fps=10&token=the-key" {
		t.Fatalf("the phone was sent %q, want only the frame rate and the key", seen)
	}
	if code, _ := get(t, server, "/api/stream?fps=nonsense"); code != http.StatusBadRequest {
		t.Error("a frame rate that is not a number should be refused here")
	}
}

// ------------------------------------------------------------------- the key

// Card 17. The console makes the key, keeps it, and sends it in the next pairing code.
func TestTheConsoleMakesAndRemovesTheKey(t *testing.T) {
	state, server, _ := testConsole(t)
	before := state.pairText()

	if code, body := post(t, server, "/api/token?do=new"); code != http.StatusOK {
		t.Fatalf("making a key should work, got %d %.80q", code, body)
	}
	_, token, _ := state.snapshot()
	if token == "" {
		t.Fatal("the console should now hold a key")
	}
	if readTrimmed(tokenFile()) != token {
		t.Fatal("the CLI reads the same file, so it must be written")
	}
	if !strings.Contains(state.pairText(), "token=") {
		t.Fatal("the pairing code must carry the key")
	}
	if state.pairText() == before {
		t.Fatal("the pairing code should have changed")
	}

	if code, _ := post(t, server, "/api/token?do=clear"); code != http.StatusOK {
		t.Fatalf("removing a key should work, got %d", code)
	}
	if _, token, _ = state.snapshot(); token != "" {
		t.Fatal("the key should be gone")
	}
	// An empty value, not an absent one. An absent parameter leaves the phone's old key
	// in place, which is the opposite of what removing it means.
	if !strings.Contains(state.pairText(), "token=") {
		t.Fatal("the pairing code must tell the phone to forget its key")
	}
}

func TestAKeyIsLongEnoughToBeWorthHaving(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 50; i++ {
		value, err := newToken()
		if err != nil {
			t.Fatal(err)
		}
		if len(value) < 20 {
			t.Fatalf("a key of %d characters is too short", len(value))
		}
		if seen[value] {
			t.Fatal("two keys came out the same")
		}
		seen[value] = true
	}
}

// ------------------------------------------------------------------ addresses

func TestAnAddressIsAnAddress(t *testing.T) {
	for _, host := range []string{"127.0.0.1", "192.168.86.120", "::1"} {
		if !ipOK(host) {
			t.Errorf("%q is an address", host)
		}
	}
	for _, host := range []string{"example.com", "", "127.0.0.1/../x", "file:///etc/passwd"} {
		if ipOK(host) {
			t.Errorf("%q is not an address", host)
		}
	}
}

func TestAPhoneURLIsHTTPAndAnIP(t *testing.T) {
	for _, raw := range []string{"http://127.0.0.1:8080", "http://192.168.86.120:8080"} {
		if !phoneURLOK(raw) {
			t.Errorf("%q should be accepted", raw)
		}
	}
	for _, raw := range []string{
		"https://127.0.0.1:8080",
		"http://evil.example.com:8080",
		"file:///etc/passwd",
		"http://127.0.0.1:8080/api/set?token=x",
		"http://127.0.0.1",
		"",
	} {
		if phoneURLOK(raw) {
			t.Errorf("%q should be refused", raw)
		}
	}
}

func TestTheLanAddressIsAnAddress(t *testing.T) {
	if !ipOK(lanAddress()) {
		t.Fatalf("%q is not an address", lanAddress())
	}
}

func TestProbingRefusesATargetThatIsNotAnAddress(t *testing.T) {
	if probePhone("evil.example.com", 80, "", time.Second) != nil {
		t.Error("a name is not an address")
	}
	if probePhone("127.0.0.1", 0, "", time.Second) != nil {
		t.Error("port 0 is not a port")
	}
}

// --------------------------------------------------------------- the page

func TestThePageCarriesNoSecretAndNoCameraParameterInItsStream(t *testing.T) {
	state, server, _ := testConsole(t)
	setToken(t, "s3cret-token-value")
	_ = state
	code, body := get(t, server, "/")
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if strings.Contains(body, "s3cret-token-value") {
		t.Fatal("the page must not carry the key")
	}
	// A reconnecting browser must not rewrite the camera an agent is about to use.
	if !strings.Contains(body, "'/api/stream?fps=10&t='") {
		t.Fatal("the stream URL should carry only fps and a cache buster")
	}
	// The live view goes through the console, which holds the key. Pointing it at the
	// phone is what made an access key turn the video black. Card 54.
	if strings.Contains(body, "S.phone+'/api/stream") {
		t.Fatal("the page must not fetch the stream from the phone directly")
	}
	if strings.Contains(body, "stream?fps=10&rotate=") {
		t.Fatal("the stream URL must not carry a camera parameter")
	}
}

// ------------------------------------------- ported from the Python console

// These eight came across when console.py was deleted in the card 53 cutover. Each one
// held a property the Go suite did not, and a property nobody tests is a property that
// stops being true.

func TestACodeIsValidJustInsideItsLife(t *testing.T) {
	// The other side of TestACodeDiesOfOldAge. A boundary needs both of its sides, or a
	// comparison that is off by one only fails in the direction nobody checked.
	state, _, _ := testConsole(t)
	state.nonceBorn = time.Now().Add(-nonceTTL + time.Second)
	if !state.spendNonce(state.nonce) {
		t.Fatal("a code one second short of its life must still pair")
	}
}

func TestTheOperatorsOwnBrowserStillGetsEverything(t *testing.T) {
	// The counterpart to TestOnlyPairingIsOfferedToTheNetwork. Refusing the network is
	// only correct if the machine it runs on is still served, and a rule that refuses
	// everything passes the other test perfectly.
	_, server, shots := testConsole(t)
	id := writeCapture(t, shots, "one.jpg", map[string]any{"zoom": 1.0})
	for _, path := range []string{
		"/", "/qr.svg", "/install.svg", "/api/install", "/api/state", "/api/roll",
		"/img/" + id + "/0", "/thumb/" + id + "/0", "/sidecar/" + id + "/0",
	} {
		if code, _ := get(t, server, path); code != http.StatusOK {
			t.Errorf("the operator's own browser should get %s, got %d", path, code)
		}
	}
}

func TestAnUnknownPathIsA404(t *testing.T) {
	_, server, _ := testConsole(t)
	if code, _ := get(t, server, "/nope"); code != http.StatusNotFound {
		t.Fatalf("want 404, got %d", code)
	}
}

func TestASavedAddressThatIsNotAnAddressIsIgnored(t *testing.T) {
	// A file on disk is not a trusted input. Everything downstream builds a URL out of
	// this, and the console sends the access key to whatever it names.
	for _, tc := range []struct{ saved, want string }{
		{"file:///etc/passwd", ""},
		{"http://evil.example.com:8080", ""},
		{"http://192.168.86.120:8080", "http://192.168.86.120:8080"},
	} {
		dir := t.TempDir()
		t.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, "config"))
		if err := writeConfig(urlFile(), tc.saved); err != nil {
			t.Fatal(err)
		}
		state, err := newConsoleState(Config{Shots: dir}, 9999)
		if err != nil {
			t.Fatal(err)
		}
		if state.phone != tc.want {
			t.Errorf("saved %q became phone %q, want %q", tc.saved, state.phone, tc.want)
		}
	}
}

func TestAMissingConfigIsNotAnError(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, "nothing"))
	state, err := newConsoleState(Config{Shots: dir}, 9999)
	if err != nil {
		t.Fatalf("a first run has no config and that is not a fault: %v", err)
	}
	if state.phone != "" || state.currentToken() != "" {
		t.Fatal("a first run knows no phone and holds no key")
	}
}

func TestProbingAnAddressWithNothingOnIt(t *testing.T) {
	// Port 9 is discard. Nothing on this machine answers HTTP there, so this is the
	// dial-failure path rather than the not-an-address path above it.
	if probePhone("127.0.0.1", 9, "", time.Second) != nil {
		t.Fatal("a port with nothing on it is not a phone")
	}
}

// The second route the network may reach: the app itself, so a phone that scans the
// install code can fetch it. It carries no secret; the same file is on the releases page.
func TestTheAppIsHandedToThePhoneWhenThisCloneHasOne(t *testing.T) {
	state, _, _ := testConsole(t)
	apk := filepath.Join(t.TempDir(), "deskcam.apk")
	if err := os.WriteFile(apk, []byte("PK\x03\x04 not really an app"), 0o644); err != nil {
		t.Fatal(err)
	}
	state.apk = apk
	mux := http.NewServeMux()
	state.routes(mux)
	req := httptest.NewRequest(http.MethodGet, "/deskcam.apk", nil)
	req.RemoteAddr = "192.168.86.99:54321"
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("the phone must be able to fetch the app, got %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/vnd.android.package-archive" {
		t.Fatalf("an Android installer wants the APK type, got %q", ct)
	}
	url, _ := state.installURL()
	if !strings.HasPrefix(url, "http://") || !strings.HasSuffix(url, ":9999/deskcam.apk") {
		t.Fatalf("the install code should point at this console, got %s", url)
	}
}

func TestWithNoAppOfItsOwnTheInstallCodePointsAtTheRelease(t *testing.T) {
	state, _, _ := testConsole(t)
	if url, _ := state.installURL(); url != releaseAPK {
		t.Fatalf("with no local build the code should be the release, got %s", url)
	}
	if !strings.HasPrefix(releaseAPK, "https://github.com/") {
		t.Fatal("the release must come over HTTPS from the project's own page")
	}
	if got := statusFromLAN(t, state, "/deskcam.apk"); got != http.StatusNotFound {
		t.Fatalf("a console with no app of its own must say so, got %d", got)
	}
}

func TestAnAppThatIsNotThereIsAnError(t *testing.T) {
	if _, err := findAPK(filepath.Join(t.TempDir(), "missing.apk")); err == nil {
		t.Fatal("an --apk that does not exist must be an error, not a quiet fallback to the release")
	}
}
