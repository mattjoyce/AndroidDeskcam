package main

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// keyedPhone wants a key on everything, and keeps what reached it.
func keyedPhone(t *testing.T, key string) (*httptest.Server, func() []string) {
	t.Helper()
	var mu sync.Mutex
	var asked []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		mu.Lock()
		asked = append(asked, r.Method+" "+r.URL.Path+"?"+r.URL.RawQuery+" "+string(body))
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Query().Get("token") != key {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = fmt.Fprint(w, `{"ok":false,"error":"wrong or missing token"}`)
			return
		}
		if r.URL.Query().Get("zoom") == "banana" {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = fmt.Fprint(w, `{"ok":false,"error":"bad value for 'zoom': 'banana'"}`)
			return
		}
		w.Header().Set("X-DeskCam-Thermal", "none")
		_, _ = fmt.Fprint(w, `{"ok":true,"settings":{"zoom":2}}`)
	}))
	t.Cleanup(server.Close)
	return server, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), asked...)
	}
}

// ask sends what a browser would, with the headers a test names.
func ask(t *testing.T, server *httptest.Server, method, path, body string, headers map[string]string) (int, string, http.Header) {
	t.Helper()
	req, err := http.NewRequest(method, server.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	for k, v := range headers {
		if k == "Host" {
			req.Host = v
		} else {
			req.Header.Set(k, v)
		}
	}
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	raw, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(raw), resp.Header
}

var fromThePage = map[string]string{consoleHeader: "1", "Sec-Fetch-Site": "same-origin"}

// The console serves the bench tool's own page, and the page talks to /api as it does on
// the phone. The console answers for the phone and puts the key on, which the browser never
// holds. Decision D20.
func TestThePagesRequestsReachThePhoneWithTheKeyOn(t *testing.T) {
	state, server, _ := testConsole(t)
	setToken(t, "the-key")
	phone, asked := keyedPhone(t, "the-key")
	pairTo(state, phone.URL)

	code, body, headers := ask(t, server, http.MethodGet, "/api/set?zoom=2&token=guessed", "", fromThePage)
	if code != http.StatusOK || !strings.Contains(body, `"zoom":2`) {
		t.Fatalf("got %d %s", code, body)
	}
	if headers.Get("X-DeskCam-Thermal") != "none" {
		t.Error("the phone's own headers should come through")
	}
	got := asked()
	if len(got) != 1 || !strings.Contains(got[0], "zoom=2") || !strings.Contains(got[0], "token=the-key") ||
		strings.Contains(got[0], "guessed") {
		t.Fatalf("the phone should get the console's key and never the caller's, got %v", got)
	}
	// A tape is a POST with a body, and it goes through whole.
	ask(t, server, http.MethodPost, "/api/script", "SET zoom=2\nSTILL\n", fromThePage)
	if got := asked(); len(got) != 2 || !strings.HasPrefix(got[1], "POST /api/script") || !strings.Contains(got[1], "STILL") {
		t.Fatalf("got %v", got)
	}
}

// The reason this is dangerous. The phone's API is all GET, and the console puts the key
// on. So without a guard, any website open in the operator's browser could point an <img>
// at 127.0.0.1 and change the camera with a key it never saw. Every one of these must be
// refused before the phone hears anything.
func TestAWebsiteCannotReachThePhoneThroughTheConsole(t *testing.T) {
	state, server, _ := testConsole(t)
	setToken(t, "the-key")
	phone, asked := keyedPhone(t, "the-key")
	pairTo(state, phone.URL)

	for name, c := range map[string]struct {
		method, path string
		headers      map[string]string
	}{
		"an <img> on another site":                                  {"GET", "/api/set?torch=45", map[string]string{"Sec-Fetch-Site": "cross-site"}},
		"an <img> from an old browser that sends no fetch metadata": {"GET", "/api/set?torch=45", nil},
		"a cross-site fetch that somehow carried the header": {"GET", "/api/set?torch=45",
			map[string]string{consoleHeader: "1", "Sec-Fetch-Site": "cross-site"}},
		"another port on this machine": {"GET", "/api/set?torch=45",
			map[string]string{consoleHeader: "1", "Sec-Fetch-Site": "same-site"}},
		"a cross-site form":                   {"POST", "/api/script", map[string]string{"Sec-Fetch-Site": "cross-site"}},
		"a still in an <img> on another site": {"GET", "/api/still", map[string]string{"Sec-Fetch-Site": "cross-site"}},
		// DNS rebinding: a site whose name now resolves to 127.0.0.1 IS same-origin to the
		// browser and can set any header it likes. The Host it sends is still its own.
		"a rebound name": {"GET", "/api/set?torch=45",
			map[string]string{consoleHeader: "1", "Sec-Fetch-Site": "same-origin", "Host": "evil.example:9000"}},
		"a path that is not an endpoint": {"GET", "/api/..%2f..%2fadmin", fromThePage},
	} {
		if code, body, _ := ask(t, server, c.method, c.path, "", c.headers); code == http.StatusOK {
			t.Errorf("%s was served: %s", name, body)
		}
	}
	if got := asked(); len(got) != 0 {
		t.Fatalf("the phone must hear none of that, heard %v", got)
	}
	if got := statusFromLAN(t, state, "/api/set?torch=45"); got != http.StatusForbidden {
		t.Errorf("the network got %d", got)
	}
}

// A picture can be shown or saved without the header, because a link and an <img> cannot
// send one. It changes nothing on the camera, and it is still refused from another site.
func TestAPictureNeedsNoHeaderFromThisPage(t *testing.T) {
	state, server, _ := testConsole(t)
	phone, asked := keyedPhone(t, "")
	pairTo(state, phone.URL)
	code, _, _ := ask(t, server, http.MethodGet, "/api/still?w=800", "", map[string]string{"Sec-Fetch-Site": "same-origin"})
	if code != http.StatusOK || len(asked()) != 1 {
		t.Fatalf("got %d, phone heard %v", code, asked())
	}
}

// What a person does to the camera from the console is written down like anything else,
// so an agent waiting on deskcam log wait sees a reframe as well as a mark. Looking is not.
func TestWhatThePageChangesIsJournalledAndWhatItReadsIsNot(t *testing.T) {
	state, server, _ := testConsole(t)
	phone, _ := keyedPhone(t, "")
	pairTo(state, phone.URL)
	ask(t, server, http.MethodGet, "/api/status", "", fromThePage)
	ask(t, server, http.MethodGet, "/api/marks", "", fromThePage)
	ask(t, server, http.MethodGet, "/api/set?zoom=4&cx=0.3", "", fromThePage)
	ask(t, server, http.MethodGet, "/api/marks?mark=0.3,0.6&label=here", "", fromThePage)
	ask(t, server, http.MethodGet, "/api/set?zoom=banana", "", fromThePage)

	entries := readJournal(journalDir(), 20)
	if len(entries) != 3 {
		t.Fatalf("want the two sets and the mark, got %d: %+v", len(entries), entries)
	}
	if entries[0].Operation != "set" || entries[0].Query != "zoom=4&cx=0.3" || str(entries[0].Asker, "via") != "console" {
		t.Errorf("the set is %+v", entries[0])
	}
	if entries[1].Operation != "marks" || !entries[1].Ok {
		t.Errorf("the mark is %+v", entries[1])
	}
	if entries[2].Ok || !strings.Contains(entries[2].Error, "banana") {
		t.Errorf("the refusal should be in the phone's words, got %+v", entries[2])
	}
}

func TestThePageIsToldWhenThereIsNoPhone(t *testing.T) {
	_, server, _ := testConsole(t)
	code, body, _ := ask(t, server, http.MethodGet, "/api/status", "", fromThePage)
	if code != http.StatusBadGateway || !strings.Contains(body, "no phone paired") {
		t.Fatalf("got %d %s", code, body)
	}
}
