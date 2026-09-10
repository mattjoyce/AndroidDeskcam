---
name: deskcam
description: Look at real physical things with a phone bench camera over HTTP. Use when the user asks you to look at, photograph, inspect, or measure something on their desk - a circuit board, a component, an OLED or LCD panel, a connector, a cable, a device screen, a 3D print, anything physical. Also use when the user says "look at this", "can you see", "what does this look like", "check the board", "photograph it", or refers to the deskcam, bench camera, or phone camera. Gives full-resolution stills, software zoom and pan, manual focus and exposure, RAW/DNG, and burst capture.
---

# DeskCam

A phone on a stand acting as a bench camera. You drive it with one shell command and
read the resulting file.

The camera is a measuring instrument first. Where "looks good" and "is correct" disagree,
correct wins.

## The loop

```sh
img=$(deskcam snap zoom=4 cx=0.35 cy=0.62)
```

`snap` prints the path it wrote and nothing else. Read that path with the Read tool to
see the picture. Every capture also writes `NAME.json` beside the image, holding the
settings and the measured values.

Check the camera is there before a long sequence:

```sh
deskcam show      # one line: zoom, framing, focus, exposure, iso
```

If that fails, refer to **When it does not work** at the end.

## Aiming

Zoom and pan are a crop of the full 4032x3024 sensor, done in software. `zoom=4` gives a
true 1008x756 crop of real pixels. Nothing is enlarged.

| Want | Command |
|---|---|
| See everything | `deskcam reset` |
| Closer | `deskcam snap zoom=4` |
| A named spot | `deskcam snap zoom=6 cx=0.3 cy=0.7` |
| Nudge the view | `deskcam pan left`, `pan up 0.4` |
| Back to centre | `deskcam center` |

`cx` and `cy` are 0 to 1 across the whole frame. `0,0` is the top left corner and
`0.5,0.5` is the centre. **Take a wide shot first, read it, then pick coordinates from
what you saw.** Do not guess coordinates blind.

Focus and light metering follow the crop, so a zoom onto a part also focuses on that part.

If the picture is upside down or sideways, the phone is mounted that way. Correct it once
with `deskcam set rotate=180` and it stays.

## Close work on a board

The camera focuses to 98 mm. At that distance one pixel covers 30 micrometres, so it
reads silkscreen and finds a part. It cannot show a solder fillet. That is an optical
limit and no setting changes it.

```sh
deskcam set focusm=0.12 torch=30      # focus at 12 cm, LED at level 30 of 45
img=$(deskcam snap zoom=5)
```

`torch` is the rear LED, 0 to 45. It is the bench light. Use it whenever the part is in
shadow. Turn it off after with `torch=0`.

If focus is wrong, `deskcam af` runs one autofocus sweep, or set the distance yourself in
metres with `focusm`.

## Photographing a display

**A display needs a fixed exposure or you get dark bands across it.** The panel flickers
faster than the eye sees, and the camera reads the frame one row at a time, so different
rows catch different parts of the flicker.

Set the exposure to a whole multiple of the panel period:

```sh
deskcam set exposure=1/60 iso=200 awb=daylight awblock=on   # a 60 Hz panel
img=$(deskcam snap)
```

Try `1/60`, `1/30` or `1/120` and keep whichever has no bands. Lock the white balance as
well, or the colour drifts between shots and you will report colour differences that are
not real.

## Measuring, not photographing

Use `measure=1` when the pixel values themselves matter, for example when comparing two
renders or checking panel uniformity.

```sh
img=$(deskcam snap measure=1 iso=56 exposure=1/120)
```

This turns off the tone curve, noise reduction, edge enhancement, and lens shading
correction. In this mode doubling the exposure doubles the pixel value; with the default
pipeline it does not, and the default lifts the shadows substantially. The default makes a
photograph look good, which is the opposite of what a comparison needs.

Measured on 2026-09-10, six captures from 50 to 283 ms at ISO 56: **2.062x per doubling**
(95% 2.041 to 2.083, R squared 1.000), with a black-level pedestal of -2.39 DN. Remove the
pedestal and the exponent is 1.003, so read this as **linear with an offset of about two
digits**. Run `deskcam analyse linearity DIR` to measure it again on your own setup.

The image will look dark and flat. That is correct.

`deskcam status` reports a `pipeline` block saying what the camera actually applied, not
what was asked for. It describes the **preview**. A capture describes itself: its own
values are in its sidecar and in its EXIF `UserComment`, taken from the capture result of
that frame.

## Better data

| Need | Command | Why |
|---|---|---|
| Linear sensor data | `deskcam raw -o x.dng` | 10-bit, unprocessed, for real measurement. 24 MB. |
| Less noise | `deskcam burst 16` | Average the frames. Noise falls by about the square root of the count. |
| Repeat an old shot | `deskcam recall old.json` | Restores the camera settings, so a comparison is valid. |
| Find the best focus | `deskcam show sharpness=1` | A number, not a picture. Move the focus, read it again, keep the peak. |
| Everything sharp at once | `deskcam focussweep from=3 to=6 steps=7` | A still at each lens position, for stacking. Steps are equal in dioptres. |
| A lit panel in a dark bezel | `deskcam bracket base=1/240 stops=4` | Doubling exposures, each a whole multiple of the panel's PWM period. |

Every capture writes `NAME.json` beside the image. It comes from the capture itself, in the
`X-DeskCam-Provenance` header of the reply that carried the picture, so it describes that
frame and not whatever the camera was doing a moment later.

