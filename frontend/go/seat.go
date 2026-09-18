package main

import (
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// The console is the agent's seat. Decision D19.
//
// A person there does what an agent does: a still, a focus hunt, the measurement mode, a
// capture taken again from its record. Each is the CLI's own command run through operate(),
// so it goes to the phone by the same code, is refused for the same reasons, leaves the
// same sidecar, and is written in the same journal, marked as having come from the console.
//
// It does not aim. There is no zoom, pan or rotate here and no way to send a parameter of
// the caller's choosing, because aiming belongs to the bench tool on the phone (D18) and
// the console's own copy of it is how the two pages drifted apart. The one exception is
// "again", whose parameters come from a record and not from the caller.

// One operation at a time. There is one camera, and what a command said when it failed is
// kept in one place for the length of a command.
var seatMu sync.Mutex

type seatStep struct {
	Operation string   `json:"operation"`
	Ok        bool     `json:"ok"`
	Error     string   `json:"error,omitempty"`
	Files     []string `json:"files,omitempty"`
}

func (s *consoleState) handleOp(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	phone, token, _ := s.snapshot()
	why := strings.TrimSpace(q.Get("why"))

	var steps [][]string // each a command and its words, as they would be typed
	switch q.Get("do") {
	case "snap":
		steps = [][]string{{"snap"}}
	case "hunt":
		steps = [][]string{{"focus", "hunt"}}
	case "focusat":
		// A double tap on the live view. The place is a fraction of the picture, and the
		// CLI's own command turns it into a focus box, so there is one copy of that sum.
		steps = [][]string{{"focus", "at", q.Get("fx") + "," + q.Get("fy")}}
	case "measure":
		steps = [][]string{{"set", "measure=1"}}
	case "normal":
		steps = [][]string{{"set", "measure=0"}}
	case "again":
		file, ok := journalledFile(s.journal, q.Get("id"), q.Get("n"))
		if !ok || file.Sidecar == nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false,
				"error": "there is no record of that capture to shoot it again from"})
			return
		}
		recall := recallFrom(file.Sidecar)
		if recall == "" {
			writeJSON(w, http.StatusUnprocessableEntity, map[string]any{"ok": false,
				"error": "that record holds no settings to recall"})
			return
		}
		set := append([]string{"set", "reset=1"}, strings.Split(recall, "&")...)
		steps = [][]string{set, {"snap"}}
		if why == "" {
			why = "again, from " + file.Path
		}
	default:
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false,
			"error": "the console offers snap, hunt, focusat, measure, normal and again. " +
				"Aiming is on the phone's own page."})
		return
	}
	if phone == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "no phone paired"})
		return
	}

	seatMu.Lock()
	defer seatMu.Unlock()
	cfg := Config{URL: phone, Token: token, Shots: s.shots, Timeout: 60 * time.Second}
	done := []seatStep{}
	ok := true
	for _, words := range steps {
		in := &invocation{command: words[0], cfg: cfg, client: NewClient(cfg)}
		for _, word := range words[1:] {
			if strings.Contains(word, "=") {
				if in.query != "" {
					in.query += "&"
				}
				in.query += word
			} else {
				in.args = append(in.args, word)
			}
		}
		in.who = whoAsked(s.shots, words, why, "console", os.Getenv)
		code, said := operate(in)
		done = append(done, seatStep{Operation: strings.Join(words, " "), Ok: code == 0,
			Error: said, Files: in.produced})
		if code != 0 {
			// A recall that failed must not be followed by a still of something else.
			ok = false
			break
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": ok, "steps": done})
}
