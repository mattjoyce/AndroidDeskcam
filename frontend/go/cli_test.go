package main

import (
	"archive/tar"
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// ------------------------------------------------------------- the parameters

// The generated table must be what the generator produces from Params.java right now.
// That is the whole claim: the names are not typed here, they come from the one place a
// parameter exists. Card 31, card 53.
func TestTheParameterTableIsGeneratedNotTyped(t *testing.T) {
	source := filepath.Join("..", "..", "backend", "app", "src", "dev", "deskcam", "Params.java")
	if _, err := os.Stat(source); err != nil {
		t.Skip("Params.java is not here")
	}
	fresh := filepath.Join(t.TempDir(), "params_gen.go")
	cmd := exec.Command("go", "run", "./internal/genparams", source, fresh)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("the generator failed: %v\n%s", err, out)
	}
	want, err := os.ReadFile(fresh)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile("params_gen.go")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(want, got) {
		t.Fatal("params_gen.go is stale. Run go generate ./...")
	}
}

func TestTheTableKnowsWhereEachParameterTakesEffect(t *testing.T) {
	// Decision D9. Widening the presentation set quietly is how w and h came to persist.
	presentation := map[string]bool{}
	for name, kind := range Params {
		if kind == Presentation {
			presentation[name] = true
		}
	}
	want := map[string]bool{"w": true, "h": true, "jpegq": true, "quality": true}
	if fmt.Sprint(presentation) != fmt.Sprint(want) {
		t.Fatalf("presentation is %v, want %v", presentation, want)
	}
	if Params["rotate"] != Camera {
		t.Fatal("rotate is camera state; it describes how the phone is bolted down")
	}
	if Params["zoom"] != Camera || Params["settle"] != Router {
		t.Fatal("the kinds are wrong")
	}
}

// If a phone is reachable, its own help is the authority and the table must agree with it.
func TestTheTableAgreesWithALivePhone(t *testing.T) {
	target := os.Getenv("DESKCAM_URL")
	if target == "" {
		t.Skip("no DESKCAM_URL, so there is no phone to ask")
	}
	client := NewClient(loadConfig(""))
	help, err := client.GetJSON("/api/help", "")
	if err != nil {
		t.Skipf("the phone did not answer: %v", err)
	}
	names, ok := help["parameter_names"].(map[string]any)
	if !ok {
		t.Fatal("/api/help has no parameter_names")
	}
	for name := range names {
		if _, known := Params[name]; !known {
			t.Errorf("the phone accepts %q and this build does not know it", name)
		}
	}
	for name := range Params {
		if _, known := names[name]; !known {
			t.Errorf("this build knows %q and the phone does not accept it", name)
		}
	}
}

// ----------------------------------------------------------- the stream guard

func TestAStreamRefusesACameraParameterBeforeItLeaves(t *testing.T) {
	in := &invocation{command: "stream", query: "rotate=180&fps=10"}
	if code := in.checkParams(); code == 0 {
		t.Fatal("a camera parameter on a stream should be refused locally")
	}
	ok := &invocation{command: "stream", query: "fps=10&n=30&w=320"}
	if code := ok.checkParams(); code != 0 {
		t.Fatal("presentation and router parameters are fine on a stream")
	}
	// The phone is the authority, so an unfamiliar name is a warning and still goes.
	unknown := &invocation{command: "snap", query: "wibble=1"}
	if code := unknown.checkParams(); code != 0 {
		t.Fatal("an unknown name must not be refused locally")
	}
}

// ------------------------------------------------------------------- recall