`deskcam recall` restores camera state: framing, focus, exposure, ISO, torch, white
balance, rotation, measurement mode. It does not restore `w`, `h` or `jpegq`, because those
apply to one request only and are gone by then. Pass them again on the new capture.

**A burst can come back short.** `deskcam burst` prints a warning on stderr when the phone
returned fewer frames than you asked for, and the phone answers 206 rather than 200. Check
the frame count before you average.

The DNG holds the **whole sensor**. Zoom and pan do not apply to it, because a workstation
must demosaic before it crops. The framing is reported in the `X-DeskCam-ROI` header.

For a burst, set the exposure a little dark. Highlights that clip cannot be recovered, and
the average brings the shadows back.

## Everything is one parameter set

Any parameter works on any command, and is applied before the picture is taken. So one
line is a complete instruction and you never need to remember the current state:

```sh
deskcam snap zoom=6 cx=0.3 cy=0.7 focusm=0.15 torch=25 exposure=1/120 iso=100
```

**These persist until you change them again:** `camera`, `zoom`, `zoomby`, `cx`, `cy`,
`dx`, `dy`, `af`, `focus`, `focusm`, `ae`, `exposure`, `iso`, `ev`, `aelock`, `awb`,
`awblock`, `torch`, `measure`, `shadingmap`, `rotate`.

**These apply to the one command that names them and are then forgotten:** `w`, `h`,
`jpegq`. So `deskcam frame w=320` does not shrink your next `snap`, and a resize never
quietly disables the untouched-JPEG path.

Exposure accepts what a datasheet says: `1/120`, `8ms`, `250us`, `0.5s`.

A wrong parameter name is an error, not a silent no-op, on every endpoint. So is a value
the phone cannot read: `timeout=soon` is a 400, not a default. If a command fails, read the
message; the CLI prints the phone's reason and it names the bad parameter.

`deskcam show` prints every setting that can change your next capture, rotation and
measurement mode included.

`deskcam api` prints the full machine-readable reference if you need something not here.

## Before you report a number

**Run `deskcam aatest` first.** It takes two captures with identical settings and prints the
smallest difference a measurement can honestly claim. Anything smaller than that is this
camera talking to itself.

```sh
deskcam aatest measure=1 iso=56 exposure=200ms
# aa-test: 1.415 DN ... must differ by more than 1.42 DN (1.38% of the level)
```

It records the floor beside your captures, and the analysis tools then **refuse** any
result that sits inside it. You do not have to remember the number, only to have run it.

The tools live in `frontend/analysis/` and need `pip install -e '.[analysis]'`:

| Question | Command |
|---|---|
| How large must a difference be to be real? | `deskcam aatest` |
| Does the pixel value track the light? | `deskcam analyse linearity DIR` |
| How much does averaging a burst help? | `deskcam analyse burst-noise DIR` |
| How many pixels per millimetre, in this picture? | `deskcam scale FILE --pitch-mm 1.0` |
| How far apart are these two points, in mm? | `deskcam measure FILE 412,308 1190,306` |
| How sharp is what the camera is looking at? | `deskcam show sharpness=1` |

Each one prints its value with an interval and a sample count, and refuses rather than
guessing when its confidence is too low. A refusal exits 2 and carries no number, on
purpose: a number with a warning beside it gets quoted without the warning. Add `--json`
for the full record.

**Scale is not a camera specification.** It changes whenever the stand moves, so measure it
from a rule or graph paper inside the picture you are actually reporting on. If more than
one regular pattern is in frame, the tool says so and you have to choose.

`deskcam scale` records what it measured, and every capture taken afterwards carries the
number in its sidecar while the framing holds. Change the zoom, the pan, the rotation or
the camera and the sidecar says which one changed and that the scale no longer describes
it. What none of it can see is the stand moving, so a sidecar carrying a scale is making a
claim about the settings and never about the bench.

**Sharpness is a comparison, never a measurement.** It moves with the subject, with how
much of the frame the region of interest holds, and with the noise. Only compare readings
taken with everything but the focus held still, and check the age the reading comes with.

**Merge a bracket on the measured exposure, never on the nominal stop.** The sensor does
not deliver exactly what it was asked for. Every frame records `exposure_ns`,
`base_periods` and `period_error` for that reason.

## Judgement

- **Read the image you took.** Do not report on a picture you have not looked at.
- Take a wide shot before a close one. Pick coordinates from what you actually saw.
- `deskcam frame` is much faster than `snap` and is enough while you are still aiming.
  Use `snap` once the framing is right.
- Do not push zoom past about 8. You are cropping to very few pixels. Ask the user to move
  the camera closer instead.
- Say when you cannot see something well enough. A blurred or too-distant picture is not
  evidence. Ask for the camera to be moved, or for a macro lens.
- Turn the torch off when you finish.
- The camera is rear-facing, 12.2 MP, id 0. Do not switch to camera 1; it is the front
  camera, lower resolution and fixed focus, so it cannot focus on a board at all.

## When it does not work

```sh
deskcam show
```

| Symptom | Meaning | Fix |
|---|---|---|
| `state: disconnected` | Another app has the camera | It reopens by itself within about 15 s. Wait, then retry. |
| Connection refused | The service is not running | `deskcam start`, or ask the user to open DeskCam on the phone |
| Wrong or no address | The phone moved to a new address | `deskcam wifi`, or `deskcam usb` for a cable, or `deskcam serve` and scan the QR code |
| A capture times out | The exposure is very long | Raise it with `timeout=20000`, or shorten the exposure |

After a reboot the phone does not start the camera by itself. Android forbids it for a
camera app. Run `deskcam start`, or ask the user to tap the app once.
