package main

import (
	"fmt"
	"math"
	"strconv"
	"strings"
)

// focusSpot is the side of the focus box as a fraction of the picture's width. The phone's
// page uses the same figure for a double tap, so a tap there and a command here judge focus
// over the same patch.
const focusSpot = 0.09

// focusAt focuses on a place in the picture you can see.
//
//	deskcam focus at 0.3,0.6
//
// The place is a fraction of the visible picture, left to right and top to bottom. An agent
// that has just looked at a frame knows where the soft part is in those terms and no
// others. Turning it into a focus box means knowing the crop, which depends on the zoom
// and on how the phone clamps a crop at the edge of the sensor. That sum lived in the
// phone's page, in JavaScript. It is here once so the CLI and the console's double tap
// cannot disagree, and the framing is left alone because the focus box is not the crop
// (D17). Rotation is the phone's business: cx, cy and focusbox are all in the coordinates
// of the picture as seen, and Geom.roi maps them back.
func focusAt(in *invocation) int {
	words := in.args[1:]
	if len(words) == 1 {
		words = strings.Split(words[0], ",")
	}
	if len(words) != 2 {
		return fail("usage: deskcam focus at FX,FY   each from 0 to 1, a place in the picture you can see")
	}
	var at [2]float64
	for i, word := range words {
		v, err := strconv.ParseFloat(strings.TrimSpace(word), 64)
		if err != nil || math.IsNaN(v) || v < 0 || v > 1 {
			return fail("%q is not a place in the picture. FX and FY each run from 0 to 1.", word)
		}
		at[i] = v
	}

	status, err := in.client.GetJSON("/api/status", "")
	if err != nil {
		return failWith(err)
	}
	settings := sub(status, "settings")
	zoom := math.Max(1, number(settings, "zoom", 1))
	w := 1 / zoom
	left := clamp(number(settings, "cx", 0.5)-w/2, 0, 1-w)
	top := clamp(number(settings, "cy", 0.5)-w/2, 0, 1-w)
	box := fmt.Sprintf("focusbox=%.4f,%.4f,%.4f,%.4f",
		left+at[0]*w, top+at[1]*w, focusSpot*w, focusSpot*w)
	return printSummary(in, "/api/af", in.with(box))
}

// number reads a value the phone may have sent as null, which is how it reports a centre
// that has never been set.
func number(m map[string]any, key string, otherwise float64) float64 {
	if v, ok := m[key].(float64); ok {
		return v
	}
	return otherwise
}

func clamp(v, lo, hi float64) float64 {
	return math.Min(math.Max(v, lo), hi)
}
