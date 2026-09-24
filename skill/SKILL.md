---
name: deskcam
description: Look at real physical things with a phone bench camera over HTTP. Use when the user asks you to look at, read, photograph, inspect, or measure something in front of them - a handwritten note, a painting or a drawing, a page of a book, a plant, a 3D print, a circuit board, a component, an OLED or LCD panel, a connector, a cable, a device screen, anything physical. Also use when the user says "look at this", "can you see", "what does this look like", "read my note", "check the board", "photograph it", or refers to the deskcam, bench camera, or phone camera. Gives full-resolution stills, software zoom and pan, manual focus and exposure, RAW/DNG, and burst capture.
---

# DeskCam

A phone on a stand acting as a bench camera. You drive it with one shell command and
read the resulting file.

**It is a general observation platform.** Most of the time the job is to look at
something and say what is there. A handwritten note to read back, a watercolour someone
wants an opinion on, a page of a book, a plant, a first layer that is not sticking, a
connector seated crooked, a missing part. Answer the question that was actually asked, in
plain words, from a picture you looked at.

**Which mode you are in is situational, and the request decides it.** Not the tool, and
not this document.

| When the ask is | It wants |
|---|---|
| "read my handwritten note", "what does this look like", "help me with my watercolour", "is it seated properly" | Looking. Answer in words from the picture. The image pipeline's job here is to make the thing legible, so leave it on. |
| "help me build an enclosure", "will this fit", "how far apart are these holes", "is the first layer even" | Numbers, and every rule under **Before you report a number** applies in full. A scale reference in the frame, the noise floor, and a refusal rather than a guess. |

Both directions fail. A caution about measurement error is not an answer to "what does
this look like", and an eyeballed millimetre is not an answer to "will this fit". Read
which one you were asked for, and say which one you are giving.

## First, find out what this camera is

Before a session of any length, ask the camera what it can do. The answers differ from one
phone to the next, so take them from the device rather than from this document.

```sh
deskcam cameras          # the cameras, their size, closest focus, and capabilities
deskcam status           # the limits block, and what the camera is set to right now
```

Read the `limits` block once and then work inside it. **The limits are the phone's, not a
guess**, further down, says what each one governs. Capabilities are worth the same
attention: if `deskcam cameras` does not list `raw`, this phone cannot answer
`deskcam raw` at all, and that is a fact about the hardware rather than a fault to debug.

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

## Where the files land, and who sees what

A capture is written into `DESKCAM_SHOTS`, or into the current directory when that is
unset. `snap` prints the full path it wrote, so read that rather than assuming a
directory. Wherever the file goes, the command is also written into a journal with its
thumbnail, and the person's console reads that journal. So they can see what you captured,
what you changed and what was refused, from any directory and after your scratch directory
is gone.

**Say what each capture is for, and which project it is for.** Every capture leaves a record
beside it, and the person reads those records later to see what you looked at and why. Add
`--why "read the OLED after flashing"` to a capture, in your own words. The record also
names the project, which it finds from the repository you run in. A scratch directory is in
no repository, so do not `cd` into one to shoot. Stay in the project directory and send the
files where you want them:

```bash
export DESKCAM_SHOTS="$SCRATCH"        # where the files go
img=$(deskcam snap zoom=4 --why "check the solder bridge on U3")
```

If you cannot run from the project directory, set `DESKCAM_PROJECT` to its path.

`deskcam log 10 --json` is what happened recently, by anybody, if you need to catch up.
`via=console` narrows it to the person, and `op=snap,mark` to the operations named.

**There are two places a person sees the camera, and one camera page.** The page is one
file. The phone serves it, and the console embeds the same file beside its journal
(decision D20), so the live view, the gestures and the marks are the same on both.
What differs is what is written down.

