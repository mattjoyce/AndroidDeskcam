package main

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
)

// The scale record the measurement tools write beside the captures. Card 23.
const scaleFile = "deskcam-scale.json"

// scaleRecord is one measured scale and the geometry it was measured in.
//
// Written by `deskcam scale`, which is the Python side, and read here. The fields are its
// fields; this is the reading end of one file and not a second definition of it.
type scaleRecord struct {
	PxPerMM    float64        `json:"px_per_mm"`
	PitchMM    float64        `json:"pitch_mm"`
	WidthPx    int            `json:"width_px"`
	HeightPx   int            `json:"height_px"`
	Settings   map[string]any `json:"settings"`
	MeasuredAt string         `json:"measured_at"`
	Image      string         `json:"image"`
	Interval   []float64      `json:"interval"`
}

func readScale(dir string) *scaleRecord {
	raw, err := os.ReadFile(filepath.Join(dir, scaleFile))
	if err != nil {
		return nil
	}
	var record scaleRecord
	if json.Unmarshal(raw, &record) != nil || record.PxPerMM <= 0 {
		return nil
	}
	return &record
}

// framing is what a scale depends on, out of everything a capture records.
//
// Zoom and pan choose the piece of the sensor, rotate turns it, and the camera is which
// lens it came out of. Change any of them and the millimetres behind a pixel change with
// it. Exposure, focus mode and white balance do not appear here, on purpose: they change
// what the picture looks like and not how big anything in it is.
var framing = []string{"camera", "zoom", "cx", "cy", "rotate"}

// scaleFor decides whether a recorded scale describes this capture, and says why not.
//
// The one thing it cannot check is the one that matters most. A scale is a distance as
// much as it is a setting, and nothing in this system can see the stand move. So this
// answers a narrower question than it looks like it answers, and the note it writes into
// the sidecar says which question that was.
func scaleFor(record *scaleRecord, settings map[string]any, width, height int) map[string]any {
	if record == nil {
		return nil
	}
	out := map[string]any{"measured_from": record.Image, "measured_at": record.MeasuredAt}
	refuse := func(why string) map[string]any {
		out["applies"] = false
		out["why"] = why
		return out
	}

	for _, key := range framing {
		was, now := record.Settings[key], settings[key]
		if !sameSetting(was, now) {
			return refuse(fmt.Sprintf("the scale was measured at %s %v and this capture is "+
				"at %s %v", key, plain(was), key, plain(now)))
		}
	}
	if record.WidthPx < 1 || record.HeightPx < 1 || width < 1 || height < 1 {
		return refuse("the pixel size of one of the two captures is not known")
	}

	// Same framing, different sampling. A still and a preview frame of the same view hold
	// the same millimetres in a different number of pixels, so the scale converts by the
	// width ratio. Only when the shape matches: a different aspect ratio is a different
	// field of view, whatever the settings say.
	wasAspect := float64(record.WidthPx) / float64(record.HeightPx)
	nowAspect := float64(width) / float64(height)
	if math.Abs(wasAspect-nowAspect)/wasAspect > 0.01 {
		return refuse(fmt.Sprintf("the scale was measured on a %dx%d image and this one is "+
			"%dx%d, which is a different shape and so a different view",
			record.WidthPx, record.HeightPx, width, height))
	}

	ratio := float64(width) / float64(record.WidthPx)
	out["applies"] = true
	out["px_per_mm"] = record.PxPerMM * ratio
	if len(record.Interval) == 2 {
		out["interval"] = []float64{record.Interval[0] * ratio, record.Interval[1] * ratio}
	}
	if ratio == 1 {
		out["note"] = "the framing is unchanged since the scale was measured. This cannot " +
			"know whether the camera has moved closer or further away."
	} else {
		out["note"] = fmt.Sprintf("the framing is unchanged since the scale was measured, "+
			"and it is converted from %d pixels wide to %d. This cannot know whether the "+
			"camera has moved closer or further away.", record.WidthPx, width)
	}
	return out
}

// sameSetting compares two values out of two sidecars, which arrive as JSON and so as
// float64, string or nil. Numbers compare with a tolerance because 2 and 2.0000001 are the
// same zoom, and a difference that small cannot change a scale by anything measurable.
func sameSetting(a, b any) bool {
	af, aok := a.(float64)
	bf, bok := b.(float64)
	if aok && bok {
		return math.Abs(af-bf) <= 1e-6*math.Max(1, math.Abs(af))
	}
	if aok != bok {
		return false
	}
	return fmt.Sprint(plain(a)) == fmt.Sprint(plain(b))
}

// plain prints a JSON number the way a person wrote it, so a message says "zoom 2" and
// not "zoom 2e+00".
func plain(v any) any {
	if f, ok := v.(float64); ok {
		if f == math.Trunc(f) {
			return int64(f)
		}
	}
	if v == nil {
		return "not recorded"
	}
	return v
}
