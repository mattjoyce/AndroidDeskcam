// Command deskcam controls the DeskCam bench camera over HTTP.
//
// One static binary: the command line and the local console, with no runtime behind it.
// Measuring what is in a picture still needs the Python tools in frontend/analysis/,
// because that is array maths over image data and NumPy's job. Taking a picture needs
// nothing. Card 53.
package main

import (
	"archive/tar"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func main() { os.Exit(run(os.Args[1:])) }

func fail(format string, a ...any) int {
	fmt.Fprintf(os.Stderr, "deskcam: "+format+"\n", a...)
	return 1
}

// failWith reports an error and, when the phone's status code says what to do about it,
// says that too.
func failWith(err error) int {
	fmt.Fprintf(os.Stderr, "deskcam: %v\n", err)
	if next := advice(err); next != "" {
		fmt.Fprintf(os.Stderr, "  %s\n", next)
	}
	return 1
}

type invocation struct {
	command string
	args    []string // bare positional words
	query   string   // k=v words, joined
	out     string
	cfg     Config
	client  *Client
}

func (in *invocation) arg(i int) string {
	if i < len(in.args) {
		return in.args[i]
	}
	return ""
}

// with returns the request query with extra parameters in front of the caller's, so an
// explicit k=v on the command line always wins.
func (in *invocation) with(extra string) string {
	switch {
	case extra == "":
		return in.query
	case in.query == "":
		return extra
	default:
		return extra + "&" + in.query
	}
}

func run(argv []string) int {
	if len(argv) == 0 {
		usage()
		return 0
	}

	in := &invocation{command: argv[0]}
	var urlFlag string
	rest := argv[1:]
	for i := 0; i < len(rest); i++ {
		switch a := rest[i]; {
		case a == "-o" || a == "--out":
			if i+1 >= len(rest) {
				return fail("%s needs a value", a)
			}
			i++
			in.out = rest[i]
		case a == "--url":
			if i+1 >= len(rest) {
				return fail("--url needs a value")
			}
			i++
			urlFlag = rest[i]
		case a == "-h" || a == "--help":
			usage()
			return 0
		// A camera parameter is name=value where the name is lowercase letters. Anything
		// beginning with a dash is a flag meant for something else, usually the Python
		// measurement tools, and routing it into the query dropped it silently and then
		// warned that it was not a camera parameter. `analyse scale f.jpg --pitch-mm=5`
		// lost the pitch; the space-separated form worked, which is why testing missed it.
		case strings.Contains(a, "=") && !strings.HasPrefix(a, "-"):
			if in.query != "" {
				in.query += "&"
			}
			in.query += a
		default:
			in.args = append(in.args, a)
		}
	}

	in.cfg = loadConfig(urlFlag)
	in.client = NewClient(in.cfg)

	if code := in.checkParams(); code != 0 {
		return code
	}

	switch in.command {
	case "help", "--help", "-h":
		usage()
		return 0

	// ------------------------------------------------------------- captures
	case "snap", "still", "shot":
		return capture(in, "/api/still", "jpg")
	case "frame", "preview":
		return capture(in, "/api/frame", "jpg")
	case "raw", "dng":
		return capture(in, "/api/raw", "dng")
	case "burst":
		return burst(in)
	case "stream":
		return stream(in)

	// ---------------------------------------------------------------- state
	case "status":
		return printJSON(in, "/api/status", in.query)
	case "show":
		return printSummary(in, "/api/status", in.query)
	case "set":
		if in.query == "" {
			return fail("usage: deskcam set key=value [...]")
		}
		return printSummary(in, "/api/set", in.query)
	case "reset":
		return printSummary(in, "/api/reset", in.query)
	case "recall":
		if in.arg(0) == "" {
			return fail("usage: deskcam recall FILE.json")
		}
		q, err := recallQuery(in.arg(0))
		if err != nil {
			return fail("%v", err)
		}
		return printSummary(in, "/api/set", in.with("reset=1&"+q))

	// -------------------------------------------------------------- aiming
	case "zoom":
		if in.arg(0) == "" {
			return fail("usage: deskcam zoom N")
		}
		return printSummary(in, "/api/set", in.with("zoom="+in.arg(0)))
	case "center", "centre":
		return printSummary(in, "/api/set", in.with("cx=0.5&cy=0.5"))
	case "pan":
		return pan(in)
	case "af", "autofocus":
		return printSummary(in, "/api/af", in.query)
	case "focus":
		if in.arg(0) == "" {
			return fail("usage: deskcam focus METRES|auto")
		}
		if in.arg(0) == "auto" {
			return printSummary(in, "/api/set", in.with("af=continuous&focus=auto"))
		}
		return printSummary(in, "/api/set", in.with("focusm="+in.arg(0)))
	case "exposure", "shutter":
		if in.arg(0) == "" {
			return fail("usage: deskcam exposure 1/120|8ms|250us")
		}
		return printSummary(in, "/api/set", in.with("exposure="+in.arg(0)))
	case "iso":
		if in.arg(0) == "" {
			return fail("usage: deskcam iso N")
		}
		return printSummary(in, "/api/set", in.with("iso="+in.arg(0)))
	case "auto":
		return printSummary(in, "/api/set", in.with("ae=on&af=continuous&focus=auto"))
	case "torch", "light":
		if in.arg(0) == "" {
			return fail("usage: deskcam torch 0-45|off|max")
		}
		return printSummary(in, "/api/set", in.with("torch="+in.arg(0)))

	// ----------------------------------------------------------- discovery
	case "cameras":
		return printJSON(in, "/api/cameras", in.query)
	case "api":
		return printJSON(in, "/api/help", "")
	case "which":
		fmt.Println(in.cfg.URL)
		return 0
	case "open":
		fmt.Println(in.cfg.URL)
		_ = exec.Command("xdg-open", in.cfg.URL).Start()
		return 0

	// ----------------------------------------------------- measurement
	case "aatest":
		return aatest(in)
	case "analyse", "analysis":
		if len(in.args) == 0 {
			return fail("usage: deskcam analyse scale|linearity|burst-noise|aatest ...")
		}
		return runAnalysis(in.args)

	// ------------------------------------------------------------- console
	case "serve", "console":
		port := 9000
		if in.arg(0) != "" {
			n, err := strconv.Atoi(in.arg(0))
			if err != nil {
				return fail("port must be a number, got %q", in.arg(0))
			}
			port = n
		}
		return serve(in.cfg, port)
	case "token":
		return token(in)

	// -------------------------------------------------------------- target
	case "use":
		if in.arg(0) == "" {
			return fail("usage: deskcam use http://host:8080")
		}
		if err := writeConfig(urlFile(), strings.TrimRight(in.arg(0), "/")); err != nil {
			return fail("%v", err)
		}
		fmt.Println("target:", loadConfig("").URL)
		return 0
	case "usb":
		return usb(in)
	case "wifi":
		return wifi(in)
	case "start", "stop":
		return service(in)
	}

	usage()
	return 1
}

// checkParams reads the generated table before anything reaches the network.
//
// The phone is the authority on what it accepts, so an unfamiliar name is a warning and
// still gets sent: an older binary against a newer phone must not refuse a parameter that
// works. A camera parameter aimed at /api/stream is different. That one is refused here,
// because the phone will refuse it anyway and a local message names the fix without a
// round trip. Decision D10.
func (in *invocation) checkParams() int {
	if in.query == "" {
		return 0
	}
	var refused []string
	for _, pair := range strings.Split(in.query, "&") {
		name, _, found := strings.Cut(pair, "=")
		if !found {
			continue
		}
		name = strings.ToLower(strings.TrimSpace(name))
		kind, known := Params[name]
		switch {
		case !known:
			fmt.Fprintf(os.Stderr, "deskcam: %q is not a parameter this build knows; "+
				"sending it anyway, the phone decides. Try deskcam api.\n", name)
		case in.command == "stream" && kind == Camera:
			refused = append(refused, name)
		}
	}
	if len(refused) > 0 {
		return fail("a stream is a view, so it does not take %s. Send %s to deskcam set, "+
			"then start the stream.", strings.Join(refused, ", "),
			map[bool]string{true: "them", false: "it"}[len(refused) > 1])
	}
	return 0
}

// ------------------------------------------------------------------ captures

func timestamped(dir, ext string) string {
	return filepath.Join(dir, "deskcam-"+time.Now().Format("20060102-150405")+"."+ext)
}

func capture(in *invocation, path, ext string) int {
	out := in.out
	if out == "" {
		out = timestamped(in.cfg.Shots, ext)
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return fail("%v", err)
	}
	reply, err := in.client.GetFile(path, in.query, out)
	if err != nil {
		return failWith(err)
	}
	if err := writeSidecar(out, reply, in.client, in.cfg.URL); err != nil {
		fmt.Fprintln(os.Stderr, "deskcam: could not write the sidecar:", err)
	}
	if err := writeThumb(out); err != nil {
		// A thumbnail is a convenience. Its absence must never fail a capture, but it is
		// made from a file that is right here, so a failure is worth a word.
		fmt.Fprintln(os.Stderr, "deskcam: could not write the thumbnail:", err)
	}
	fmt.Println(out)
	return 0
}

func burst(in *invocation) int {
	n := 8
	if in.arg(0) != "" {
		parsed, err := strconv.Atoi(in.arg(0))
		if err != nil || parsed < 1 {
			return fail("burst needs a frame count, got %q", in.arg(0))
		}
		n = parsed
	}
	dir := in.out
	if dir == "" {
		dir = filepath.Join(in.cfg.Shots, "deskcam-burst-"+time.Now().Format("20060102-150405"))
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fail("cannot make %s: %v", dir, err)
	}

	// A full-sensor DNG is about 24 MB, so a burst of them is taken one request at a time
	// rather than as one archive that will not fit in the phone's heap.
	if strings.Contains(in.query, "format=raw") {
		q := strings.ReplaceAll(in.query, "format=raw", "")
		if strings.Trim(q, "&") != "" {
			if _, err := in.client.Get("/api/set", q); err != nil {
				return fail("%v", err)
			}
		}
		for i := 0; i < n; i++ {
			name := filepath.Join(dir, fmt.Sprintf("burst-%03d.dng", i))
			if _, err := in.client.GetFile("/api/raw", "", name); err != nil {
				return fail("raw burst failed at frame %d: %v", i, err)
			}
		}
		fmt.Println(dir)
		return 0
	}

	written := 0
	reply, err := in.client.GetStream("/api/burst", in.with(fmt.Sprintf("n=%d", n)),
		func(r io.Reader) error {
			archive := tar.NewReader(r)
			for {
				header, err := archive.Next()
				if err == io.EOF {
					return nil
				}
				if err != nil {
					return err
				}
				if header.Typeflag != tar.TypeReg {
					continue
				}
				// The phone names these burst-000.jpg and nothing else, but an archive is
				// still untrusted input and a name is still a path.
				name := filepath.Base(header.Name)
				if name == "." || name == ".." || name == "" {
					continue
				}
				f, err := os.Create(filepath.Join(dir, name))
				if err != nil {
					return err
				}
				_, err = io.Copy(f, archive)
				if closeErr := f.Close(); err == nil {
					err = closeErr
				}
				if err != nil {
					return err
				}
				written++
			}
		})
	if err != nil {
		fmt.Fprintln(os.Stderr, "deskcam: burst failed")
		return failWith(err)
	}

	// A burst that ran out of time answers 206 with fewer frames than were asked for.
	// Writing the directory in silence would leave an average of six frames looking
	// exactly like an average of sixteen. Card 40.
	if got, ok := reply.headerInt("X-DeskCam-Frames"); ok && got < n {
		fmt.Fprintf(os.Stderr, "deskcam: short burst, %d of %d frames (HTTP %d)\n",
			got, n, reply.Status)
	}
	if err := writeSidecar(filepath.Join(dir, "burst.jpg"), reply, in.client, in.cfg.URL); err != nil {
		fmt.Fprintln(os.Stderr, "deskcam: could not write the sidecar:", err)
	}
	fmt.Println(dir)
	return 0
}

func stream(in *invocation) int {
	out := in.out
	if out == "" {
		out = filepath.Join(in.cfg.Shots, "deskcam-stream.mjpg")
	}
	q := in.query
	if !strings.Contains(q, "n=") {
		q = in.with("n=30")
	}
	if _, err := in.client.GetFile("/api/stream", q, out); err != nil {
		return fail("stream failed: %v", err)
	}
	fmt.Println(out)
	return 0
}

func pan(in *invocation) int {
	if in.arg(0) == "" {
		return fail("usage: deskcam pan up|down|left|right [amount]")
	}
	amount := in.arg(1)
	if amount == "" {
		amount = "0.25"
	}
	var p string
	switch in.arg(0) {
	case "left":
		p = "dx=-" + amount
	case "right":
		p = "dx=" + amount
	case "up":
		p = "dy=-" + amount
	case "down":
		p = "dy=" + amount
	default:
		return fail("direction must be up, down, left or right")
	}
	return printSummary(in, "/api/set", in.with(p))
}

// aatest takes two captures with identical settings, then measures what differs between
// them. That difference is the noise of the instrument, and a measurement smaller than it
// means nothing. Card 35.
func aatest(in *invocation) int {
	dir := in.out
	if dir == "" {
		dir = in.cfg.Shots
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fail("cannot make %s: %v", dir, err)
	}
	if !strings.Contains(in.query, "exposure=") && !strings.Contains(in.query, "iso=") &&
		!strings.Contains(in.query, "ae=") {
		fmt.Fprintln(os.Stderr, "deskcam: note, no exposure given; fix exposure= and iso= "+
			"for a floor you can compare against")
	}
	stamp := time.Now().Format("20060102-150405")
	var shots []string
	for _, suffix := range []string{"a", "b"} {
		name := filepath.Join(dir, "aatest-"+stamp+"-"+suffix+".jpg")
		reply, err := in.client.GetFile("/api/still", in.query, name)
		if err != nil {
			return fail("%s capture failed: %v", suffix, err)
		}
		if err := writeSidecar(name, reply, in.client, in.cfg.URL); err != nil {
			return fail("could not write the sidecar: %v", err)
		}
		shots = append(shots, name)
	}
	return runAnalysis([]string{"aatest", shots[0], shots[1], "--write", dir})
}

// -------------------------------------------------------------------- output

func printJSON(in *invocation, path, query string) int {
	reply, err := in.client.Get(path, query)
	if err != nil {
		return failWith(err)
	}
	_, _ = os.Stdout.Write(reply.Body)
	if len(reply.Body) > 0 && reply.Body[len(reply.Body)-1] != '\n' {
		fmt.Println()
	}
	return 0
}

func printSummary(in *invocation, path, query string) int {
	status, err := in.client.GetJSON(path, query)
	if err != nil {
		return failWith(err)
	}
	fmt.Println(summarise(status))
	if note := str(status, "note"); note != "" {
		fmt.Fprintln(os.Stderr, "deskcam: "+note)
	}
	return 0
}