| Page | Where | What it is |
|---|---|---|
| The bench tool | the phone itself, `http://PHONE:8080` | The camera page alone. Any browser on the network opens it with nothing installed. `deskcam open` opens this one. **Nothing done here reaches the journal.** |
| The console | the workstation, `http://127.0.0.1:9000`, from `deskcam serve` | The same camera page, with the journal of every operation beside it, grouped by project and session: captures with thumbnails and sidecars, changes to the camera, and refusals in the phone's words. Also **Snap** and the other operation buttons, which run the CLI's own commands, and the QR codes that install and pair. Everything the person does here is journalled as `via: console`. |

The console is **loopback only**. Its banner prints the machine's LAN address on the first
line, but that address serves the phone `/p/` and `/deskcam.apk` and nothing else, and
answers 403 for the page itself. Only `http://127.0.0.1:9000` opens the console, and only
from the workstation. The banner's `shots:` line says where captures are going.

The consequence for you: **there is no way to put an image in front of the person at the
bench.** Both views show the live camera and never your files. Give the person the path
you printed, or tell them to look at the console, and say which page you mean. What you
can put in front of them is a mark, which is the next section.

## Pointing, both ways

A **mark** is a point or a box on the picture with a few words on it: "look here", "pin 1",
"this cap". The page calls them annotations. A mark changes nothing on the camera, which is
the point of it: a double tap points too, but it moves the lens.

**The person points at something for you.** On either live view:

| Gesture or button | What it does |
|---|---|
| shift-drag a box | Replaces every mark with one box labelled "look here" |
| shift-click | Replaces every mark with one point labelled "look here" |
| ctrl-shift-drag | Adds a box and keeps the marks already there |
| **Mark this view** | Replaces every mark with a box the size of what is on screen |
| **Clear** | Removes every mark |
| **Reset all** | Resets the camera to the full sensor, then removes every mark |
| **Fit all** | Frames the camera around every mark. This one moves the camera |
| double tap | Focuses there and leaves the framing alone. Not a mark |
| drag a box, hold then drag, scroll, `+` and `-` | Aim: crop to the box, pan, zoom. Not a mark |

If they say "watch for my signal", "I'll point at it" or "I'll show you", wait for the mark
itself. A person lining a part up zooms and pans first, and every one of those is a `set`
in the journal, so a wait for anything wakes on the first zoom:

```bash
deskcam log wait op=mark timeout=180      # returns on their mark; exit 2 if none came
```

It prints one JSON entry. The place is in `query` as `mark=cx,cy` for a point or
`mark=cx,cy,w,h` for a box, as fractions of the whole frame, the coordinates `cx`, `cy`
and `focusbox` use, whatever the framing was when they drew it. So
`deskcam snap zoom=4 cx=CX cy=CY` looks where they pointed. The query also carries
`label=look here`, which is what the page always writes. The person's own words are not in
it, so ask them if the place alone is not enough.

`op` takes a list, and the names are the CLI's own, whichever page the person used:

| `op` | The person |
|---|---|
| `mark` | pointed: a shift-drag, a shift-click, or Mark this view |
| `unmark` | cleared the marks, with Clear or Reset all |
| `focus` | double tapped to focus. The place is the `focusbox` in `query` |
| `snap` | pressed Snap to take a still for you. Its path is in `files` |
| `set`, `reset`, `af` | aimed, reset, or pressed Autofocus |

`deskcam log wait op=mark,snap` returns on whichever comes first. `log wait` watches the
console only, because another agent's still is not a signal from the person. `via=any`
widens it.

**A mark drawn on the phone's own page is invisible to `log wait`**, because nothing done
there is journalled. If the person is at the phone rather than the console, ask them to
point and tell you when, then read the marks:

```bash
deskcam mark list      # every mark on the phone, as JSON
```

Each mark has an `id`, `kind` (`point` or `box`), `cx`, `cy` and for a box `w` and `h`,
in the same whole-frame coordinates, its `label`, who made it in `by`, when in `at`, and
`in_crop`, which says whether it is inside what the camera is framing now and so whether
the person can see it.

**You point at something for the person.** Look at a frame, find the part, and mark it in
the picture you looked at:

