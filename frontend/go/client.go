package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// Client talks to the phone. Every operation is a GET with query parameters, which is
// decision D3, so there is very little here.
type Client struct {
	base    string
	token   string
	timeout time.Duration
	http    *http.Client
}

func NewClient(c Config) *Client {
	return &Client{
		base:    c.URL,
		token:   c.Token,
		timeout: c.Timeout,
		http:    &http.Client{Timeout: c.Timeout},
	}
}

// Reply is one answer from the phone, kept whole so a caller can read its headers.
type Reply struct {
	Status  int
	Body    []byte
	Header  http.Header
	Elapsed time.Duration
}

// Provenance is the record of the frame, on the response that carried the frame.
func (r *Reply) Provenance() []byte {
	v := r.Header.Get("X-DeskCam-Provenance")
	if v == "" {
		return nil
	}
	return []byte(v)
}

func (r *Reply) headerInt(name string) (int, bool) {
	v := r.Header.Get(name)
	if v == "" {
		return 0, false
	}
	var n int
	if _, err := fmt.Sscanf(v, "%d", &n); err != nil {
		return 0, false
	}
	return n, true
}

// HTTPError carries the reason the phone gave. curl -f used to discard exactly this, so
// the message rule R5 goes to the trouble of writing never reached a person.
type HTTPError struct {
	Status  int
	Message string
	Path    string
	Err     error // the transport failure underneath, when there was one
}

func (e *HTTPError) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("HTTP %d from %s", e.Status, e.Path)
	}
	return fmt.Sprintf("HTTP %d\n  %s", e.Status, e.Message)
}

// Unwrap keeps anything underneath reachable through errors.Is.
func (e *HTTPError) Unwrap() error { return e.Err }

// advice turns a status code into the thing the operator should actually do next.
//
// The status is the only machine-readable fact the phone reports about a failure, and
// until now every one of them arrived as the same undifferentiated string, leaving a
// person to read English to tell "the key is wrong" from "the camera is not running".
// That is precisely what this project tells its own measurement tools not to do.
func advice(err error) string {
	var httpErr *HTTPError
	if !errors.As(err, &httpErr) {
		// Not an answer from the phone at all, so it never arrived.
		return "the phone did not answer. Check deskcam which, and deskcam start if the " +
			"service is not running."
	}
	switch httpErr.Status {
	case http.StatusUnauthorized:
		return "the phone expects a different access key. Pair again so it learns this " +
			"one, or clear it with deskcam token clear."
	case http.StatusServiceUnavailable:
		return "the phone is busy with as many requests as it will take at once. Try again."
	case http.StatusNotFound:
		return "this build asked for an endpoint the phone does not have. It may be " +
			"running an older APK."
	}
	if httpErr.Status >= 500 {
		return "the phone failed to serve this. Its own message is above; /api/status " +
			"will say whether the camera is running."
	}
	return "" // 4xx: the phone's own message already names the fix
}

func (c *Client) url(path, query string) string {
	q := strings.TrimPrefix(strings.TrimSpace(query), "&")
	if c.token != "" {
		if q != "" {
			q += "&"
		}
		q += "token=" + url.QueryEscape(c.token)
	}
	if q == "" {
		return c.base + path
	}
	return c.base + path + "?" + q
}

// Get performs one request and buffers the answer. A 4xx or 5xx becomes an HTTPError
// carrying the phone's own explanation, never a bare status code.
func (c *Client) Get(path, query string) (*Reply, error) {
	var buf []byte
	read := false
	reply, err := c.GetStream(path, query, func(r io.Reader) error {
		var readErr error
		buf, readErr = io.ReadAll(r)
		read = true
		return readErr
	})
	// Only when consume actually ran. On the error path GetStream has already put the
	// phone's explanation in reply.Body, and this used to overwrite it with a nil buffer
	// that consume never filled, so the Reply documented above as "kept whole" arrived
	// empty exactly when its contents mattered most.
	if reply != nil && read {
		reply.Body = buf
	}
	return reply, err
}

// GetStream hands the body to a reader function without holding it in memory.
//
// A burst of sixteen full-resolution frames is tens of megabytes, and the phone already
// went to the trouble of streaming it rather than buffering three copies. Undoing that on
// this side would be rude.
func (c *Client) GetStream(path, query string, consume func(io.Reader) error) (*Reply, error) {
	started := time.Now()
	resp, err := c.http.Get(c.url(path, query))
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	reply := &Reply{Status: resp.StatusCode, Header: resp.Header}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		// An error body is small and is the whole point of the reply, so it is read.
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
		reply.Body = body
		reply.Elapsed = time.Since(started)
		return reply, &HTTPError{Status: resp.StatusCode, Message: errorIn(body), Path: path}
	}
	if err := consume(resp.Body); err != nil {
		return reply, err
	}
	reply.Elapsed = time.Since(started)
	return reply, nil
}

// GetJSON is Get for the endpoints that answer with an object.
func (c *Client) GetJSON(path, query string) (map[string]any, error) {
	reply, err := c.Get(path, query)
	if err != nil {
		return nil, err
	}
	var out map[string]any
	if err := json.Unmarshal(reply.Body, &out); err != nil {
		return nil, fmt.Errorf("the phone sent something that is not JSON: %w", err)
	}
	return out, nil
}

// GetFile streams the body to a path, and hands back the reply so its headers survive.
func (c *Client) GetFile(path, query, out string) (*Reply, error) {
	f, err := os.Create(out)
	if err != nil {
		return nil, err
	}
	reply, err := c.GetStream(path, query, func(r io.Reader) error {
		_, copyErr := io.Copy(f, r)
		return copyErr
	})
	closeErr := f.Close()
	if err != nil {
		// A failed capture must not leave a truncated file looking like one. If even
		// the removal fails there is nothing further to try.
		_ = os.Remove(out)
		return reply, err
	}
	if closeErr != nil {
		return reply, closeErr
	}
	return reply, nil
}

// errorIn pulls the explanation out of an error body, whatever shape it arrived in.
func errorIn(body []byte) string {
	var doc map[string]any
	if err := json.Unmarshal(body, &doc); err == nil {
		if msg, ok := doc["error"].(string); ok {
			return msg
		}
	}
	text := strings.TrimSpace(string(body))
	if len(text) > 400 {
		text = text[:400] + "..."
	}
	return text
}
