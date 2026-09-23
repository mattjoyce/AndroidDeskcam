# The CLI day to day

`deskcam` is one static binary. Each command is one HTTP request to the phone, prints a file
path or one line of state, and exits 0 when it worked, 1 on an error and 2 on a refusal. The
full list is in the [reference](reference.md#cli).

## One line is a complete instruction

Every command takes any camera parameter as a `k=v` word, applied before the picture is
taken:

```sh
deskcam snap zoom=6 cx=0.32 cy=0.68 torch=25 focusm=0.12 exposure=1/120
```

So a script never has to remember what state the camera is in. Three kinds of parameter
behave differently:

| Kind | Examples | Lasts |
|---|---|---|
| Camera state | `zoom`, `cx`, `cy`, `focus`, `exposure`, `iso`, `torch`, `awb`, `measure`, `rotate` | Until something changes it |
| Presentation | `w`, `h`, `jpegq` | This one request |
| Router | `reset`, `settle`, `timeout`, `fresh`, `n`, and the sweep ranges | This one request |

So `deskcam frame w=320` does not shrink your next `snap`. `reset=1` starts a request from
the defaults: `deskcam snap reset=1 zoom=4` ignores whatever the last person left behind.

A parameter the phone does not know, or a value it cannot read, is an error that names it,
never a silent default. `timeout=soon` is a 400.

## Looking at the state

| Command | Prints |
|---|---|
| `deskcam show` | One line: zoom, framing, focus, exposure, ISO, and anything unusual such as rotation, measurement mode or `HOT severe` |
| `deskcam show sharpness=1` | The same, with a fresh sharpness reading |
| `deskcam status` | Everything, as JSON: settings, limits, measured values, orientation, heat and battery |
| `deskcam cameras` | Each camera, its size, its closest focus and what it can do |

Before a long session, read `deskcam status` once for its `limits` block. The numbers are
the phone's, and another phone answers differently.

## Framing

Zoom and pan crop the full 4032 by 3024 sensor in software, so `zoom=4` is a real 1008 by
756 crop of sensor pixels, never an enlargement. `cx` and `cy` run from 0 to 1 across the
whole sensor, with `0,0` at the top left.

| Want | Command |
|---|---|
| The whole sensor | `deskcam reset`, or `deskcam zoom 1` |
| Closer | `deskcam zoom 4` |
| A named spot | `deskcam snap zoom=6 cx=0.3 cy=0.7` |
| Nudge the view | `deskcam pan left`, `deskcam pan up 0.4` |
| Back to the centre | `deskcam center` |
| The phone is mounted upside down | `deskcam set rotate=180`, once |

Take a wide shot first, look at it, and pick coordinates from what you saw. Past about 6x
the crop holds few pixels; moving the phone closer does better than zooming further.

Metering and autofocus follow the crop, so zooming onto a part also exposes and focuses for
that part.

## Focus

| Want | Command |
|---|---|
| Let the camera decide | `deskcam af`, one sweep on the crop |
| Focus on a spot you can see, keeping the framing | `deskcam focus at 0.3,0.6` |
| A fixed distance | `deskcam focus 0.12`, in metres, or `focus=4.25` in dioptres |
| Back to automatic | `deskcam focus auto`, or `deskcam auto` for focus and exposure together |
| The sharpest position, measured | `deskcam focus hunt` |
| Judge focus somewhere other than the crop | `deskcam set focusbox=0.35,0.35,0.15,0.15` |

`focus at FX,FY` takes fractions of the picture you are looking at, 0 to 1 from the left and
from the top, not of the whole sensor. It is the CLI's double tap.

`focusbox` is `cx,cy,w,h` in whole-sensor coordinates. It moves where focus is judged, and
where a sharpness reading measures, without moving the crop or the metering. `focusbox=off`
goes back to the crop. A box outside the crop is refused, because the capture would not
contain what you focused on.

`deskcam focus hunt` walks the lens on the phone, prints the curve it measured, and leaves
the lens at the peak. **Fix the exposure first** (`exposure=1/33 iso=200`), or it climbs the
auto-exposure loop instead of the lens. It refuses, exits non-zero and puts the focus back
when the curve is flat or peaks at an end of the range. `deskcam af` reports `focused` even
when nothing is. [How it works](how-it-works.md#hunting-the-focus) shows what a hunt prints.

The closest focus on the Pixel 6a is 98 mm, about 10.2 dioptres.

## Exposure and colour

| Want | Command |
|---|---|
| A fixed shutter | `deskcam exposure 1/120`, or `8ms`, `250us`, `0.5s` |
| A fixed ISO | `deskcam iso 200` |
| Automatic again | `deskcam auto` |
| Brighter or darker, on auto | `deskcam set ev=6`, in steps of `ev_step` |
| Colour that does not drift | `deskcam set awb=daylight awblock=on` |
| No white balance at all | `deskcam set awbgains=neutral` |
| Linear pixel values | `deskcam set measure=1`, and `measure=0` to go back |

Setting either `exposure` or `iso` switches auto exposure off. An exposure longer than a
few seconds needs `timeout=` raised to match. ISO above `max_analog_iso`, 444 on the Pixel
6a, is digital gain and adds noise without adding light.

**Leave `measure` off unless you are comparing pixel values.** It switches off the tone
curve, sharpening and noise reduction, which make a picture legible, and the image looks
dark and flat. [Measuring](measuring.md) is where it belongs.

## Light

`deskcam torch 25` sets the rear LED, 0 to 45 on the Pixel 6a. `deskcam torch off` and
`deskcam torch max` do what they say. It is the bench light. It is one small LED, so it
glares off anything glossy. Turn it off when you finish.

## Kinds of capture

| Command | Gives you | Use it for |
|---|---|---|
| `deskcam snap` | A full-resolution still of the crop | Almost everything |
| `deskcam frame` | A preview-sized frame, fast | Aiming, before the snap |
| `deskcam raw` | The whole sensor as a DNG | Measurement work outside the pipeline |
| `deskcam burst 16` | 16 stills with one set of settings | Averaging the noise away |
| `deskcam focussweep from=3 to=6 steps=7` | A still at each lens position | Focus stacking |
| `deskcam bracket base=1/240 stops=4` | Stills at doubling exposures | A lit screen in a dark bezel |
| `deskcam walk vary=torch values=0,10,20,45` | One still at each value of one setting | Seeing what a setting does |
| `deskcam stream n=60` | An MJPEG file | Watching something change |

`-o FILE` names a single capture, and `-o DIR` names the directory for a set. Each file gets
its own sidecar. A burst can come back short; the CLI warns on stderr and the phone answers
206.

A DNG always holds the whole sensor, because a crop has to wait until after demosaicing. The
framing you asked for is reported in the `X-DeskCam-ROI` header.

`deskcam stream` is a view and refuses any parameter that would change the camera. Set the
camera first with `deskcam set`.

## Putting the camera back

| Command | Does |
|---|---|
| `deskcam reset` | Every setting to its default |
| `deskcam recall shot.json` | Restores the camera settings a past capture recorded: framing, focus, exposure, ISO, torch, white balance, rotation, measurement mode |
| `deskcam auto` | Focus and exposure back to automatic, nothing else |

`recall` does not restore `w`, `h` or `jpegq`, because they belonged to one request. Pass
them again. In the console, **Shoot this again** is a recall and a snap.

## Where captures go, and saying why

A capture goes to `-o`, or to `DESKCAM_SHOTS`, or to the current directory. `snap` prints the
full path, so read the path rather than assuming a directory.

Every capture also records who asked, in the `asker` block of its sidecar. Give it a reason:

```sh
deskcam snap zoom=4 --why "check the solder bridge on U3"
```

The record names the project by finding the nearest `.git` above the current directory. A
scratch directory is in no project, so either run from the project and send the files
elsewhere with `DESKCAM_SHOTS`, or set `DESKCAM_PROJECT`. `DESKCAM_SESSION` names a run of
work, and the console groups by it.

## The journal

Every command that takes a picture or changes the camera is written into the journal, with
a thumbnail and a copy of the sidecar, wherever the files went and whoever asked. Refusals
are written too. Reading the camera is not.

```sh
deskcam log                      # the last 20 operations, by anybody
deskcam log 50 via=console       # the last 50 done at the console
deskcam log op=snap,mark --json  # only captures and marks, as JSON
```

The console shows the same journal. It lives in `~/.local/state/deskcam/journal`, or
`DESKCAM_JOURNAL`, keeps its newest 2,000 entries, and never fails a capture by failing to
write. `deskcam log wait` is for agents and is in [Working with an agent](agents.md).
