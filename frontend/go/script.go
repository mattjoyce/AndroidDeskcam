package main

import (
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// scriptCommand submits a tape and unpacks the stream that comes back.
//
// The answer is one multipart/mixed stream: a JSON event per step, and each capture's
// pixels as the part after its own event. That shape is what keeps the phone free of
// storage, which the phone deliberately has none of. The alternative, an events-only stream
// naming files on the phone, would have needed a working directory, a cleanup policy, a
// listing endpoint and a download endpoint before the first script ran. Card 57.
func scriptCommand(in *invocation) int {
	if in.arg(0) != "run" || in.arg(1) == "" {
		return fail("usage: deskcam script run FILE [-o DIR]\n" +
			"  one verb per line, '#' starts a comment; deskcam api lists the verbs")
	}
	tape, err := os.ReadFile(in.arg(1))
	if err != nil {
		return fail("%v", err)
	}

	dir := in.out
	if dir == "" {
		dir = filepath.Join(in.cfg.Shots,
			"deskcam-script-"+time.Now().Format("20060102-150405"))
	}

	// A tape is many captures with waits between them, so it outlives the ordinary
	// per-request budget for the same reason a walk does.
	client := in.client
	if os.Getenv("DESKCAM_TIMEOUT") == "" {
		running := in.cfg
		running.Timeout = 30 * time.Minute
		client = NewClient(running)
	}

	made := false
	written := 0
	finished := false
	ok := false

	_, err = client.PostStream("/api/script", in.query, "text/plain", tape,
		func(resp *http.Response) error {
			boundary, err := boundaryOf(resp)
			if err != nil {
				return err
			}
			parts := multipart.NewReader(resp.Body, boundary)
			var pending map[string]any
			for {
				part, err := parts.NextPart()
				if err == io.EOF {
					return nil
				}
				if err != nil {
					return err
				}
				name := filepath.Base(part.FileName())
				if name == "." || name == ".." || name == "" {
					// A JSON event. It arrives before the pixels it describes, so it is
					// held to become the sidecar of whatever follows.
					body, err := io.ReadAll(io.LimitReader(part, 1<<22))
					if err != nil {
						return err
					}
					var event map[string]any
					if err := json.Unmarshal(body, &event); err != nil {
						return fmt.Errorf("the phone sent an event that is not JSON: %w", err)
					}
					if done, is := event["done"].(bool); is && done {
						finished = true
						ok = flag(event, "ok")
					}
					printEvent(event)
					pending = event
					continue
				}
				if !made {
					if err := os.MkdirAll(dir, 0o755); err != nil {
						return err
					}
					made = true
				}
				// A part name is still a path, however well the phone behaves. Same
				// reasoning as the burst and the walk.
				if err := writePart(filepath.Join(dir, name), part, pending); err != nil {
					return err
				}
				written++
			}
		})
	if err != nil {
		fmt.Fprintln(os.Stderr, "deskcam: script failed")
		return failWith(err)
	}

	if written > 0 {
		fmt.Println(dir)
	}
	if !finished {
		// The stream ended without a final event, so the tape did not run to its end and
		// nobody said why. A script that half ran must never look like one that finished.
		fmt.Fprintln(os.Stderr, "deskcam: the stream ended before the script did; "+
			"the camera may be part way through the tape")
		return 1
	}
	if !ok {
		return 1
	}
	return 0
}

// writePart puts one capture on disk, with the event that describes it beside it.
//
// Each file gets the whole event as its sidecar rather than a second /api/status request,
// which is the same rule every other capture in this tool follows: the record comes from
// the reply that carried the pixels, so it cannot describe a later moment.
func writePart(path string, body io.Reader, event map[string]any) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	_, err = io.Copy(f, body)
	if closeErr := f.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	// A part that is already a record needs no record beside it. walk.json is the one:
	// its sidecar name would be its own name, and it would overwrite the manifest.
	if strings.EqualFold(filepath.Ext(path), ".json") {
		return nil
	}
	record := recordFor(event, filepath.Base(path))
	if record == nil {
		return nil
	}
	record["image"] = filepath.Base(path)
	record["from"] = "the capture itself"
	out, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(sidecarPath(path), append(out, '\n'), 0o644)
}