func TestRecallNamesCameraStateAndNothingElse(t *testing.T) {
	dir := t.TempDir()
	sidecar := filepath.Join(dir, "shot.json")
	doc := map[string]any{"settings": map[string]any{
		"camera": "0", "zoom": 6.0, "cx": 0.3, "cy": 0.7, "rotate": 180.0,
		"measure": true, "torch": 25.0, "awb": "daylight", "awb_lock": true,
		"ae_lock": false, "shading_map": false, "ae": "manual",
		"exposure_ns": 8333333.0, "iso": 200.0, "focus_diopters": 6.67,
		"jpeg_quality": 92.0, "out_w": 320.0, "out_h": 240.0,
		"preview_size_requested": "1280x960", "still_size": "max",
	}}
	raw, _ := json.Marshal(doc)
	if err := os.WriteFile(sidecar, raw, 0o644); err != nil {
		t.Fatal(err)
	}
	q, err := recallQuery(sidecar)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"zoom=6", "cx=0.3", "rotate=180", "measure=1",
		"torch=25", "awb=daylight", "focus=6.67", "exposure=8333333", "iso=200",
		"previewsize=1280x960"} {
		if !strings.Contains(q, want) {
			t.Errorf("recall should name %s, got %q", want, q)
		}
	}
	// Presentation is gone by the time recall runs, so naming it would promise something
	// the phone no longer does. Decision D9. Compared as whole names: "torch=" contains
	// "h=", which is how the first version of this check fooled itself.
	named := map[string]bool{}
	for _, pair := range strings.Split(q, "&") {
		key, _, _ := strings.Cut(pair, "=")
		named[key] = true
	}
	for _, forbidden := range []string{"jpegq", "quality", "w", "h", "stillsize"} {
		if named[forbidden] {
			t.Errorf("recall must not name %q, got %q", forbidden, q)
		}
	}
	for name := range named {
		if kind, known := Params[name]; !known || kind != Camera {
			t.Errorf("recall named %q, which is %v and not camera state", name, kind)
		}
	}
}

// ---------------------------------------------------------------- summarise

func TestShowPrintsEverySettingThatCanSpoilTheNextCapture(t *testing.T) {
	status := map[string]any{
		"state": "running",
		"settings": map[string]any{
			"zoom": 6.0, "cx": 0.3, "cy": 0.7, "af": "off", "ae": "manual",
			"rotate": 180.0, "measure": true, "torch": 25.0, "awb_lock": true,
			"out_w": 320.0, "capture_path": "decoded_and_reencoded", "camera": "0",
		},
		"measured": map[string]any{"exposure_human": "8.33ms (1/120)", "iso": 200.0},
	}
	line := summarise(status)
	// These four were exactly the four the shell version left out. Card 26.
	for _, want := range []string{"rotate 180", "MEASURE", "out 320x-", "zoom 6x",
		"8.33ms (1/120)", "iso 200", "torch 25", "awb locked", "path decoded_and_reencoded"} {
		if !strings.Contains(line, want) {
			t.Errorf("show should print %q, got %q", want, line)
		}
	}
}

func TestShowSaysWhenTheCameraIsNotRunning(t *testing.T) {
	line := summarise(map[string]any{
		"state":    "disconnected",
		"settings": map[string]any{"zoom": 1.0, "cx": 0.5, "cy": 0.5},
	})
	if !strings.Contains(line, "[disconnected]") {
		t.Fatalf("a camera that is not running should say so, got %q", line)
	}
}

// -------------------------------------------------------------- the client

func TestTheReasonFromThePhoneReachesTheCaller(t *testing.T) {
	// curl -f used to discard exactly this, so the message R5 goes to the trouble of
	// writing never reached a person.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"ok":false,"error":"unknown parameter 'zomo'"}`)
	}))
	defer server.Close()

	client := NewClient(Config{URL: server.URL, Timeout: defaultTestTimeout})
	_, err := client.Get("/api/set", "zomo=4")
	if err == nil {
		t.Fatal("a 400 should be an error")
	}
	if !strings.Contains(err.Error(), "unknown parameter 'zomo'") {
		t.Fatalf("the phone's reason should survive, got %q", err)
	}
}