```bash
deskcam mark at 0.42,0.61 label="pin 1" by=claude             # a point
deskcam mark at 0.42,0.61,0.2,0.1 label="this cap" by=claude  # a box: centre, then size
```

`mark at` takes fractions of the picture you can see, 0 to 1 from the left and from the
top, the same as `focus at`, and turns them into whole-frame coordinates from the framing at
that moment. So take the frame, read it, and mark before anybody reframes. The mark appears
on both live views, in a list the person can click to go to it. `label` is at most 80
characters. `by` is one short word, and `agent` when left out. It is a claim, not a
proof, and the person's own marks say `you`. Your mark is added to the ones already there;
it does not replace them.

Tidy up after yourself with `deskcam mark clear ID`, which removes the mark with that `id`
from `mark list`. `deskcam mark clear` on its own removes every mark, the person's too, so
use it only when they ask. A clear is journalled as `unmark`.

The phone keeps at most 200 marks. They are stored on the sensor and mapped back through
`rotate` every time they are read, so a mark stays on its part when the phone is remounted.
They do not survive a restart of the app, and marking works while a tape holds the camera,
because a mark does not touch the camera.


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

**To focus on one part of a wide picture, name it rather than zooming to it:**

```sh
deskcam set focusbox=0.35,0.35,0.15,0.15   # cx,cy,w,h of the frame, same coords as cx/cy
deskcam focus hunt                          # hunts on the box, framing untouched
deskcam snap                                # the whole frame, sharp on the box
```

`focusbox` moves the autofocus region and the region a sharpness reading measures. It does
not move the crop and does not move the exposure metering, so the picture you framed is
still the picture you get. `focusbox=off` goes back to judging focus on the whole crop,
which is the default. A box outside the crop is refused, because that is focusing on
something the capture will not contain.

If focus is wrong, `deskcam af` runs one autofocus sweep, or set the distance yourself in
metres with `focusm`. When you want a number rather than a claim, `deskcam focus hunt`
walks the lens on the phone, prints the curve it measured, and leaves the lens at the
peak. Fix the exposure first, or it climbs the auto-exposure loop instead of the lens:

```sh
deskcam set exposure=1/33 iso=200 && deskcam focus hunt
```

It exits non-zero, and puts the focus back, when there is no peak in the range: either the
curve was flat, so nothing in the crop came into focus anywhere, or the peak was at an end
of the range and the real one is outside it. That refusal is the reason to use it over
`deskcam af`, which reports `focused` with nothing behind it.

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

## Looking at things that are not circuit boards

A note, a painting, a fabric, a plant, a page. Same camera, and most of the measurement
advice below is wrong for these.

- **Leave the pipeline alone.** `measure=1` switches off the tone curve, the noise
  reduction and the sharpening, which are the things that make a picture legible to a
  person. It is for comparing pixel values and nothing else. Never use it to read a note
  or to look at a painting.
- **Fill the frame by moving the camera, not by zooming.** Zoom is a crop of sensor
  pixels, so a note filling the frame at `zoom=1` carries far more detail than the same
  note cropped to at `zoom=4`. Ask for the subject or the stand to be moved.
- **The torch is one small LED, so it glares.** Ink, varnish, wet paint, a photo in a
  plastic sleeve and a glossy page all throw a specular highlight straight back. If a
  bright patch is washing out part of the picture, turn the torch off and use the room,
  or ask for a lamp off to one side.
- **Raking light shows relief.** Brush strokes, paper grain, an embossed seal, a scratch,
  a crease. Light from one side at a shallow angle reveals texture that flat light hides.
  That is a request to the person rather than a setting.
- **Square up for anything with lines on it.** A note or a painting read straight on keeps
  its text and edges square. The tilt in `deskcam status` is the check, and it is the angle
  to gravity, so it only means square when the subject is lying flat.
- **Reading small handwriting is a resolution question.** Use `deskcam snap`, which is full
  resolution. `deskcam frame` is preview sized and will not read faint pencil.