// recordFor picks the part of a step's result that describes this one file.
//
// A capture verb has one record and it is the whole result. A walk has one per frame,
// inside the manifest, because every frame of a walk differs in the thing the walk exists
// to vary and a single record for the set would lose exactly that. Same rule the tar
// unpacker follows for walk.json.
func recordFor(event map[string]any, name string) map[string]any {
	result, _ := event["result"].(map[string]any)
	if result == nil {
		return nil
	}
	frames, _ := result["frames"].([]any)
	if frames == nil {
		return result
	}
	// The phone prefixes each name with its step number; the manifest names the file as
	// the archive holds it.
	bare := name
	if _, after, found := strings.Cut(name, "-"); found {
		bare = after
	}
	for _, f := range frames {
		frame, _ := f.(map[string]any)
		if frame != nil && str(frame, "file") == bare {
			return frame
		}
	}
	return nil
}

// printEvent is one line per step, which is what a script is for: watching it happen.
func printEvent(event map[string]any) {
	switch {
	case flag(event, "started"):
		verbs, _ := event["verbs"].([]any)
		words := make([]string, 0, len(verbs))
		for _, v := range verbs {
			words = append(words, valueString(v))
		}
		fmt.Printf("%s steps: %s\n", str(event, "steps"), strings.Join(words, " "))
		return
	case flag(event, "done"):
		if flag(event, "ok") {
			fmt.Printf("done: %s of %s steps in %s\n", str(event, "completed"),
				str(event, "steps"), seconds(event))
			return
		}
		fmt.Fprintf(os.Stderr, "deskcam: the script stopped at step %s (line %s, %s): %s\n",
			str(event, "failed_at"), str(event, "line"), str(event, "verb"),
			str(event, "error"))
		if restored := sub(event, "restored"); restored != nil {
			fmt.Fprintln(os.Stderr, "deskcam: the camera was put back to: "+
				summarise(map[string]any{"settings": restored}))
		}
		return
	}

	line := fmt.Sprintf("  %2s %-11s", str(event, "step"), str(event, "verb"))
	if !flag(event, "ok") {
		// The line says which step, and the reason goes to stderr with the rest of the
		// failure. Results on stdout, problems on stderr, the same as everywhere else
		// here: printing a two hundred character reason on both is worse than either.
		fmt.Println(line + "FAILED")
		return
	}
	switch {
	case event["waited_ms"] != nil:
		fmt.Println(line + str(event, "waited_ms") + " ms")
	case event["files"] != nil:
		files, _ := event["files"].([]any)
		names := make([]string, 0, len(files))
		for _, f := range files {
			names = append(names, valueString(f))
		}
		fmt.Println(line + strings.Join(names, " "))
		// A walk warns about anything the camera was still deciding for itself, and a
		// warning nobody reads is not a warning. It went into walk.json and nowhere else
		// once before, which is how the bracket's note about digital gain stayed invisible.
		for _, warning := range warningsIn(sub(event, "result")) {
			fmt.Fprintln(os.Stderr, "deskcam: "+warning)
		}
	case sub(event, "result")["diopters"] != nil:
		// A hunt answers with a curve, not with the camera state, so the one-line
		// summary of the camera would print a row of empty fields.
		hunt := sub(event, "result")
		fmt.Printf("%schose %s d, sharpness %s, contrast %s, %s readings\n", line,
			str(hunt, "diopters"), str(hunt, "sharpness"), str(hunt, "contrast"),
			str(hunt, "readings"))
	default:
		fmt.Println(line + summarise(sub(event, "result")))
	}
}

func warningsIn(result map[string]any) []string {
	raw, _ := result["warnings"].([]any)
	out := make([]string, 0, len(raw))
	for _, w := range raw {
		out = append(out, valueString(w))
	}
	return out
}

func seconds(event map[string]any) string {
	ms, ok := num(event, "millis")
	if !ok {
		return "?"
	}
	return fmt.Sprintf("%.1f s", ms/1000)
}

// boundaryOf reads the part separator the phone chose, rather than assuming it.
func boundaryOf(resp *http.Response) (string, error) {
	kind, params, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if err != nil {
		return "", fmt.Errorf("the phone sent an unreadable content type: %w", err)
	}
	if !strings.HasPrefix(kind, "multipart/") || params["boundary"] == "" {
		return "", fmt.Errorf("a script answers with multipart, and this one said %q", kind)
	}
	return params["boundary"], nil
}
