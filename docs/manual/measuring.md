# Measuring

When the question wants a number, such as "will this fit" or "how far apart are these holes",
the same camera measures, and refuses rather than guessing when it cannot support one. When
the question wants a description, none of this page applies. Read which one you were asked
for, and say which one you are giving.

## The tools

The measurement tools are Python, in `frontend/analysis/`. They read captures and sidecars off
disk and never talk to the camera, so they work on anything you photographed last month.
They need `numpy` and `pillow`, which taking a picture does not:

```sh
pip install -e '.[analysis]'
pip install -e '.[mat]'          # adds OpenCV, for finding the mat's markers
```

The CLI runs these with the first `python3` on your path, so install into that Python, or
activate the environment you installed into before running `deskcam`.

| Question | Command |
|---|---|
| How large must a difference be to be real? | `deskcam aatest` |
| How many pixels per millimetre, in this picture? | `deskcam scale FILE --pitch-mm 1.0` |
| How far apart are two points, in mm? | `deskcam measure FILE X1,Y1 X2,Y2` |
| Where is the mat, and has the bench moved? | `deskcam calibration FILE` |
| Does the pixel value track the light? | `deskcam analyse linearity DIR` |
| How much does averaging a burst help? | `deskcam analyse burst-noise DIR` |
| How sharp is what the camera sees? | `deskcam show sharpness=1` |

Each prints its value with an interval and a sample count. Add `--json` for the full record.

## Refusal is the feature

Each tool has a confidence measure and a limit. Below the limit it prints no number, says
why, and exits 2. A number with a caveat beside it gets quoted without the caveat, so the tools
do not print one.

| Tool | Confidence is | Refuses below | Why that limit |
|---|---|---|---|
| `scale` | autocorrelation peak height | 0.60 | correct strips measured 0.76 to 0.96, harmonic misreads about 0.33 |
| `linearity` | power-law fit R squared | 0.980 | both a linear response and an sRGB curve fit above 0.99 |
| `burst-noise` | interval tightness | 0.85 | the question turns on an 8% difference, so a wider interval decides nothing |
| `aatest` | fraction of pixels not pinned | 0.99 | a pixel at 0 or 255 records no difference and flatters the floor |
| `calibration` | markers found, their spread, the fit | 4 markers, 35% of the page each way, 1.0 mm worst corner | fewer or bunched markers describe one corner of the sheet, not the page |

Exit codes are 0 for a measurement, 2 for a refusal, and 1 for a tool that could not run.

## Start with the noise floor

Two captures of the same subject with the same settings differ by the noise of the
instrument. A difference smaller than that is the camera talking to itself.

```sh
deskcam aatest measure=1 iso=56 exposure=200ms
```

```
aa-test: 1.415 DN, n=68252, fraction of pixels not pinned 1.000
  a measurement of this scene must differ by more than 1.42 DN (1.38% of the level)
  before it is a difference and not this camera
```

The floor is recorded beside your captures as `deskcam-noisefloor.json`. `linearity` reads it
and refuses a series whose steps sit inside it. The other tools do not read it yet, so hold
their differences against the floor yourself.

## Measurement mode

`measure=1` puts the pipeline into an instrument state:

* noise reduction, edge sharpening, hot pixel correction and chromatic aberration correction
  are off
* the tone curve is linear
* optical image stabilisation is locked, so the lens does not move on a still mount
* white balance gains are locked

The picture looks dark and flat, which is correct. The sidecar's `pipeline` block says what
was actually applied, and with `measure=1` every entry reads `off`. `deskcam status` has the
same block for the preview. Leave `measure` off for anything a person is meant to look at.

## Size: scale and distance

### Scale from a rule

Pixels per millimetre change every time the stand moves, so they are never a camera
specification. Measure them in the picture you are reporting on:

1. Put a steel rule or 1 mm graph paper in the frame, flat, in the plane of what you will
   measure.
2. Take the picture, and run `deskcam scale` on it:
   ```sh
   deskcam scale shot.jpg --pitch-mm 1.0 --region 0.365,0.41,0.66,0.05
   ```
   This writes `deskcam-scale.json` beside the captures.
3. Measure between two pixel positions of that capture:
   ```sh
   deskcam measure shot.jpg 412,308 1190,306
   # 47.4 mm (95% 47.3 to 47.5)
   ```

Every capture taken after a `scale` carries it in its sidecar while the framing holds. Change
the zoom, the pan, the rotation or the camera, and the sidecar says which one changed rather
than going quiet:

```json
"scale": {"applies": false, "measured_from": "deskcam-20260910-124151.jpg",
          "why": "the scale was measured at zoom 2 and this capture is at zoom 4"}
```

A still and a preview of the same view are the same field sampled into different numbers of
pixels, so the scale converts between them by the width ratio and says so.
`deskcam analyse scale` is the same measurement without recording it.

**If more than one regular pattern is in frame, the tool says so, and you choose.** Its first
run on this bench locked onto graph paper, was told it was looking at millimetres, and
reported a scale five times too large, with 107 strips agreeing and a healthy correlation.
`--region` points it at the right one.

**The interval is precision, not accuracy.** On one setup on 2026-09-10 the rule gave
16.42 px/mm (95% 16.38 to 16.46, 40 strips) and the graph paper in the same frame gave
16.54 px/mm (95% 16.52 to 16.57, 37 strips). The intervals do not overlap. For better than
about 2%, use calipers.

