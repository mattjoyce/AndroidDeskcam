package main

import (
	"encoding/json"
	"image/color"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func framedAt(zoom, cx, cy, rotate float64) map[string]any {
	return map[string]any{"camera": "0", "zoom": zoom, "cx": cx, "cy": cy, "rotate": rotate}
}

func aScale(width, height int) *scaleRecord {
	return &scaleRecord{
		PxPerMM:  16.4,
		PitchMM:  1.0,
		WidthPx:  width,
		HeightPx: height,
		Settings: framedAt(2, 0.5, 0.5, 180),
		Image:    "deskcam-first.jpg",
		Interval: []float64{16.3, 16.5},
	}
}

func TestAScaleTravelsToACaptureOfTheSameFraming(t *testing.T) {
	block := scaleFor(aScale(2016, 1512), framedAt(2, 0.5, 0.5, 180), 2016, 1512)
	if block["applies"] != true {
		t.Fatalf("the same framing should carry the scale, got %v", block)
	}
	if block["px_per_mm"] != 16.4 {
		t.Fatalf("the scale changed on the way, got %v", block["px_per_mm"])
	}
	if !strings.Contains(block["note"].(string), "moved closer") {
		t.Error("the note must say what it cannot know, which is the distance")
	}
}

// Every one of these changes how many millimetres are behind a pixel, and each has to say
// which one it was. "the scale no longer applies" is not a message anyone can act on.
func TestAScaleStopsAtAChangeOfFraming(t *testing.T) {
	for _, tc := range []struct {
		what     string
		settings map[string]any
		says     string
	}{
		{"zoom", framedAt(4, 0.5, 0.5, 180), "zoom"},
		{"pan across", framedAt(2, 0.3, 0.5, 180), "cx"},
		{"pan down", framedAt(2, 0.5, 0.7, 180), "cy"},
		{"rotation", framedAt(2, 0.5, 0.5, 0), "rotate"},
	} {
		block := scaleFor(aScale(2016, 1512), tc.settings, 2016, 1512)
		if block["applies"] != false {
			t.Errorf("a change of %s must stop the scale", tc.what)
			continue
		}
		why, _ := block["why"].(string)
		if !strings.Contains(why, tc.says) {
			t.Errorf("a change of %s should name it, got %q", tc.what, why)
		}
	}

	other := framedAt(2, 0.5, 0.5, 180)
	other["camera"] = "1"
	if block := scaleFor(aScale(2016, 1512), other, 2016, 1512); block["applies"] != false {
		t.Error("the other camera is a different lens and a different scale")
	}
}

// A still and a preview frame of the same view hold the same millimetres in a different
// number of pixels. That is a conversion, not a change of framing.
func TestAScaleConvertsBetweenAStillAndAPreviewFrame(t *testing.T) {
	block := scaleFor(aScale(2016, 1512), framedAt(2, 0.5, 0.5, 180), 1008, 756)
	if block["applies"] != true {
		t.Fatalf("half the pixels of the same view is still the same view, got %v", block)
	}
	if got := block["px_per_mm"].(float64); got != 8.2 {
		t.Fatalf("half the pixels is half the pixels per millimetre, got %v", got)
	}
	span := block["interval"].([]float64)
	if span[0] != 8.15 || span[1] != 8.25 {
		t.Fatalf("the interval must be converted with the value, got %v", span)
	}
	if !strings.Contains(block["note"].(string), "2016 pixels wide to 1008") {
		t.Errorf("the conversion should be stated, got %q", block["note"])
	}
}

// A different shape is a different field of view, whatever the settings say about it.
func TestAScaleRefusesADifferentShapeOfImage(t *testing.T) {
	block := scaleFor(aScale(2016, 1512), framedAt(2, 0.5, 0.5, 180), 2016, 1134)
	if block["applies"] != false {
		t.Fatal("4:3 and 16:9 of the same sensor are not the same view")
	}
	if !strings.Contains(block["why"].(string), "different shape") {
		t.Errorf("say what is wrong with it, got %q", block["why"])
	}
}

func TestNoRecordedScaleAddsNothingToASidecar(t *testing.T) {
	if block := scaleFor(nil, framedAt(1, 0.5, 0.5, 0), 100, 100); block != nil {
		t.Fatalf("a capture taken before any scale was measured should carry none, got %v", block)
	}
	if readScale(t.TempDir()) != nil {
		t.Fatal("an empty directory holds no scale")
	}
}

func TestARecordedScaleIsReadBackFromDisk(t *testing.T) {
	dir := t.TempDir()
	raw, err := json.Marshal(aScale(2016, 1512))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, scaleFile), raw, 0o644); err != nil {
		t.Fatal(err)
	}
	record := readScale(dir)
	if record == nil || record.PxPerMM != 16.4 || record.WidthPx != 2016 {
		t.Fatalf("the record did not survive the round trip: %+v", record)
	}

	// Junk on disk is not a reason to fail a capture. The scale is an extra.
	if err := os.WriteFile(filepath.Join(dir, scaleFile), []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	if readScale(dir) != nil {
		t.Error("a broken scale file must read as no scale, not as a scale")
	}
}