func TestTheTokenIsSentAndNeverDoubled(t *testing.T) {
	var seen string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.URL.RawQuery
		_, _ = fmt.Fprint(w, "{}")
	}))
	defer server.Close()
	client := NewClient(Config{URL: server.URL, Token: "abc123", Timeout: defaultTestTimeout})
	if _, err := client.Get("/api/status", "zoom=2"); err != nil {
		t.Fatal(err)
	}
	if seen != "zoom=2&token=abc123" {
		t.Fatalf("query was %q", seen)
	}
}

func TestAFailedCaptureLeavesNoTruncatedFile(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = fmt.Fprint(w, `{"error":"camera not running"}`)
	}))
	defer server.Close()
	out := filepath.Join(t.TempDir(), "shot.jpg")
	client := NewClient(Config{URL: server.URL, Timeout: defaultTestTimeout})
	if _, err := client.GetFile("/api/still", "", out); err == nil {
		t.Fatal("a 500 should be an error")
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Fatal("a failed capture must not leave a file that looks like one")
	}
}

// ------------------------------------------------------------------- burst

func TestABurstIsUnpackedAndAShortOneIsReported(t *testing.T) {
	var archive bytes.Buffer
	writer := tar.NewWriter(&archive)
	for i := 0; i < 3; i++ {
		body := []byte(fmt.Sprintf("frame %d", i))
		if err := writer.WriteHeader(&tar.Header{
			Name: fmt.Sprintf("burst-%03d.jpg", i),
			Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg,
		}); err != nil {
			t.Fatal(err)
		}
		_, _ = writer.Write(body)
	}
	_ = writer.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/burst") {
			w.Header().Set("X-DeskCam-Frames", "3")
			w.Header().Set("X-DeskCam-Frames-Requested", "8")
			w.Header().Set("X-DeskCam-Provenance", `{"tool":"DeskCam","settings":{"zoom":1}}`)
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write(archive.Bytes())
			return
		}
		_, _ = fmt.Fprint(w, "{}")
	}))
	defer server.Close()

	dir := filepath.Join(t.TempDir(), "burst")
	cfg := Config{URL: server.URL, Shots: t.TempDir(), Timeout: defaultTestTimeout}
	in := &invocation{command: "burst", args: []string{"8"}, out: dir, cfg: cfg,
		client: NewClient(cfg)}
	if code := burst(in); code != 0 {
		t.Fatalf("burst returned %d", code)
	}
	for i := 0; i < 3; i++ {
		name := filepath.Join(dir, fmt.Sprintf("burst-%03d.jpg", i))
		if _, err := os.Stat(name); err != nil {
			t.Errorf("%s was not unpacked", name)
		}
	}
	// The sidecar comes from the provenance header of the same reply.
	raw, err := os.ReadFile(filepath.Join(dir, "burst.json"))
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	if doc["from"] != "the capture itself" {
		t.Fatalf("the sidecar should come from the capture, got %v", doc["from"])
	}
}

func TestABurstArchiveCannotWriteOutsideItsDirectory(t *testing.T) {
	var archive bytes.Buffer
	writer := tar.NewWriter(&archive)
	body := []byte("nope")
	_ = writer.WriteHeader(&tar.Header{
		Name: "../../escaped.jpg", Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg,
	})
	_, _ = writer.Write(body)
	_ = writer.Close()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/status" {
			_, _ = fmt.Fprint(w, `{"settings":{},"measured":{}}`)
			return
		}
		w.Header().Set("X-DeskCam-Frames", "1")
		_, _ = w.Write(archive.Bytes())
	}))
	defer server.Close()

	root := t.TempDir()
	dir := filepath.Join(root, "burst")
	cfg := Config{URL: server.URL, Shots: root, Timeout: defaultTestTimeout}
	in := &invocation{command: "burst", args: []string{"1"}, out: dir, cfg: cfg,
		client: NewClient(cfg)}
	burst(in)
	if _, err := os.Stat(filepath.Join(root, "..", "escaped.jpg")); err == nil {
		t.Fatal("a member name is a path, and an archive is untrusted input")
	}
	if _, err := os.Stat(filepath.Join(dir, "escaped.jpg")); err != nil {
		t.Fatal("the member should have landed inside the burst directory")
	}
}