- **Lock the white balance before comparing colour.** `awblock=on`, or a named setting like
  `awb=daylight`. Two captures taken on auto white balance can differ in colour for no
  reason but the camera changing its mind, so never report a colour difference between them.

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

Measured on 2026-09-10 with `deskcam analyse linearity`, six captures from 50 to 283 ms at
ISO 56: **2.062x per doubling** (95% 2.041 to 2.083, R squared 1.000), with a black-level
pedestal of -2.39 DN. With the pedestal removed the tool reports an exponent of 0.999
(1.999x per doubling, 95% 1.992 to 2.006), so read this as **linear with an offset of about
two digits**. Run `deskcam analyse linearity DIR` to measure it again on your own setup;
the tool prints all three figures.

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
| Find the best focus | `deskcam focus hunt` | The phone walks the lens and stops at the peak. One request, no frames over the network, and it refuses when there is no peak. |
| Focus on something you can see | `deskcam focus at 0.3,0.6` | Autofocus on that place in the picture, as fractions from the left and from the top of the frame you just looked at. The framing does not move. Use it when a frame is sharp in the wrong place. |
| Read the sharpness once | `deskcam show sharpness=1` | A number, not a picture. The hunt above is this in a loop on the phone. |
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

## A sequence goes as one tape

When you want several steps, submit them together rather than one command at a time:

```sh
cat > /tmp/inspect.dcl <<'TAPE'
# inspect the part, lit and unlit
SET zoom=2 cx=0.5 cy=0.5 exposure=1/33 iso=200 awbgains=neutral
FOCUSHUNT
SET torch=25
WAIT 500
SNAP
SET torch=0
SNAP
TAPE
deskcam script run /tmp/inspect.dcl
```

The verbs are `SNAP`, `FRAME`, `RAW`, `BURST`, `BRACKET`, `FOCUSSWEEP`, `WALK`,
`FOCUSHUNT`, `SET`, `RESET`, `AF`, `STATUS` and `WAIT`, each taking the same `k=v` words
its command takes. `#` is a comment. `WAIT` is for waiting on something that is not a
capture, such as the LED settling after `torch=45`; a capture verb has `settle` of its own.

**Do this whenever two steps have to describe the same moment**, because the reason is not
speed. Nothing stops the browser panel, or another agent, from changing the camera between
your `SET` and your `SNAP`. A tape holds the camera for its whole duration and refuses
anything that would change it, so the sequence you asked for is the sequence that ran.

The whole tape is read before any of it runs, so a typo is a 400 and the camera is
untouched. A step that fails ends the tape, puts the camera back where the tape found it,
and exits non-zero, so `deskcam script run x.dcl && deskcam analyse ...` is safe to write.
A `FOCUSHUNT` that finds no peak is a failed step for that reason: the `SNAP` after it
would have been out of focus.

Each capture is written to the directory the run prints, with its own record beside it.
You are the intelligence; a tape has no branching, no variables and no arithmetic on
purpose. When you need to decide something, read the result and send another tape.

## Everything is one parameter set

Any parameter works on any command, and is applied before the picture is taken. So one
line is a complete instruction and you never need to remember the current state:

```sh
deskcam snap zoom=6 cx=0.3 cy=0.7 focusm=0.15 torch=25 exposure=1/120 iso=100
```

**These persist until you change them again:** `camera`, `zoom`, `zoomby`, `cx`, `cy`,
`dx`, `dy`, `af`, `focus`, `focusm`, `focusbox`, `ae`, `exposure`, `iso`, `ev`, `aelock`,
`awb`, `awbgains`, `awblock`, `torch`, `measure`, `shadingmap`, `rotate`, `previewsize`,
`stillsize`. `cam`, `shutter` and `sensitivity` are other names for `camera`, `exposure`
and `iso`. `previewsize` and `stillsize` rebuild the capture session, so leave them alone
unless you know why; `stillsize=max` is the default.