// The whole path, from the file beside the captures to the sidecar of the next one.
func TestACaptureCarriesTheScaleMeasuredBeforeIt(t *testing.T) {
	shots := t.TempDir()
	raw, err := json.Marshal(aScale(320, 240))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(shots, scaleFile), raw, 0o644); err != nil {
		t.Fatal(err)
	}
	image := filepath.Join(shots, "deskcam-second.jpg")
	if err := os.WriteFile(image, solidJPEG(t, 320, 240, color.RGBA{G: 180, A: 255}), 0o644); err != nil {
		t.Fatal(err)
	}

	provenance := map[string]any{"settings": framedAt(2, 0.5, 0.5, 180)}
	body, err := json.Marshal(provenance)
	if err != nil {
		t.Fatal(err)
	}
	reply := &Reply{Header: map[string][]string{"X-Deskcam-Provenance": {string(body)}}}
	if err := writeSidecar(image, reply, nil, "http://phone"); err != nil {
		t.Fatal(err)
	}

	written, err := os.ReadFile(sidecarPath(image))
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(written, &doc); err != nil {
		t.Fatal(err)
	}
	if doc["width_px"] != 320.0 || doc["height_px"] != 240.0 {
		t.Errorf("the sidecar should record the pixel size, got %v x %v",
			doc["width_px"], doc["height_px"])
	}
	block, ok := doc["scale"].(map[string]any)
	if !ok {
		t.Fatalf("the sidecar carries no scale: %s", written)
	}
	if block["applies"] != true || block["px_per_mm"] != 16.4 {
		t.Fatalf("the scale did not travel: %v", block)
	}
	if block["measured_from"] != "deskcam-first.jpg" {
		t.Errorf("the sidecar should say which capture the scale came from, got %v",
			block["measured_from"])
	}
}

// ------------------------------------------------------------- focus sweeps

