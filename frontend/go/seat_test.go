package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// aPhone answers a still with a picture and everything else with an ok, and keeps what it
// was asked, in order.
func aPhone(t *testing.T) (*httptest.Server, func() []string) {
	t.Helper()
	picture := aJPEG(t)
	var mu sync.Mutex
	var asked []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		asked = append(asked, r.URL.Path+"?"+r.URL.RawQuery)
		mu.Unlock()
		if r.URL.Path == "/api/still" {
			w.Header().Set("X-DeskCam-Provenance", `{"settings":{"zoom":4,"cx":0.3,"cy":0.7}}`)
			_, _ = w.Write(picture)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"ok":true,"settings":{"zoom":4}}`)
	}))
	t.Cleanup(server.Close)
	return server, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), asked...)
	}
}

// The console is the agent's seat: a person there does what the agent did, through the
// code the agent's command runs, and it is written down the same way. Decision D19.
func TestAStillFromTheSeatIsAnOrdinaryJournalledStill(t *testing.T) {
	state, server, shots := testConsole(t)
	phone, _ := aPhone(t)
	pairTo(state, phone.URL)

	code, body := post(t, server, "/api/op?do=snap&why=looking+for+myself")
	if code != http.StatusOK || !strings.Contains(body, `"ok": true`) {
		t.Fatalf("got %d %s", code, body)
	}
	groups := rollFrom(journalDir(), rollLimit, sessionGap)
	if len(groups) != 1 {
		t.Fatalf("want one group, got %+v", groups)
	}
	item := groups[0].Sessions[0].Items[0]
	if item.Operation != "snap" || item.Via != "console" || item.Why != "looking for myself" {
		t.Errorf("the row is %+v", item)
	}
	if !item.Exists || !strings.HasPrefix(item.Path, shots) {
		t.Errorf("the still should be in the console's shots directory, got %q", item.Path)
	}
}

// "Shoot this again" restored the settings and stopped, and it built them in the page's
// own JavaScript from six of the keys recallQuery knows. It now runs recallQuery's code and
// then takes the picture.
func TestShootThisAgainRecallsThroughTheClisCodeAndThenShoots(t *testing.T) {
	state, server, shots := testConsole(t)
	phone, asked := aPhone(t)
	pairTo(state, phone.URL)
	id := writeCapture(t, shots, "before.jpg", map[string]any{
		"zoom": 6.0, "cx": 0.3, "cy": 0.7, "rotate": 0.0, "awb": "daylight",
		"focus_box": []any{0.2, 0.7, 0.1, 0.1},
	})

	code, body := post(t, server, "/api/op?do=again&id="+id+"&n=0")
	if code != http.StatusOK || !strings.Contains(body, `"ok": true`) {
		t.Fatalf("got %d %s", code, body)
	}
	got := asked()
	if len(got) != 2 || !strings.HasPrefix(got[0], "/api/set?") || !strings.HasPrefix(got[1], "/api/still?") {
		t.Fatalf("want a set and then a still, got %v", got)
	}
	// Two keys the page's own copy never sent.
	for _, want := range []string{"reset=1", "zoom=6", "awb=daylight", "focusbox=0.2"} {
		if !strings.Contains(got[0], want) {
			t.Errorf("the recall should carry %s, got %s", want, got[0])
		}
	}
}

// A refusal at the seat comes back in the phone's words, and is journalled as one.
func TestARefusalAtTheSeatSaysWhy(t *testing.T) {
	state, server, _ := testConsole(t)
	phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_, _ = fmt.Fprint(w, `{"ok":false,"error":"a script holds the camera"}`)
	}))
	defer phone.Close()
	pairTo(state, phone.URL)

	code, body := post(t, server, "/api/op?do=snap")
	var reply map[string]any
	if err := json.Unmarshal([]byte(body), &reply); err != nil {
		t.Fatalf("not JSON: %d %s", code, body)
	}
	if reply["ok"] != false || !strings.Contains(body, "a script holds the camera") {
		t.Fatalf("got %d %s", code, body)
	}
	groups := rollFrom(journalDir(), rollLimit, sessionGap)
	if len(groups) != 1 || groups[0].Sessions[0].Refused != 1 {
		t.Fatalf("the refusal should be in the journal, got %+v", groups)
	}
}

// The seat operates and does not aim. It offers named operations, and anything else,
// a zoom or a pan most of all, is the bench tool's job.
func TestTheSeatOffersOperationsAndNothingElse(t *testing.T) {
	state, server, _ := testConsole(t)
	phone, asked := aPhone(t)
	pairTo(state, phone.URL)
	for _, path := range []string{"/api/op?do=zoom&zoom=4", "/api/op?do=set&zoom=4", "/api/op"} {
		if code, _ := post(t, server, path); code == http.StatusOK {
			t.Errorf("%s should not be offered", path)
		}
	}
	if got := asked(); len(got) != 0 {
		t.Fatalf("nothing should have reached the phone, got %v", got)
	}
	// What framing is made of. A gesture on the view is allowed, and two exist, a double tap
	// to focus and a shift-drag to mark, but neither may send a zoom or a pan.
	for _, framing := range []string{"zoomby", "zoom=", "cx=", "cy=", "rotate="} {
		if strings.Contains(consolePage, framing) {
			t.Errorf("the page can still frame the camera: it contains %q", framing)
		}
	}
}

func TestTheSeatNeedsAPhone(t *testing.T) {
	_, server, _ := testConsole(t)
	if code, body := post(t, server, "/api/op?do=snap"); code != http.StatusBadRequest || !strings.Contains(body, "no phone paired") {
		t.Fatalf("got %d %s", code, body)
	}
}
