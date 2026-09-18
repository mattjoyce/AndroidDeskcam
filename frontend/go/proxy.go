package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

// The console answers /api for the phone. Decision D20.
//
// The console serves the bench tool's own page, the one file the phone serves, and that
// page talks to /api/... as it does on the phone. Here those requests are forwarded, with
// the access key put on, because the browser never holds the key (card 29).
//
// That makes this the most dangerous route in the console. The phone's API is all GET, and
// a GET can be fired by anything: an <img> on any website open in the operator's browser
// reaches 127.0.0.1 as easily as this page does. Forwarding it with the key on would let a
// site that never saw the key change the camera. So a request is forwarded only when all of
// these hold:
//
//  1. It came from this machine (the local wrapper on the route).
//  2. Its Host is this machine by address or as localhost. A site whose name has been
//     rebound to 127.0.0.1 is same-origin as far as the browser can tell and can set any
//     header it likes, and the Host it sends is still its own name.
//  3. The browser does not say it came from somewhere else. Sec-Fetch-Site is set by the
//     browser and a page cannot forge it. same-origin is this page; none is an address
//     typed by the operator. cross-site and same-site (another port here) are refused.
//  4. It carries the console's header, which a form, an <img> and a link cannot send and a
//     cross-origin fetch cannot send without a preflight nobody answers. This is what
//     protects a browser too old to send Sec-Fetch-Site.
//
// A picture is excused from the fourth, because an <img> and a download link cannot carry a
// header, and showing a picture changes nothing on the camera. It is held to the other
// three, so another site still cannot show itself the bench.

// An endpoint is one lowercase word. Nothing that could be a path gets as far as the phone.
var phoneEndpoint = regexp.MustCompile(`^/api/[a-z]+$`)

// Pictures: asked for by <img> and by links, which cannot send a header.
var pictures = map[string]bool{"still": true, "frame": true, "raw": true}

// What changes the camera or takes a picture with it, and so is written in the journal.
// marks is here only when it adds or removes one; reading the marks is a poll.
var changes = map[string]bool{
	"set": true, "reset": true, "af": true, "focushunt": true, "still": true, "raw": true,
	"burst": true, "bracket": true, "walk": true, "focussweep": true, "script": true,
}

// phoneClient waits as long as the slowest thing the phone does. A walk answers only when
// every frame is taken, and the phone's own limit for one is 300 seconds.
var phoneClient = &http.Client{
	Transport: &http.Transport{
		ResponseHeaderTimeout: 310 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	},
}

func refuse(w http.ResponseWriter, code int, why string) {
	writeJSON(w, code, map[string]any{"ok": false, "error": why})
}

// thisMachine says whether a Host header names this machine and not some other name that
// happens to resolve here.
func thisMachine(host string) bool {
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	return ip != nil && ip.IsLoopback()
}

