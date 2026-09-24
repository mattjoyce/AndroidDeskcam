package main

import (
	"archive/tar"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
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
		"focus_box": []any{0.35, 0.35, 0.15, 0.15},
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
		"previewsize=1280x960",
		// A list goes back as the phone parses it, not as Go prints a slice. Card 60.
		"focusbox=0.35,0.35,0.15,0.15"} {
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

// Sharpness is a comparison, so a stale one is worse than none: an agent following the
// slope of a focus sweep would walk straight past the peak on a number from four seconds
// ago. Card 9.
func TestShowPrintsSharpnessAndSaysWhenItIsOld(t *testing.T) {
	with := func(age float64) string {
		return summarise(map[string]any{
			"state":     "running",
			"settings":  map[string]any{"zoom": 1.0, "cx": 0.5, "cy": 0.5},
			"sharpness": map[string]any{"value": 341.46, "frame_age_ms": age},
		})
	}
	fresh := with(35)
	if !strings.Contains(fresh, "sharp 341.46") {
		t.Errorf("show should print the sharpness, got %q", fresh)
	}
	if strings.Contains(fresh, "old") {
		t.Errorf("a frame from this moment is not old, got %q", fresh)
	}
	if stale := with(4200); !strings.Contains(stale, "sharp 341.46 (4.2s old)") {
		t.Errorf("an old reading must say how old, got %q", stale)
	}
	// A phone nobody is watching converts no preview frames, so there is nothing to say.
	none := summarise(map[string]any{
		"state": "running", "settings": map[string]any{"zoom": 1.0},
	})
	if strings.Contains(none, "sharp") {
		t.Errorf("no frame means no sharpness, got %q", none)
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

// A hunt that found nothing has to exit non-zero.
//
// The whole point of the endpoint over /api/af is that it can say there was no peak, and
// a shell script that runs `deskcam focus hunt && deskcam snap` has to hear that. Card 56.
func TestAHuntThatRefusesExitsNonZero(t *testing.T) {
	answers := map[string]string{
		"chose": `{"ok":true,"diopters":3.827,"focus_metres_approx":0.261,
			"sharpness":64.38,"contrast":0.959,"readings":14,"millis":4021,
			"coarse_readings":1,"walked":[{"diopters":0,"sharpness":3.4},
			{"diopters":3.827,"sharpness":64.38}]}`,
		"flat": `{"ok":false,"refused":"flat","reason":"the sharpness moved by 0%",
			"diopters":0,"sharpness":4.1,"contrast":0.02,"readings":9,"millis":2600,
			"coarse_readings":9,"walked":[{"diopters":0,"sharpness":4.1}]}`,
	}
	for _, shape := range []string{"chose", "flat"} {
		body := answers[shape]
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/api/focushunt" {
				t.Errorf("a hunt should go to /api/focushunt, went to %s", r.URL.Path)
			}
			_, _ = fmt.Fprint(w, body)
		}))

		cfg := Config{URL: server.URL, Shots: t.TempDir(), Timeout: defaultTestTimeout}
		in := &invocation{command: "focus", args: []string{"hunt"}, cfg: cfg,
			client: NewClient(cfg)}
		printed, code := captureStdout(t, func() int { return focusHunt(in) })
		server.Close()

		switch shape {
		case "chose":
			if code != 0 {
				t.Errorf("a hunt that chose should exit 0, got %d", code)
			}
			for _, want := range []string{"chosen 3.827 d", "about 261 mm", "sharpness 64.38",
				"contrast 0.959", "14 readings in 4.0 s", "the fine pass"} {
				if !strings.Contains(printed, want) {
					t.Errorf("a hunt should print %q, got:\n%s", want, printed)
				}
			}
			// The curve is the evidence, so it is drawn and not summarised away.
			if !strings.Contains(printed, "3.827 d") || !strings.Contains(printed, "#") {
				t.Errorf("a hunt should draw the curve it walked, got:\n%s", printed)
			}
		case "flat":
			if code == 0 {
				t.Error("a hunt that refused must not exit 0")
			}
			// The curve still goes to stdout: a refusal is an answer with evidence.
			if !strings.Contains(printed, "0.000 d") {
				t.Errorf("a refusal should still draw the curve, got:\n%s", printed)
			}
		}
	}
}

