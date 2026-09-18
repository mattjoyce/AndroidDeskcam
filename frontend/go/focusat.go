package main

import (
	"fmt"
	"math"
	"net/url"
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

	crop, code := cropNow(in)
	if code != 0 {
		return code
	}
	box := fmt.Sprintf("focusbox=%.4f,%.4f,%.4f,%.4f",
		crop.left+at[0]*crop.w, crop.top+at[1]*crop.w, focusSpot*crop.w, focusSpot*crop.w)
	// The record says what was sent, in the sensor's coordinates. A fraction of the picture
	// means something only at the framing of that moment, and the journal outlives it.
	in.query = in.with(box)
	return printSummary(in, "/api/af", in.query)
}

// seen is the part of the sensor the picture shows, in the coordinates cx, cy, focusbox and
// mark all use. The picture is square in these coordinates: one width serves both axes, as
// it does on the phone.
type seen struct{ left, top, w float64 }

// cropNow asks the phone how it is framed and works out the crop as the phone does,
// clamped at the edge of the sensor. It is the one copy of that sum on the workstation.
func cropNow(in *invocation) (seen, int) {
	status, err := in.client.GetJSON("/api/status", "")
	if err != nil {
		return seen{}, failWith(err)
	}
	settings := sub(status, "settings")
	w := 1 / math.Max(1, number(settings, "zoom", 1))
	return seen{
		left: clamp(number(settings, "cx", 0.5)-w/2, 0, 1-w),
		top:  clamp(number(settings, "cy", 0.5)-w/2, 0, 1-w),
		w:    w,
	}, 0
}

// markAt puts a mark on a place in the picture you can see: a point, or a box given by its
// centre and its size, all as fractions of the picture.
//
//	deskcam mark at 0.3,0.6                 a point
//	deskcam mark at 0.3,0.6,0.2,0.1 label="this cap"
//
// A mark points and changes nothing. A double tap points too, but it moves the lens, which
// is the wrong thing to do to a camera somebody else is about to use. The mark is kept on
// the phone, where any client can read it at /api/marks, and the command is journalled,
// which is where an agent waiting on deskcam log wait sees it.
func markAt(in *invocation) int {
	words := strings.Split(in.arg(1), ",")
	if len(words) != 2 && len(words) != 4 {
		return fail("usage: deskcam mark at FX,FY[,FW,FH] [label=TEXT] [by=WORD]   fractions of the picture you can see")
	}
	at := make([]float64, len(words))
	for i, word := range words {
		v, err := strconv.ParseFloat(strings.TrimSpace(word), 64)
		if err != nil || math.IsNaN(v) || v < 0 || v > 1 {
			return fail("%q is not a place in the picture. Each number runs from 0 to 1.", word)
		}
		at[i] = v
	}
	crop, code := cropNow(in)
	if code != 0 {
		return code
	}
	mark := fmt.Sprintf("mark=%.4f,%.4f", crop.left+at[0]*crop.w, crop.top+at[1]*crop.w)
	if len(at) == 4 {
		mark += fmt.Sprintf(",%.4f,%.4f", at[2]*crop.w, at[3]*crop.w)
	}
	// The words of a label are free text, and the client sends a query as it was typed. A
	// space in one reached the phone raw and was answered 400.
	var rest []string
	for _, pair := range strings.Split(in.query, "&") {
		if k, v, ok := strings.Cut(pair, "="); ok {
			rest = append(rest, k+"="+url.QueryEscape(v))
		}
	}
	// As in focusAt: the journal records what was sent, which is where an agent reads the
	// place from.
	in.query = strings.Join(append([]string{mark}, rest...), "&")
	reply, err := in.client.GetJSON("/api/marks", in.query)
	if err != nil {
		return failWith(err)
	}
	fmt.Printf("marked, %v on the phone\n", reply["count"])
	return 0
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