func (s *consoleState) handlePhone(w http.ResponseWriter, r *http.Request) {
	if !phoneEndpoint.MatchString(r.URL.Path) {
		refuse(w, http.StatusNotFound, "no such endpoint")
		return
	}
	name := strings.TrimPrefix(r.URL.Path, "/api/")
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		refuse(w, http.StatusMethodNotAllowed, "the phone's API is GET, and POST for a script")
		return
	}
	if !thisMachine(r.Host) {
		refuse(w, http.StatusForbidden, "this console answers to 127.0.0.1 and localhost, not to "+r.Host)
		return
	}
	if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" && site != "none" {
		refuse(w, http.StatusForbidden, "this request came from another site, and the console "+
			"does not reach the phone for one")
		return
	}
	picture := pictures[name] && r.Method == http.MethodGet
	if !picture && r.Header.Get(consoleHeader) != "1" {
		refuse(w, http.StatusForbidden, "missing "+consoleHeader+"; this request did not come "+
			"from the console's page")
		return
	}

	phone, token, _ := s.snapshot()
	if phone == "" {
		refuse(w, http.StatusBadGateway, "no phone paired")
		return
	}

	// The caller's key, if it sent one, is dropped. Only the console's goes to the phone.
	// The rest goes as it was written: re-encoding it sorts it, and the journal should say
	// what the page asked for in the order it asked.
	var kept []string
	for _, pair := range strings.Split(r.URL.RawQuery, "&") {
		if name, _, _ := strings.Cut(pair, "="); pair != "" && !strings.EqualFold(name, "token") {
			kept = append(kept, pair)
		}
	}
	asked := strings.Join(kept, "&")
	if token != "" {
		kept = append(kept, "token="+url.QueryEscape(token))
	}
	target := phone + r.URL.Path
	if len(kept) > 0 {
		target += "?" + strings.Join(kept, "&")
	}
	out, err := http.NewRequestWithContext(r.Context(), r.Method, target, r.Body)
	if err != nil {
		refuse(w, http.StatusBadGateway, err.Error())
		return
	}
	if ct := r.Header.Get("Content-Type"); ct != "" {
		out.Header.Set("Content-Type", ct)
	}

	started := time.Now()
	resp, err := phoneClient.Do(out)
	if err != nil {
		s.journalPage(name, r, asked, started, false, "the phone did not answer: "+err.Error())
		refuse(w, http.StatusBadGateway, "the phone did not answer: "+err.Error())
		return
	}
	defer func() { _ = resp.Body.Close() }()

	for key, values := range resp.Header {
		if key == "Content-Type" || key == "Content-Disposition" || key == "Cache-Control" ||
			strings.HasPrefix(key, "X-Deskcam-") {
			for _, v := range values {
				w.Header().Add(key, v)
			}
		}
	}
	var body io.Reader = resp.Body
	said := ""
	if resp.StatusCode >= 400 {
		// A refusal is small, and its words are what the journal is for.
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
		var doc map[string]any
		if json.Unmarshal(raw, &doc) == nil {
			said = str(doc, "error")
		}
		if said == "" {
			said = strings.TrimSpace(string(raw))
		}
		body = bytes.NewReader(raw)
	}
	w.WriteHeader(resp.StatusCode)
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 32<<10)
	for {
		n, readErr := body.Read(buf)
		if n > 0 {
			if _, err := w.Write(buf[:n]); err != nil {
				break
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if readErr != nil {
			break
		}
	}
	s.journalPage(name, r, asked, started, resp.StatusCode < 400, said)
}

// journalPage writes down what the page did to the camera, so that a person's reframe at
// the console is in the same record as an agent's, and an agent waiting on deskcam log wait
// sees it. Reading is not written down: the page polls status and the marks every two
// seconds.
func (s *consoleState) journalPage(name string, r *http.Request, query string, started time.Time, ok bool, said string) {
	q := r.URL.Query()
	marking := name == "marks" && (q.Get("mark") != "" || q.Get("unmark") != "")
	if !changes[name] && !marking {
		return
	}
	if s.journal == "" {
		return
	}
	line := r.Method + " " + r.URL.Path
	if query != "" {
		line += "?" + query
	}
	who := whoAsked(s.shots, nil, "", "console", os.Getenv)
	who.Command = keyInCommand.ReplaceAllString(line, "token=***")
	decoded, err := url.QueryUnescape(query)
	if err != nil {
		decoded = query
	}
	code := 0
	if !ok {
		code = 1
	}
	entry := journalEntry{
		At:        started.UTC().Format(time.RFC3339Nano),
		Millis:    time.Since(started).Milliseconds(),
		Operation: name,
		Query:     keyInCommand.ReplaceAllString(decoded, "token=***"),
		Ok:        ok,
		ExitCode:  code,
		Error:     said,
		Asker:     who.block(),
	}
	if phone, _, _ := s.snapshot(); phone != "" {
		entry.Target = phone
	}
	if writeJournal(s.journal, entry, nil) == nil {
		pruneJournal(s.journal, journalCap)
	}
}