// captureStdout runs f with stdout on a pipe and gives back what it wrote.
func captureStdout(t *testing.T, f func() int) (string, int) {
	t.Helper()
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	was := os.Stdout
	os.Stdout = write
	code := f()
	os.Stdout = was
	_ = write.Close()
	out, err := io.ReadAll(read)
	if err != nil {
		t.Fatal(err)
	}
	return string(out), code
}

// ---------------------------------------------------------------- the script

// A script's answer is one ordered stream: an event, then the pixels it describes.
//
// The three shapes that matter are a tape that finished, a tape that stopped at a step,
// and a stream that ended without saying either. Only the first is exit 0. Card 57.
func TestAScriptStreamIsUnpackedAndItsEndingDecidesTheExitCode(t *testing.T) {
	provenance := `{"tool":"DeskCam","settings":{"zoom":2},"measured":{"iso":200}}`
	cases := []struct {
		name    string
		parts   []scriptPart
		closed  bool
		want    int
		expects []string
	}{
		{
			name: "finished",
			parts: []scriptPart{
				{json: `{"started":true,"steps":3,"verbs":["SET","WAIT","SNAP"]}`},
				{json: `{"step":0,"verb":"SET","ok":true,"result":{"settings":{"zoom":2,` +
					`"cx":0.5,"cy":0.5,"af":"off","ae":"manual"}}}`},
				{json: `{"step":1,"verb":"WAIT","ok":true,"waited_ms":400}`},
				{json: `{"step":2,"verb":"SNAP","ok":true,"files":["002-still.jpg"],` +
					`"result":` + provenance + `}`},
				{name: "002-still.jpg", body: "pretend pixels"},
				{json: `{"done":true,"ok":true,"steps":3,"completed":3,"millis":1500}`},
			},
			closed:  true,
			want:    0,
			expects: []string{"3 steps: SET WAIT SNAP", "400 ms", "002-still.jpg", "done: 3 of 3"},
		},
		{
			name: "stopped at a step",
			parts: []scriptPart{
				{json: `{"started":true,"steps":3,"verbs":["SET","SNAP","SET"]}`},
				{json: `{"step":0,"verb":"SET","ok":true,"result":{"settings":{"torch":45}}}`},
				{json: `{"step":1,"verb":"SNAP","ok":false,"error":"the capture timed out"}`},
				{json: `{"done":true,"ok":false,"steps":3,"completed":1,"failed_at":1,` +
					`"line":3,"verb":"SNAP","error":"the capture timed out",` +
					`"restored":{"torch":0,"zoom":1,"cx":0.5,"cy":0.5}}`},
			},
			closed:  true,
			want:    1,
			expects: []string{"FAILED"},
		},
		{
			// The connection died part way. Nothing said the tape finished, so it did not.
			name: "truncated",
			parts: []scriptPart{
				{json: `{"started":true,"steps":2,"verbs":["SET","SNAP"]}`},
				{json: `{"step":0,"verb":"SET","ok":true,"result":{"settings":{"zoom":1}}}`},
			},
			closed: true,
			want:   1,
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodPost || r.URL.Path != "/api/script" {
					t.Errorf("a script should POST to /api/script, got %s %s", r.Method, r.URL.Path)
				}
				tape, _ := io.ReadAll(r.Body)
				if !strings.Contains(string(tape), "SET zoom=2") {
					t.Errorf("the tape should reach the phone as it was written, got %q", tape)
				}
				w.Header().Set("Content-Type", "multipart/mixed; boundary=deskcamstep")
				for _, p := range c.parts {
					_, _ = w.Write([]byte(p.encode()))
				}
				if c.closed {
					_, _ = w.Write([]byte("--deskcamstep--\r\n"))
				}
			}))
			defer server.Close()

			tape := filepath.Join(t.TempDir(), "tape.dcl")
			if err := os.WriteFile(tape, []byte("# a tape\nSET zoom=2\n"), 0o644); err != nil {
				t.Fatal(err)
			}
			dir := filepath.Join(t.TempDir(), "out")
			cfg := Config{URL: server.URL, Shots: t.TempDir(), Timeout: defaultTestTimeout}
			in := &invocation{command: "script", args: []string{"run", tape}, out: dir,
				cfg: cfg, client: NewClient(cfg)}
			printed, code := captureStdout(t, func() int { return scriptCommand(in) })
			if code != c.want {
				t.Errorf("exit code %d, wanted %d; printed:\n%s", code, c.want, printed)
			}
			for _, want := range c.expects {
				if !strings.Contains(printed, want) {
					t.Errorf("a script run should print %q, got:\n%s", want, printed)
				}
			}
		})
	}
}

