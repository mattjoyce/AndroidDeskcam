package main

import (
	"encoding/json"
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
}

func (e *HTTPError) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("HTTP %d from %s", e.Status, e.Path)
	}
	return fmt.Sprintf("HTTP %d\n  %s", e.Status, e.Message)
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
	reply, err := c.GetStream(path, query, func(r io.Reader) error {
		var readErr error
		buf, readErr = io.ReadAll(r)
		return readErr
	})
	if reply != nil {
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
	defer resp.Body.Close()

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
		os.Remove(out) // a failed capture must not leave a truncated file looking like one
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
