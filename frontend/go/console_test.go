package main

import (
	"encoding/json"
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

	state := &consoleState{port: 9999, shots: shots}
	state.newNonce()
	mux := http.NewServeMux()
	state.routes(mux)
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return state, server, shots
}

func writeCapture(t *testing.T, shots, name string, settings map[string]any) {
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
	defer resp.Body.Close()
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
	state.token = "s3cret-token-value"
	code, body := get(t, server, "/api/state")
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	for _, forbidden := range []string{state.token, state.nonce, "pair_qr", "pair_url"} {
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
	_, body := get(t, server, "/api/newcode")
	if state.nonce == before {
		t.Fatal("the code should have changed")
	}
	if strings.Contains(body, state.nonce) {
		t.Fatal("the reply must not carry the new code")
	}
}

func TestTheQRIsAnImageAndCarriesTheKey(t *testing.T) {
	state, server, _ := testConsole(t)
	state.token = "s3cret-token-value"
	code, body := get(t, server, "/qr.svg")
	if code != http.StatusOK || !strings.HasPrefix(body, "<svg") {
		t.Fatalf("want an svg, got %d %.40q", code, body)
	}
	// The picture may hold the key, because it is on the operator's own screen. What it
	// must not do is hold it as readable text.
	if strings.Contains(body, state.token) {
		t.Fatal("the svg should be modules, not the text of the key")
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

func TestACaptureAndItsSidecarComeBack(t *testing.T) {
	_, server, shots := testConsole(t)
	writeCapture(t, shots, "one.jpg", map[string]any{"zoom": 3.0, "cx": 0.5, "cy": 0.5})
	if code, _ := get(t, server, "/img/one.jpg"); code != http.StatusOK {
		t.Fatalf("the capture should be served, got %d", code)
	}
	code, body := get(t, server, "/sidecar/one.jpg")
	if code != http.StatusOK || !strings.Contains(body, `"zoom": 3`) {
		t.Fatalf("the sidecar should be served, got %d %.60q", code, body)
	}
}

func TestAMissingSidecarSaysSo(t *testing.T) {
	_, server, shots := testConsole(t)
	if err := os.WriteFile(filepath.Join(shots, "lonely.jpg"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	code, body := get(t, server, "/sidecar/lonely.jpg")
	if code != http.StatusNotFound || !strings.Contains(body, "no sidecar") {
		t.Fatalf("want a 404 saying so, got %d %.60q", code, body)
	}
}

func TestInShotsAcceptsOnlyWhatTheConsoleWrote(t *testing.T) {
	_, _, shots := testConsole(t)
	writeCapture(t, shots, "good.jpg", map[string]any{"zoom": 1.0})
	if err := os.WriteFile(filepath.Join(shots, "notes.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, ok := inShots(shots, "good.jpg"); !ok {
		t.Error("a capture should be servable")
	}
	for _, name := range []string{"notes.txt", "../main.go", "", "missing.jpg", ".hidden.jpg"} {
		if _, ok := inShots(shots, name); ok {
			t.Errorf("%q must not be servable", name)
		}
	}
}

// --------------------------------------------------------------------- roll

func TestTheRollReadsADirectoryOfCaptures(t *testing.T) {
	_, _, shots := testConsole(t)
	writeCapture(t, shots, "one.jpg", map[string]any{"zoom": 1.0, "cx": 0.5, "cy": 0.5})
	time.Sleep(10 * time.Millisecond)
	writeCapture(t, shots, "two.jpg", map[string]any{"zoom": 6.0, "cx": 0.3, "cy": 0.7, "measure": true})
	if err := os.WriteFile(filepath.Join(shots, "two.thumb.jpg"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	items := roll(shots, 60)
	if len(items) != 2 {
		t.Fatalf("want 2 captures with the thumbnail skipped, got %d", len(items))
	}
	if items[0].Name != "two.jpg" {
		t.Fatalf("newest first, got %q", items[0].Name)
	}
	if !strings.Contains(items[0].Summary, "measure") {
		t.Errorf("the summary should mention measurement mode, got %q", items[0].Summary)
	}
	if items[0].Exposure != "8.00ms (1/125)" {
		t.Errorf("the exposure should come from the sidecar, got %q", items[0].Exposure)
	}
}

func TestTheRollSurvivesABrokenSidecar(t *testing.T) {
	_, _, shots := testConsole(t)
	if err := os.WriteFile(filepath.Join(shots, "bad.jpg"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(shots, "bad.json"), []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	items := roll(shots, 60)
	if len(items) != 1 || items[0].Name != "bad.jpg" {
		t.Fatal("a broken sidecar must not lose the capture")
	}
}

func TestTheRollOfADirectoryThatIsNotThere(t *testing.T) {
	if got := roll(filepath.Join(t.TempDir(), "nowhere"), 60); len(got) != 0 {
		t.Fatalf("want nothing, got %d", len(got))
	}
}

// ------------------------------------------------------------------- the key

// Card 17. The console makes the key, keeps it, and sends it in the next pairing code.
func TestTheConsoleMakesAndRemovesTheKey(t *testing.T) {
	state, server, _ := testConsole(t)
	before := state.pairText()

	if code, _ := get(t, server, "/api/token?do=new"); code != http.StatusOK {
		t.Fatalf("making a key should work, got %d", code)
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

	if code, _ := get(t, server, "/api/token?do=clear"); code != http.StatusOK {
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
	state.token = "s3cret-token-value"
	code, body := get(t, server, "/")
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if strings.Contains(body, state.token) {
		t.Fatal("the page must not carry the key")
	}
	// A reconnecting browser must not rewrite the camera an agent is about to use.
	if !strings.Contains(body, "/api/stream?fps=10&t=") {
		t.Fatal("the stream URL should carry only fps and a cache buster")
	}
	if strings.Contains(body, "stream?fps=10&rotate=") {
		t.Fatal("the stream URL must not carry a camera parameter")
	}
}