// The pixels land on disk with the event that describes them beside them, and a part
// name is untrusted input however well the phone behaves.
func TestAScriptWritesEachCaptureAndItsRecord(t *testing.T) {
	parts := []scriptPart{
		{json: `{"started":true,"steps":1,"verbs":["WALK"]}`},
		{json: `{"step":0,"verb":"WALK","ok":true,"files":["000-torch-00-0.jpg","000-walk.json"],` +
			`"result":{"walk":"torch","warnings":["the white balance was left to the camera"],` +
			`"frames":[{"file":"torch-00-0.jpg","step":0,"vary":"torch","value_asked":"0"}]}}`},
		{name: "000-torch-00-0.jpg", body: "frame"},
		{name: "000-walk.json", body: `{"walk":"torch"}`},
		{name: "../../escaped.jpg", body: "nope"},
		{json: `{"done":true,"ok":true,"steps":1,"completed":1,"millis":900}`},
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "multipart/mixed; boundary=deskcamstep")
		for _, p := range parts {
			_, _ = w.Write([]byte(p.encode()))
		}
		_, _ = w.Write([]byte("--deskcamstep--\r\n"))
	}))
	defer server.Close()

	tape := filepath.Join(t.TempDir(), "tape.dcl")
	if err := os.WriteFile(tape, []byte("SET zoom=2\nWALK vary=torch values=0,20\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	dir := filepath.Join(root, "out")
	cfg := Config{URL: server.URL, Shots: root, Timeout: defaultTestTimeout}
	in := &invocation{command: "script", args: []string{"run", tape}, out: dir, cfg: cfg,
		client: NewClient(cfg)}
	if _, code := captureStdout(t, func() int { return scriptCommand(in) }); code != 0 {
		t.Fatalf("the script should have finished, got %d", code)
	}

	if _, err := os.Stat(filepath.Join(root, "..", "escaped.jpg")); err == nil {
		t.Fatal("a part name is a path, and a stream is untrusted input")
	}
	if _, err := os.Stat(filepath.Join(dir, "escaped.jpg")); err != nil {
		t.Fatal("the part should have landed inside the script's own directory")
	}
	// A frame of a walk gets its own entry from the manifest, not the manifest.
	raw, err := os.ReadFile(filepath.Join(dir, "000-torch-00-0.json"))
	if err != nil {
		t.Fatal(err)
	}
	var record map[string]any
	if err := json.Unmarshal(raw, &record); err != nil {
		t.Fatal(err)
	}
	if record["value_asked"] != "0" || record["vary"] != "torch" {
		t.Fatalf("the sidecar should be this frame's own record, got %v", record)
	}
	// The manifest is already a record, so nothing overwrites it with an event.
	manifest, err := os.ReadFile(filepath.Join(dir, "000-walk.json"))
	if err != nil {
		t.Fatal(err)
	}
	if string(manifest) != `{"walk":"torch"}` {
		t.Fatalf("walk.json should arrive untouched, got %s", manifest)
	}
}

// scriptPart is one member of the stream a fake phone writes.
type scriptPart struct {
	json string // a JSON event, when this is not a file
	name string // the filename, when it is
	body string
}

func (p scriptPart) encode() string {
	head := "--deskcamstep\r\nContent-Type: application/json; charset=utf-8\r\n"
	body := p.json
	if p.name != "" {
		head = "--deskcamstep\r\nContent-Type: image/jpeg\r\n" +
			"Content-Disposition: attachment; filename=\"" + p.name + "\"\r\n"
		body = p.body
	}
	return head + fmt.Sprintf("Content-Length: %d\r\n\r\n", len(body)) + body + "\r\n"
}

// A phone the platform is throttling has a hot sensor, and a hot sensor is a noisier one,
// so it belongs on the line that names everything able to change the next capture. A
// phone that is merely warm says nothing: a marker that is always there is not read.
func TestShowSaysWhenThePhoneIsTooHot(t *testing.T) {
	line := func(device map[string]any) string {
		return summarise(map[string]any{
			"state":    "running",
			"settings": map[string]any{"zoom": 1.0, "cx": 0.5, "cy": 0.5},
			"device":   device,
		})
	}
	hot := line(map[string]any{"thermal": "severe", "throttling": true})
	if !strings.Contains(hot, "HOT severe") {
		t.Errorf("a throttling phone should say so, got %q", hot)
	}
	warm := line(map[string]any{"thermal": "light", "throttling": false})
	if strings.Contains(warm, "HOT") {
		t.Errorf("a phone nobody is throttling says nothing, got %q", warm)
	}
	// An older phone, or one that never reported, must not print an empty marker.
	if none := line(nil); strings.Contains(none, "HOT") {
		t.Errorf("no reading is not a hot phone, got %q", none)
	}
}

// A capture with no focus box must not recall one, or a session that judged focus on the
// whole crop would come back judging it somewhere else. Card 60.
func TestRecallLeavesAnAbsentFocusBoxAbsent(t *testing.T) {
	dir := t.TempDir()
	sidecar := filepath.Join(dir, "shot.json")
	for _, absent := range []any{nil, []any{}} {
		doc := map[string]any{"settings": map[string]any{
			"camera": "0", "zoom": 1.0, "cx": 0.5, "cy": 0.5, "ae": "manual",
			"exposure_ns": 8333333.0, "iso": 200.0, "focus_box": absent,
		}}
		raw, _ := json.Marshal(doc)
		if err := os.WriteFile(sidecar, raw, 0o644); err != nil {
			t.Fatal(err)
		}
		q, err := recallQuery(sidecar)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(q, "focusbox") {
			t.Errorf("focus_box %v should recall nothing, got %q", absent, q)
		}
	}
}

// `deskcam use URL KEY` used to drop the key without a word, so it looked like it had been
// set and every later request was refused by the phone. There was also no way at all to
// adopt a key somebody else generated, which is the second machine at a bench: the only
// route was editing the file under ~/.config by hand.
func TestUseAndTokenSetStoreAKeyRatherThanDroppingIt(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)

	if _, code := captureStdout(t, func() int {
		return run([]string{"use", "http://10.0.0.5:8080/", "a-known-key"})
	}); code != 0 {
		t.Fatalf("use with a key exited %d", code)
	}
	if got := readTrimmed(tokenFile()); got != "a-known-key" {
		t.Errorf("the key was not stored, got %q", got)
	}
	// The trailing slash is still trimmed, which is the behaviour that already existed.
	if got := readTrimmed(urlFile()); got != "http://10.0.0.5:8080" {
		t.Errorf("address, got %q", got)
	}

	// A word nobody can account for is an error, never a silent no-op.
	if _, code := captureStdout(t, func() int {
		return run([]string{"use", "http://10.0.0.5:8080", "key", "extra"})
	}); code == 0 {
		t.Error("a third word was accepted; it used to be dropped in silence")
	}

	if _, code := captureStdout(t, func() int {
		return run([]string{"token", "set", "adopted-key"})
	}); code != 0 {
		t.Fatalf("token set exited %d", code)
	}
	if got := readTrimmed(tokenFile()); got != "adopted-key" {
		t.Errorf("token set did not store the key, got %q", got)
	}

	// Naming no key is a usage error rather than a key of empty string, which would open
	// the camera to the network while looking like it had been secured.
	if _, code := captureStdout(t, func() int {
		return run([]string{"token", "set"})
	}); code == 0 {
		t.Error("token set with no key was accepted")
	}
	if got := readTrimmed(tokenFile()); got != "adopted-key" {
		t.Errorf("a refused set changed the stored key to %q", got)
	}
}