**Level the camera first.** A tilted camera stretches one side of a flat subject. On one
setup, 13.8 degrees off gravity made the scale vary by 11% across a single sheet, and
levelling to 1.55 degrees brought that under 2%. Treat those figures as indicative, from
one bench. `deskcam status` reports the tilt, and [the level](console.md#levelling-the-mount)
sets it.

### Distance to the subject

`focus_metres_approx` in a sidecar, and the dioptres a hunt chooses, are the lens's own
estimate, which the camera itself labels `APPROXIMATE`. They are not a ruler. On 2026-09-12
the bench phone reported 0.314 m where the distance from the scale was 255 to 283 mm, which
is 15 to 20% long. That is one setup, so treat it as indicative.

Get the distance from the scale instead, as `d = f_px / scale`, where `f_px` is the focal
length in pixels. For the Pixel 6a, the EXIF focal length is 4.38 mm on a 5.645 mm wide
sensor, so `f_px = 4.38 / 5.645 × 4032 = 3128`. A zoom crops real sensor pixels, so the same
figure holds at any zoom. The distance matters for anything standing above the plane you
calibrated on, which is magnified by its height.

## The mat

The DeskCam mat is a printed A4 sheet with eight coded markers at positions this repository
knows in millimetres. With four or more of them in frame, `deskcam calibration` solves the
whole mapping between the mat's millimetres and the sensor, perspective included. That gives
the scale without a rule, the mat's rotation in the frame, and a fit residual that says
whether to trust either.

### Printing it

The sheets are in `explainer/mats/`, as vector PDFs in three designs (hybrid, quiet and
angles), each in portrait and landscape. `geometry.json` beside them holds what the solver
knows about each sheet. Print at 100% with scaling or shrink-to-fit off. The markers are
10 mm squares, so measure one with calipers after printing; a sheet printed at the wrong
scale gives confident wrong millimetres.

### Calibrating

```sh
deskcam calibration shot.jpg
```

On success it prints the scale in micrometres per pixel, with notes naming the sheet, how
many markers it found and how they were found, the mat's rotation, the worst corner's miss
after the fit, and the picture size.

It refuses when the markers are too few, too bunched to describe the page, or fit too badly.
This is a real refusal, from a capture on 2026-09-19 that showed only three markers:

```
mat calibration: REFUSED. found 3 marker(s) of deskcam-mat-a4-landscape-hybrid, need 4. Show more of the mat, or light it more evenly.
```

### Has the bench moved?

This is the one failure a mark cannot report. A mark is stored against the sensor, so moving
the camera or the stand leaves every mark on the wrong part, and nothing says so. Record a
calibration when you place marks, and check against it later:

```sh
deskcam calibration shot.jpg --write .            # writes ./deskcam-calibration.json
deskcam calibration later.jpg --against deskcam-calibration.json
```

With `--against`, the notes gain one line: how far the view has moved on the page, in
millimetres, and whether that is within the tolerance (2.0 mm unless `--tolerance-mm` says
otherwise). **A move over the tolerance is reported in that line and does not change the exit
code**, so a script should read the line, or the `--json` record. Over the tolerance means
look again and place the marks again. It does not mean nudge them.

### Without OpenCV

OpenCV finds marker corners to a fraction of a pixel, the same way every time, which is why it
is recommended. Without it `calibration` refuses and says so. The solver itself needs only
numpy, so corners read off the picture some other way, by a person or an agent looking at it,
go in through `--corners`:

```sh
# {"size": [4032, 3024], "markers": {"8": [[x,y],[x,y],[x,y],[x,y]], ...}}
deskcam calibration shot.jpg --corners corners.json
```

Corners go clockwise from each marker's top-left as printed. The millimetres come out the
same; only the precision of the corners differs, and the residual shows it.

## Sensor linearity

In measurement mode, doubling the exposure should double the pixel value. Measured on
2026-09-10 with `deskcam analyse linearity`, from seven captures between 50 ms and 400 ms at
ISO 56, zoom 4, white balance locked, on a static bench scene. The tool dropped the 400 ms
frame for clipping:

| Quantity | Result |
|---|---|
| Value change per doubling, raw fit | **2.062x** (95% 2.041 to 2.083) |
| Power-law fit | R squared 1.000, n = 6 |
| Pedestal at zero exposure | **-2.39 DN** |
| Exponent with the pedestal removed | **0.999** (1.999x per doubling, 95% 1.992 to 2.006) |
| Same-against-same noise floor for the run | 1.21 DN, smallest step between captures 10.68 DN |

The pedestal explains the excess over 2.0: a constant negative offset bends the raw exponent
upward. Read the result as **linear, with a black-level offset of about two digits**. Every
figure comes from one run of the tool. Reproduce it on your own setup:

```sh
deskcam set zoom=4 awblock=1 measure=1 iso=56
deskcam aatest -o lin/ exposure=200ms          # records the noise floor into lin/
for ms in 50 71 100 141 200 283 400; do
    deskcam snap -o lin/e$ms.jpg exposure=${ms}ms settle=600
done
deskcam analyse linearity lin/ --region 0.5,0.68,0.30,0.12
```

Your numbers will differ. The pedestal and the floor belong to your scene and camera, and the
region is a patch that was neither dark nor clipped in this one.

## Before you quote a number

* Run `deskcam aatest` for the scene, and hold every difference against it.
* Put a scale reference or the mat in the picture you are measuring, not a previous one.
* Check the tilt.
* Check `deskcam show` for `HOT`. A throttled phone has a hot sensor, and a hot sensor is
  noisier; give it a few minutes before comparing against an earlier number.
* Compare sharpness readings only within one hunt or one sweep. Twelve hunts of one subject
  agreed on the lens position and gave peak values from 34.9 to 64.5.
* Merge brackets on measured exposure, never nominal stops.
* Take the camera's first frame after it has been idle as a throwaway if timing matters. It
  takes longer, though it is no worse.
