package main

import (
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"strings"
)

// thumbWidth is how wide a tile is in the console's roll.
const thumbWidth = 320

// thumbQuality trades a few kilobytes for a tile that still reads at a glance.
const thumbQuality = 82

// thumbFor is the name of the thumbnail that belongs to a capture.
func thumbFor(capture string) string {
	return strings.TrimSuffix(capture, filepath.Ext(capture)) + ".thumb.jpg"
}

func isJPEG(path string) bool {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".jpg", ".jpeg":
		return true
	}
	return false
}

// writeThumb makes the roll's tile out of the capture's own bytes.
//
// It used to ask the phone for a fresh /api/frame?w=320 once the capture was already on
// disk, so the workstation would need no image library. The console then showed that
// second frame in place of the capture it was labelled with, and anything that moved in
// between - the board, the torch, the rotation - made the contact sheet describe a moment
// that is not the one in the file.
//
// Card 43 took exactly this mistake out of the sidecar, for metadata, and the comment
// explaining why is twenty lines above this one in sidecar.go. image/jpeg is in the
// standard library and the downscale below is a box filter, so the round trip was not
// even buying anything.
func writeThumb(capture string) error {
	return writeThumbTo(capture, thumbFor(capture))
}

// writeThumbTo is writeThumb with the tile's place named, for the journal's own copy.
func writeThumbTo(capture, out string) error {
	if !isJPEG(capture) {
		// A DNG is not decodable here, and roll() lists only JPEGs, so there is nothing
		// for a thumbnail of one to appear in.
		return nil
	}
	f, err := os.Open(capture)
	if err != nil {
		return err
	}
	full, err := jpeg.Decode(f)
	closeErr := f.Close()
	if err != nil {
		return fmt.Errorf("%s is not a JPEG this build can read: %w", filepath.Base(capture), err)
	}
	if closeErr != nil {
		return closeErr
	}

	// Written under another name and moved into place, so the console never serves half a
	// thumbnail to a page that is refreshing its roll every three seconds.
	temp := out + ".part"
	w, err := os.Create(temp)
	if err != nil {
		return err
	}
	err = jpeg.Encode(w, downscale(full, thumbWidth), &jpeg.Options{Quality: thumbQuality})
	if closeErr := w.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(temp)
		return err
	}
	if err := os.Rename(temp, out); err != nil {
		_ = os.Remove(temp)
		return err
	}
	return nil
}

// downscale averages each block of source pixels into one destination pixel.
//
// A box filter, which is the right one here: the source is several thousand pixels wide
// and the destination is 320, so every destination pixel covers a block of tens of source
// pixels and averaging them is both the cheapest answer and the least aliased one. It
// never enlarges; a capture already narrower than the target is handed back untouched.
func downscale(src image.Image, width int) image.Image {
	b := src.Bounds()
	if b.Dx() <= width || b.Dx() == 0 || b.Dy() == 0 {
		return src
	}
	height := b.Dy() * width / b.Dx()
	if height < 1 {
		height = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		y0, y1 := span(b.Min.Y, b.Dy(), y, height)
		for x := 0; x < width; x++ {
			x0, x1 := span(b.Min.X, b.Dx(), x, width)
			var sumR, sumG, sumB, n uint64
			for sy := y0; sy < y1; sy++ {
				for sx := x0; sx < x1; sx++ {
					r, g, bl, _ := src.At(sx, sy).RGBA()
					sumR += uint64(r)
					sumG += uint64(g)
					sumB += uint64(bl)
					n++
				}
			}
			// RGBA() reports sixteen bits a channel; the shift takes the top eight.
			dst.SetRGBA(x, y, color.RGBA{
				R: uint8(sumR / n >> 8), G: uint8(sumG / n >> 8), B: uint8(sumB / n >> 8), A: 255,
			})
		}
	}
	return dst
}

// span is the half-open range of source pixels that one destination pixel covers. It is
// never empty: a destination wider than its source would otherwise average nothing.
func span(min, size, i, count int) (int, int) {
	lo := min + i*size/count
	hi := min + (i+1)*size/count
	if hi <= lo {
		hi = lo + 1
	}
	return lo, hi
}