// A sweep is the one capture where every frame differs in the thing the capture exists to
// vary, so one record for the set would lose exactly what was being recorded. Card 5.
func TestASweepGivesEveryFrameItsOwnSidecar(t *testing.T) {
	dir := t.TempDir()
	names := []string{"focus-00-3.000d.jpg", "focus-01-4.500d.jpg"}
	for _, name := range names {
		body := solidJPEG(t, 64, 48, color.RGBA{B: 120, A: 255})
		if err := os.WriteFile(filepath.Join(dir, name), body, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	manifest := `{"tool":"DeskCam","sweep":"focus","from_diopters":3,"to_diopters":4.5,
	  "frames":[
	    {"file":"focus-00-3.000d.jpg","focus_diopters_asked":3,
	     "measured":{"focus_diopters":3.0},"settings":{"zoom":1}},
	    {"file":"focus-01-4.500d.jpg","focus_diopters_asked":4.5,
	     "measured":{"focus_diopters":4.51},"settings":{"zoom":1}}]}`

	if err := splitWalk(dir, []byte(manifest)); err != nil {
		t.Fatal(err)
	}
	for i, name := range names {
		side := filepath.Join(dir, strings.TrimSuffix(name, ".jpg")+".json")
		raw, err := os.ReadFile(side)
		if err != nil {
			t.Fatalf("no sidecar for %s: %v", name, err)
		}
		var doc map[string]any
		if err := json.Unmarshal(raw, &doc); err != nil {
			t.Fatal(err)
		}
		if doc["image"] != name {
			t.Errorf("sidecar %d names %v, want %s", i, doc["image"], name)
		}
		if doc["width_px"] != 64.0 || doc["bytes"] == nil {
			t.Errorf("sidecar %d should record the file it describes: %v", i, doc)
		}
		measured, _ := doc["measured"].(map[string]any)
		if measured == nil || measured["focus_diopters"] == nil {
			t.Errorf("sidecar %d lost the focus of its own frame: %v", i, doc)
		}
	}
	// The set has properties no single frame has, so the manifest is kept as well.
	if _, err := os.Stat(filepath.Join(dir, walkManifest)); err != nil {
		t.Error("the sweep's own record should be kept beside the frames")
	}
}

func TestASweepManifestCannotWriteOutsideItsDirectory(t *testing.T) {
	dir := t.TempDir()
	manifest := `{"frames":[{"file":"../escaped.json"},{"file":"/etc/passwd"},{"file":""}]}`
	if err := splitWalk(dir, []byte(manifest)); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	// Only the manifest itself. Nothing was written for a name that is a path.
	if len(entries) != 1 || entries[0].Name() != walkManifest {
		t.Fatalf("a name that is a path must be skipped, got %v", entries)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dir), "escaped.json")); err == nil {
		t.Fatal("a sidecar escaped the sweep directory")
	}
}

func TestASweepWithNoManifestSaysSo(t *testing.T) {
	if err := splitWalk(t.TempDir(), nil); err == nil {
		t.Fatal("a walk that carried no record of itself should say so")
	}
	if err := splitWalk(t.TempDir(), []byte("{not json")); err == nil {
		t.Fatal("a record that is not JSON should say so")
	}
}

// The claim an exposure bracket makes, checked rather than assumed. Card 7.
func TestABracketSaysWhenAFrameMissedItsPeriod(t *testing.T) {
	const base = 4166667.0 // 1/240 s, one period of a 240 Hz panel

	// What the phone actually produced: near enough whole multiples for a merge.
	for _, got := range []float64{4147623, 8295246, 16633251, 33309261} {
		frame := map[string]any{
			"file":     "exposure-00.jpg",
			"measured": map[string]any{"exposure_ns": got, "exposure_human": "4.15ms"},
		}
		line := checkPeriods(frame, base)
		if frame["whole_periods"] != true {
			t.Errorf("%.0f ns is %v periods, which is close enough to whole",
				got, frame["base_periods"])
		}
		if strings.Contains(line, "duty cycle") {
			t.Errorf("a good frame should not be warned about: %q", line)
		}
		if !strings.Contains(line, "periods") {
			t.Errorf("every frame should report what it actually did, got %q", line)
		}
	}

	// What a base that is not the panel's period looks like: a third of a period out.
	frame := map[string]any{
		"file":     "exposure-00.jpg",
		"measured": map[string]any{"exposure_ns": 85500.0, "exposure_human": "85.5us"},
	}
	line := checkPeriods(frame, 125000)
	if frame["whole_periods"] != false {
		t.Fatal("0.684 of a period is not a whole number of them")
	}
	if !strings.Contains(line, "duty cycle") {
		t.Errorf("a frame that missed its period must say so, got %q", line)
	}
	if frame["period_error"] != 0.316 {
		t.Errorf("the error should be recorded for the merge, got %v", frame["period_error"])
	}
}

// A focus sweep has no base period, and must not be told it missed one.
func TestAWalkWithNoBasePeriodIsNotChecked(t *testing.T) {
	frame := map[string]any{"measured": map[string]any{"exposure_ns": 12345.0}}
	if line := checkPeriods(frame, 0); line != "" {
		t.Fatalf("a focus sweep has no periods to miss, got %q", line)
	}
	if _, ok := frame["base_periods"]; ok {
		t.Fatal("nothing should be recorded about periods that do not apply")
	}
}
