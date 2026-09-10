package main

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// solidJPEG is a picture of one colour, as the phone would send it.
func solidJPEG(t *testing.T, w, h int, c color.RGBA) []byte {
	t.Helper()
	return jpegOf(t, w, h, func(int, int) color.RGBA { return c })
}

func jpegOf(t *testing.T, w, h int, at func(x, y int) color.RGBA) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.SetRGBA(x, y, at(x, y))
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 92}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// meanAt is the average colour of a region, which is how a thumbnail is compared with the
// capture it claims to be of. JPEG is lossy and the downscale averages, so nothing here
// can compare pixels.
func meanAt(img image.Image, r image.Rectangle) color.RGBA {
	var sr, sg, sb, n uint64
	for y := r.Min.Y; y < r.Max.Y; y++ {
		for x := r.Min.X; x < r.Max.X; x++ {
			cr, cg, cb, _ := img.At(x, y).RGBA()
			sr += uint64(cr)
			sg += uint64(cg)
			sb += uint64(cb)
			n++
		}
	}
	if n == 0 {
		return color.RGBA{}
	}
	return color.RGBA{uint8(sr / n >> 8), uint8(sg / n >> 8), uint8(sb / n >> 8), 255}
}

func mean(img image.Image) color.RGBA { return meanAt(img, img.Bounds()) }

func near(t *testing.T, what string, got, want color.RGBA, tolerance int) {
	t.Helper()
	off := func(a, b uint8) int {
		if a > b {
			return int(a - b)
		}
		return int(b - a)
	}
	if off(got.R, want.R) > tolerance || off(got.G, want.G) > tolerance ||
		off(got.B, want.B) > tolerance {
		t.Fatalf("%s is %v, want about %v", what, got, want)
	}
}

func decodeFile(t *testing.T, path string) image.Image {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	img, err := jpeg.Decode(f)
	if err != nil {
		t.Fatalf("%s did not decode: %v", filepath.Base(path), err)
	}
	return img
}

// The claim of card 54. The thumbnail must be made from the bytes of the capture it names,
// not from a second request that describes a later moment.
//
// The phone here is a camera pointed at something that changes: /api/still hands back a
// red frame, and every later frame is blue. The old writeThumb fetched /api/frame?w=320
// after the capture was on disk, so the roll would show blue for a picture that is red.
func TestTheThumbnailIsTheCaptureAndNotALaterFrame(t *testing.T) {
	red := color.RGBA{R: 220, G: 30, B: 30, A: 255}
	blue := color.RGBA{R: 30, G: 30, B: 220, A: 255}
	capturedFrame := solidJPEG(t, 1200, 900, red)
	laterFrame := solidJPEG(t, 320, 240, blue)

	askedForALaterFrame := false
	phone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/still":
			w.Header().Set("X-DeskCam-Provenance", `{"settings":{"zoom":1}}`)
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(capturedFrame)
		case "/api/frame":
			askedForALaterFrame = true
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(laterFrame)
		default:
			_, _ = fmt.Fprint(w, "{}")
		}
	}))
	defer phone.Close()

	shots := t.TempDir()
	out := filepath.Join(shots, "deskcam-test.jpg")
	cfg := Config{URL: phone.URL, Shots: shots, Timeout: defaultTestTimeout}
	in := &invocation{command: "snap", cfg: cfg, client: NewClient(cfg), out: out}
	if code := capture(in, "/api/still", "jpg"); code != 0 {
		t.Fatalf("the capture failed with %d", code)
	}

	if askedForALaterFrame {
		t.Error("the thumbnail must come from the capture, not from a second request")
	}
	thumb := decodeFile(t, thumbFor(out))
	near(t, "the thumbnail", mean(thumb), red, 16)
	if thumb.Bounds().Dx() != thumbWidth {
		t.Errorf("the thumbnail is %d wide, want %d", thumb.Bounds().Dx(), thumbWidth)
	}
	if thumb.Bounds().Dy() != thumbWidth*900/1200 {
		t.Errorf("the thumbnail is %d tall, which is not the capture's shape",
			thumb.Bounds().Dy())
	}
}

// A thumbnail that averaged the whole capture into one colour would pass a test that only
// checks the mean. This one has a picture in it.
func TestTheThumbnailKeepsWhatIsWhereInTheCapture(t *testing.T) {
	left := color.RGBA{R: 230, G: 40, B: 40, A: 255}
	right := color.RGBA{R: 40, G: 40, B: 230, A: 255}
	shots := t.TempDir()
	out := filepath.Join(shots, "two-tone.jpg")
	body := jpegOf(t, 1000, 500, func(x, _ int) color.RGBA {
		if x < 500 {
			return left
		}
		return right
	})
	if err := os.WriteFile(out, body, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeThumb(out); err != nil {
		t.Fatal(err)
	}
	thumb := decodeFile(t, thumbFor(out))
	b := thumb.Bounds()
	// The middle of each half, away from the seam JPEG smears.
	near(t, "the left of the thumbnail",
		meanAt(thumb, image.Rect(b.Dx()/8, 0, b.Dx()*3/8, b.Dy())), left, 16)
	near(t, "the right of the thumbnail",
		meanAt(thumb, image.Rect(b.Dx()*5/8, 0, b.Dx()*7/8, b.Dy())), right, 16)
}

func TestASmallCaptureIsNotBlownUpIntoAThumbnail(t *testing.T) {
	shots := t.TempDir()
	out := filepath.Join(shots, "small.jpg")
	if err := os.WriteFile(out, solidJPEG(t, 200, 150, color.RGBA{G: 200, A: 255}), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeThumb(out); err != nil {
		t.Fatal(err)
	}
	if got := decodeFile(t, thumbFor(out)).Bounds().Dx(); got != 200 {
		t.Fatalf("a 200 pixel capture became a %d pixel thumbnail", got)
	}
}

// A DNG cannot be decoded here and roll() never lists one, so there is nothing for a
// thumbnail of it to appear in. It must not be an error either: raw is a normal capture.
func TestARawCaptureGetsNoThumbnailAndNoComplaint(t *testing.T) {
	out := filepath.Join(t.TempDir(), "shot.dng")
	if err := os.WriteFile(out, []byte("II*\x00 not really a DNG"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeThumb(out); err != nil {
		t.Fatalf("a raw capture must not fail: %v", err)
	}
	if _, err := os.Stat(thumbFor(out)); !os.IsNotExist(err) {
		t.Fatal("a DNG should not get a thumbnail")
	}
}

// A capture that is not a JPEG under a .jpg name is worth a word, and must leave nothing
// behind that the console would serve as a thumbnail.
func TestAnUndecodableCaptureLeavesNoThumbnail(t *testing.T) {
	out := filepath.Join(t.TempDir(), "broken.jpg")
	if err := os.WriteFile(out, []byte("not a JPEG at all"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeThumb(out); err == nil {
		t.Fatal("an undecodable capture should say so")
	}
	for _, leftover := range []string{thumbFor(out), thumbFor(out) + ".part"} {
		if _, err := os.Stat(leftover); !os.IsNotExist(err) {
			t.Errorf("%s was left behind", filepath.Base(leftover))
		}
	}
}