**These apply to the one command that names them and are then forgotten:** `w`, `h`,
`jpegq` (or `quality`). So `deskcam frame w=320` does not shrink your next `snap`, and a
resize never quietly disables the untouched-JPEG path.

**The rest steer one request and are not camera state.** `reset=1` clears every setting
before the rest of the request is applied, so `deskcam snap reset=1 zoom=4` starts clean.
`settle`, `timeout` and `fresh` time a capture (see **The limits are the phone's**). `n` is a
burst's length or a stream's frame count, and `fps` a stream's rate. `wait` is how long
`deskcam af` waits after its sweep. `format` set to `raw` makes `deskcam burst` take DNG frames.
`from`, `to`, `steps`, `coarse`, `fine`, `base`, `stops`, `vary` and `values` belong to the
sweeps, hunts, brackets and walks, and `mark`, `unmark`, `label` and `by` to marks.
`sharpness=1` makes `deskcam show` read a fresh frame. `port` is for the network diagnostic
only.

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

It records the floor beside your captures. `deskcam analyse linearity` then **refuses** a
series whose steps sit inside it. The other tools do not read it yet, so hold the
differences they report against the floor it printed.

The tools live in `frontend/analysis/` and need `pip install -e '.[analysis]'`:

| Question | Command |
|---|---|
| How large must a difference be to be real? | `deskcam aatest` |
| Does the pixel value track the light? | `deskcam analyse linearity DIR` |
| How much does averaging a burst help? | `deskcam analyse burst-noise DIR` |
| How many pixels per millimetre, in this picture? | `deskcam scale FILE --pitch-mm 1.0` |
| How far apart are these two points, in mm? | `deskcam measure FILE 412,308 1190,306` |
| How sharp is what the camera is looking at? | `deskcam show sharpness=1` |
| Where is the mat, and has the bench moved? | `deskcam calibration FILE` |

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

**The mat can see the bench move, and a scale cannot.** If the printed DeskCam mat is in
frame, `deskcam calibration FILE` solves the mapping between its millimetres and the
sensor from the eight coded markers around its edge, whose positions this repository knows
exactly. That gives the scale, the mat's rotation in the frame, and a fit residual that
says whether to believe any of it.

```sh
deskcam calibration shot.jpg --write .          # record this framing
deskcam calibration later.jpg --against deskcam-calibration.json
#   the view has moved 71.9 mm on the page since that calibration, over the
#   2.0 mm tolerance: marks placed against it now name the wrong parts
```

**This is the answer to the one failure a mark cannot report.** A mark is stored against
the sensor, so moving the camera or the stand leaves every mark pointing at the wrong
part with nothing anywhere to say so. Record a calibration when you place marks, and check
it against a later capture before you trust them. A reading over the tolerance means
re-read the subject and place the marks again; it does not mean nudge them.

**Finding the markers wants OpenCV, but does not require it.** Install it with
`uv pip install 'opencv-python-headless>=4.7'`, or `pip install -e '.[mat]'`. That is the
recommendation, because it locates corners to a fraction of a pixel and does it the same
way every time. Without it the command refuses and says so. The solver itself needs only
numpy, so when there is no detector you can read the markers off the picture yourself and
pass them in:

```sh
# {"size": [4032, 3024], "markers": {"8": [[x,y],[x,y],[x,y],[x,y]], ...}}
deskcam calibration shot.jpg --corners corners.json
```

Corners go clockwise from the marker's top-left as printed, and four markers well spread
across the sheet are the minimum. The millimetres that come out are the same; only the
precision of the corners differs, and the residual in the output is where that shows up.
Both paths refuse when the markers are too few, too bunched together to describe the whole
page, or fit too badly, rather than returning a mapping that describes one corner of the
sheet and nothing else.

**The camera sleeps, and waking it is not free.** After 20 seconds with nothing asking
for a frame the phone stops reading its sensor. The next capture starts it again and waits
for the exposure to settle before answering, so you never get a frame from a pipeline that
has not converged: the cost is a slower first capture, never a worse one. `deskcam status`
has a `preview` block saying whether it is idle and what the last wake cost. If you are
about to take a series where timing matters, take one throwaway frame first.

**A hot phone is a noisier phone.** `deskcam show` ends with `HOT severe` once the
platform is throttling, and `deskcam status` carries a `device` block with the level and
the battery. A long session of bursts and walks will get there. Captures are never slowed,
but the sensor is hot, so treat a `HOT` marker the way you would treat a changed setting:
give the phone a few minutes before you quote a number you are comparing against an earlier
one. A stream does slow down, and says so in each part.

**Sharpness is a comparison, never a measurement.** It moves with the subject, with how
much of the frame the region of interest holds, and with the noise. Only compare readings
taken with everything but the focus held still, and check the age the reading comes with.
Twelve hunts of one subject on this bench agreed about the lens position and gave peak
values from 34.9 to 64.5, so take the `diopters` a hunt returns and never carry its
`sharpness` to another hunt.

**Merge a bracket on the measured exposure, never on the nominal stop.** The sensor does
not deliver exactly what it was asked for. Every frame records `exposure_ns`,
`base_periods` and `period_error` for that reason.

## The limits are the phone's, not a guess

`deskcam status` carries a `limits` block. Read it instead of assuming, because these are
one device's numbers and another phone answers differently.

Read live from this bench's Pixel 6a on 2026-09-13:

| Limit | Here | What it governs |
|---|---|---|
| `burst_max` | 31 | `n` on a burst, `steps` on a focussweep, `stops` on a bracket, and the count of `values` on a walk. All four. |
| `iso_range` | 56..7111 | `iso`. Above `max_analog_iso`, which is 444 here, the gain is digital and adds no light. Stay under it for measurement. |
| `exposure_human_range` | 53.7us .. 10.177s | `exposure`. A long one needs `timeout=` raised to match, or the capture gives up first. |
| `min_focus_diopters` | 10.2, which is 98 mm | The near end of `focus`, and the default `to=` of a sweep or a hunt. A sweep of `from=3 to=6` leaves most of the range unvisited. |
| `max_zoom` | 63 | `zoom`. The optical advice above still stands: past about 8 you are cropping to too few pixels. |
| `max_output_edge`, `max_output_pixels` | 2896, 8388608 | `w` and `h`. A larger resize is refused before anything is captured. |
| `ev_range`, `ev_step` | -24..24, 0.167 | `ev`, in sixths of a stop, and only while `ae=on`. |
| `torch_max_level` | 45 | `torch`. |
| `raw_black_level`, `raw_white_level` | 64, 1023 | The floor and the ceiling of a DNG pixel, which is what a linearity check is measured against. |

`/api/cameras` says what each camera can do rather than what it is. The rear camera here
reports `manual_sensor`, `manual_post`, `burst` and `raw`, which is why fixed exposure,
measurement mode and DNG all work. A phone missing `raw` cannot answer `deskcam raw` at
all, and the failure is worth reading as a capability rather than a fault.

**Three router parameters change how a capture is timed**, and they are easy to miss.
`settle` waits after applying settings before capturing, defaulting to 350 ms while auto
exposure is on and 120 otherwise. `timeout` bounds the capture itself, 100 to 60000.
`fresh` discards that many preview frames first, so a frame exposed under the previous
settings is never handed back as the new one. After a large change of light, `fresh=2` is
cheaper than a throwaway capture.


## Every command, and what it reaches

The sections above teach the commands in the order you need them. This is the whole
surface in one place, so nothing the camera answers is a surprise. `deskcam help` prints
the CLI's own text, and `deskcam api` the phone's.

| Phone endpoint | Command | What for |
|---|---|---|
| `/api/still` | `deskcam snap` | A full-resolution still of the crop |
| `/api/frame` | `deskcam frame` | A fast preview-sized still, for aiming |
| `/api/raw` | `deskcam raw` | The whole sensor as a DNG |
| `/api/burst` | `deskcam burst N` | N stills with one set of settings |
| `/api/focussweep` | `deskcam focussweep` | A still at each lens position, for stacking |
| `/api/focushunt` | `deskcam focus hunt` | Walks the lens and stops at the sharpest place, or refuses |
| `/api/bracket` | `deskcam bracket` | Stills at doubling exposures |
| `/api/walk` | `deskcam walk vary=NAME values=A,B,C` | One still at each value of one setting, e.g. `vary=torch values=0,10,20,45` |
| `/api/stream` | `deskcam stream` | An MJPEG stream to a file, 30 frames unless `n=` says otherwise. It refuses any setting that would change the camera |
| `/api/script` | `deskcam script run FILE` | A tape of steps as one operation |
| `/api/set` | `deskcam set k=v`, and the shorthands `deskcam zoom N`, `deskcam pan up\|down\|left\|right [amt]`, `deskcam center`, `deskcam focus METRES\|auto`, `deskcam exposure VALUE`, `deskcam iso N`, `deskcam torch 0-45\|off\|max`, `deskcam auto`, `deskcam recall FILE.json` | Changes the camera and prints the result. `auto` hands exposure and focus back to the camera |
| `/api/reset` | `deskcam reset` | Every setting back to its default |
| `/api/af` | `deskcam af`, `deskcam focus at FX,FY` | One autofocus sweep, on the crop or on a place |
| `/api/status` | `deskcam status`, `deskcam show` | The whole state as JSON, or one line |
| `/api/cameras` | `deskcam cameras` | Each camera and what it can do |
| `/api/marks` | `deskcam mark at`, `mark list`, `mark clear` | Pointing, both ways |
| `/api/help` | `deskcam api` | The machine-readable reference: every endpoint, parameter and tape verb |
| `/api/orientation` | none; the same numbers are the `orientation` block of `deskcam status` | Tilt to gravity, roll, pitch and ambient light |
| `/api/shadingmap` | none | The lens shading map, after `deskcam set shadingmap=on` |
| `/api/nettest` | none | Diagnostic: the phone opens a connection back to you to prove it can |

For the three with no command, `curl -s "$(deskcam which)/api/orientation"`, adding
`?token=KEY` with the key from `deskcam token show` when one is set.

These never reach the camera:

| Command | What for |
|---|---|
| `deskcam aatest`, `deskcam scale`, `deskcam measure`, `deskcam analyse ...` | The measurement tools. See **Before you report a number** |
| `deskcam calibration FILE` | Where the mat is, and whether the bench has moved since a recorded one. See **The mat can see the bench move** |
| `deskcam analyse average DIR`, `deskcam analyse stack DIR`, `deskcam analyse hdr DIR` | One 16-bit image from a burst, one image sharp at every depth from a focus sweep, one linear image from a bracket |
| `deskcam analyse scale FILE` | `deskcam scale` without recording the result |
| `deskcam log`, `deskcam log wait` | Read the journal, or wait on it. See **Pointing, both ways** |
| `deskcam open` | Open the phone's camera page in a browser |
| `deskcam serve` | Start the console, on port 9000 unless another is given |
| `deskcam which`, `deskcam use URL [KEY]` | Print the phone's address, or set it. A second word is the access key, for a machine that did not pair |
| `deskcam wifi`, `deskcam usb` | Reach the phone over Wi-Fi, or over a USB cable through adb |
| `deskcam start`, `deskcam stop` | Start or stop the service on the phone, through adb |
| `deskcam token new\|show\|set\|clear` | The access key. `show` is harmless. Change it only when the person asks: `new` and `clear` change the key here and not on the phone, so the camera refuses this CLI until the person pairs the phone again. `set KEY` adopts a key the phone already expects, which is how a second machine joins without pairing; `set -` reads it from standard input, keeping it out of `ps` and the shell history |
| `deskcam version` | This CLI's version |

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
