package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// zoomedPhone holds a framing and keeps what it was asked.
func zoomedPhone(t *testing.T, settings string) (*httptest.Server, func() []string) {
	t.Helper()
	var mu sync.Mutex
	var asked []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		asked = append(asked, r.URL.Path+"?"+r.URL.RawQuery)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprintf(w, `{"ok":true,"state":"running","settings":%s}`, settings)
	}))
	t.Cleanup(server.Close)
	return server, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), asked...)
	}
}

// An agent looks at a frame and sees the soft part at some place in it. That place is a
// fraction of the picture it can see, and turning it into a focus box means knowing the
// crop. The phone's page did that sum in JavaScript; this is the same sum, once, in the
// tool everybody uses. Decisions D17 and D19.
func TestFocusAtNamesAPlaceInThePictureYouCanSee(t *testing.T) {
	for _, c := range []struct{ settings, at, want string }{
		// The whole sensor: the picture is the sensor.
		{`{"zoom":1,"cx":0.5,"cy":0.5}`, "0.25,0.75", "focusbox=0.2500,0.7500,0.0900,0.0900"},
		// Zoom 4 around 0.3,0.7 shows 0.175 to 0.425 across, so its middle is 0.3.
		{`{"zoom":4,"cx":0.3,"cy":0.7}`, "0.5,0.5", "focusbox=0.3000,0.7000,0.0225,0.0225"},
		// A crop pushed against the edge is clamped, exactly as the phone clamps it.
		{`{"zoom":4,"cx":0.05,"cy":0.5}`, "0.5,0.5", "focusbox=0.1250,0.5000,0.0225,0.0225"},
		// A camera that has never been aimed reports no centre at all.
		{`{"zoom":2,"cx":null,"cy":null}`, "1,0", "focusbox=0.7500,0.2500,0.0450,0.0450"},
	} {
		phone, asked := zoomedPhone(t, c.settings)
		_, code := captureStdout(t, func() int { return run([]string{"focus", "at", c.at, "--url", phone.URL}) })
		if code != 0 {
			t.Fatalf("%s at %s: got %d", c.settings, c.at, code)
		}
		got := asked()
		if len(got) != 2 || !strings.HasPrefix(got[0], "/api/status") || !strings.HasPrefix(got[1], "/api/af?") {
			t.Fatalf("want a status and then an af, got %v", got)
		}
		if !strings.Contains(strings.ReplaceAll(got[1], "%2C", ","), c.want) {
			t.Errorf("%s at %s: want %s, got %s", c.settings, c.at, c.want, got[1])
		}
	}
}

func TestFocusAtTakesTwoWordsAsWellAsOne(t *testing.T) {
	phone, asked := zoomedPhone(t, `{"zoom":1}`)
	if _, code := captureStdout(t, func() int { return run([]string{"focus", "at", "0.5", "0.5", "--url", phone.URL}) }); code != 0 {
		t.Fatalf("got %d", code)
	}
	if got := asked(); len(got) != 2 {
		t.Fatalf("got %v", got)
	}
}

// A place outside the picture is a mistake, and nothing is sent for one.
func TestFocusAtRefusesAPlaceThatIsNotInThePicture(t *testing.T) {
	for _, at := range []string{"1.2,0.5", "-0.1,0.5", "banana", "0.5", ""} {
		phone, asked := zoomedPhone(t, `{"zoom":1}`)
		_, code := captureStdout(t, func() int { return run([]string{"focus", "at", at, "--url", phone.URL}) })
		if code == 0 {
			t.Errorf("%q should be refused", at)
		}
		if got := asked(); len(got) != 0 {
			t.Errorf("%q reached the phone: %v", at, got)
		}
	}
}

// A double tap on the console's live view is this command and nothing else.
func TestADoubleTapAtTheSeatIsTheClisFocusAt(t *testing.T) {
	state, server, _ := testConsole(t)
	phone, asked := zoomedPhone(t, `{"zoom":4,"cx":0.3,"cy":0.7}`)
	pairTo(state, phone.URL)
	code, body := post(t, server, "/api/op?do=focusat&fx=0.5&fy=0.5")
	if code != 200 || !strings.Contains(body, `"ok": true`) {
		t.Fatalf("got %d %s", code, body)
	}
	got := asked()
	if len(got) != 2 || !strings.Contains(strings.ReplaceAll(got[1], "%2C", ","), "focusbox=0.3000,0.7000") {
		t.Fatalf("got %v", got)
	}
	if code, _ := post(t, server, "/api/op?do=focusat&fx=7&fy=0.5"); code == 200 && len(asked()) != 2 {
		t.Error("a place outside the picture should not reach the phone")
	}
	// Newest first: the refused tap, then the one that worked. Both are written down.
	items := rollFrom(journalDir(), rollLimit, sessionGap)[0].Sessions[0].Items
	if len(items) != 2 {
		t.Fatalf("want two rows, got %+v", items)
	}
	if items[0].Ok || !strings.Contains(items[0].Error, "not a place in the picture") {
		t.Errorf("the refused tap is %+v", items[0])
	}
	if !items[1].Ok || items[1].Via != "console" || !strings.Contains(items[1].Command, "focus at 0.5,0.5") {
		t.Errorf("the tap that worked is %+v", items[1])
	}
}
