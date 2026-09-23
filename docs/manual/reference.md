# Reference

The whole surface in one place: every command, endpoint, parameter, tape verb, exit code,
header and sidecar key. The other pages teach these in the order you need them; this one is
for looking things up.

`frontend/test_contract.py` holds this page equal to the code. The CLI block is compared
with the text the binary prints, the endpoint table with the router, the parameter tables
with `Params.java`, and the header list with what `HttpServer.java` sends. When a test
fails, the page is wrong, or the code changed without it.

Two sources on a running system say the same things and are always current:
`deskcam help` prints the CLI text below, and `deskcam api` prints the phone's own
description of its endpoints, parameters and tape verbs.

## CLI

This is the text `deskcam help` prints, copied verbatim.

```
deskcam - control the bench camera over HTTP

  deskcam snap [-o FILE] [k=v ...]     full-resolution still, cropped to the ROI
  deskcam frame [-o FILE] [k=v ...]    fast preview-resolution frame
  deskcam raw [-o FILE] [k=v ...]      full-sensor RAW as a DNG, for measurement work
  deskcam burst N [-o DIR] [k=v ...]   N frames with identical settings, into a directory
                                       add format=raw for DNG frames
  deskcam focussweep [-o DIR] [from=D to=D steps=N]
                                       walk the lens and keep a still at each step, for
                                       focus stacking. The steps are equal in dioptres,
                                       which is equal in depth of field. To find one
                                       sharpest position instead, see deskcam focus hunt
  deskcam walk vary=NAME values=A,B,C  one still at each value, e.g. vary=torch
                                       values=0,10,20,45. You supply the values; the two
                                       axes with a step rule of their own are below
  deskcam bracket [-o DIR] [base=1/240 stops=4]
                                       stills at doubling exposures, for merging a lit
                                       panel against a dark bezel. Set the base to one
                                       period of the panel's PWM
  deskcam stream [-o FILE] [n=N]       MJPEG stream (default 30 frames to a file).
                                       A stream is a view: it takes fps, n, w, h and
                                       jpegq, and refuses anything that would change
                                       the camera. Use deskcam set for those.

  deskcam script run FILE [-o DIR]     run a tape of verbs as one operation. Holds the
                                       camera for its duration, so nothing can change it
                                       between two steps. One line per step, each capture
                                       written as it arrives. Non-zero if it did not
                                       finish; deskcam api lists the verbs

  deskcam status                       full JSON state
  deskcam show                         one-line summary
  deskcam show sharpness=1             the same, with a fresh sharpness reading. Move the
                                       focus, read the number, repeat: the peak is focus
  deskcam set k=v [k=v ...]            apply any control parameters
  deskcam reset                        restore defaults
  deskcam recall FILE.json             restore the settings of a past capture

  deskcam aatest [k=v ...]             two captures, same settings. Prints the smallest
                                       difference a measurement can honestly claim, and
                                       records it. analyse linearity refuses steps inside it.
  deskcam scale FILE [--pitch-mm N]    px/mm from a rule or graph paper in the frame, and
                                       records it, so later captures with the same framing
                                       carry it in their sidecars
  deskcam measure FILE X1,Y1 X2,Y2     millimetres between two points of a capture, using
                                       the scale in its sidecar
  deskcam calibration FILE [--write DIR] [--against RECORD]
                                       the printed mat's millimetres against the sensor,
                                       from its coded markers. --write records it,
                                       --against says how far the view has moved since
  deskcam analyse scale FILE          the measurement without recording it
  deskcam analyse linearity DIR        pixel value against exposure
  deskcam analyse burst-noise DIR      how far averaging a burst lowers the noise
  deskcam analyse average DIR          average a burst into one 16-bit image
  deskcam analyse stack DIR            one image sharp at every depth, from a focus sweep
  deskcam analyse hdr DIR              one linear image from a bracket, on the real exposures

  deskcam zoom N                       set zoom (1.0 = full sensor)
  deskcam pan up|down|left|right [amt] nudge the view (default 0.25)
  deskcam center                       recentre
  deskcam af                           one autofocus sweep
  deskcam focus METRES|auto            manual focus distance
  deskcam focus at FX,FY               autofocus on a place in the picture you can see,
                                       each from 0 to 1, and leave the framing alone.
                                       Look at a frame, see where it is soft, say where
  deskcam mark at FX,FY[,FW,FH] [label=TEXT] [by=WORD]
                                       point at a place in the picture you can see, a spot
                                       or a box by its centre and size. Changes nothing on
                                       the camera. Both live views draw it
  deskcam mark list                    every mark on the phone as JSON, in the coordinates
                                       cx and cy use, with in_crop. The only way to see one
                                       drawn on the phone's own page
  deskcam mark clear [ID|all]          remove one mark, or all of them
  deskcam log [N] [via=console|cli] [op=NAME,...] [--json]
                                       the last N operations from the journal, by anybody
  deskcam log wait [op=NAME,...] [timeout=120]
                                       return when a person next does something at the
                                       console, with what they did as JSON. op=mark waits
                                       for them to point, and skips their zooming and
                                       panning. Exit 2 if nothing came
  deskcam focus hunt [from=D to=D]     walk the lens on the phone, print the curve, and
                                       leave it at the sharpest position. Fix the exposure
                                       first, or the hunt climbs the exposure loop. Exits
                                       non-zero, and puts the focus back, when the curve
                                       has no peak in the range
  deskcam exposure VALUE               1/120, 8ms, 250us, 0.5s
  deskcam iso N                        manual sensitivity
  deskcam auto                         back to auto exposure and focus
  deskcam torch 0-45|off|max           rear LED brightness

  deskcam cameras                      list cameras and capabilities
  deskcam api                          machine-readable API description
  deskcam open                         open the web control panel
  deskcam serve [PORT] [--apk FILE]    local console on port 9000 with two codes for
                                       the phone: one installs the app, one pairs it.
                                       It hands out this clone's build if there is one,
                                       otherwise the latest release; --apk release
                                       always points at the release
  deskcam token new|show|set|clear     make, show, adopt or remove the access key

  deskcam use URL [KEY]                remember a target, e.g. http://192.168.86.120:8080
  deskcam usb [PORT]                   tunnel over USB via adb and use that
  deskcam wifi                         switch back to the device's Wi-Fi address
  deskcam start | stop                 start or stop the service on the phone (needs adb)
  deskcam which                        print the current target
  deskcam version                      print this CLI's version

Any command also accepts k=v words, applied before the image is taken:
  deskcam snap zoom=6 cx=0.3 cy=0.7 torch=25 exposure=1/120

Camera state persists until you change it (zoom, cx, cy, focus, exposure, iso, torch,
awb, measure, rotate). Presentation applies to one request and is then forgotten
(w, h, jpegq). deskcam api prints the whole list.

Any command also accepts --why "TEXT": what it is for, in your own words. It is written
into the record beside each capture and is never sent to the phone. The record also says
which directory and project asked. A scratch directory is in no project, so name one with
DESKCAM_PROJECT, and name a run of work with DESKCAM_SESSION.

Every command that takes a picture or changes the camera is written down in a journal,
with who asked, what came back, and a thumbnail, wherever the files went. A refusal is
written down too. It lives in ~/.local/state/deskcam/journal, or DESKCAM_JOURNAL.

Environment: DESKCAM_URL, DESKCAM_TOKEN, DESKCAM_TIMEOUT, DESKCAM_SHOTS, DESKCAM_SERIAL,
             DESKCAM_PROJECT, DESKCAM_SESSION, DESKCAM_JOURNAL
```

### Environment

| Variable | What it does |
|---|---|
| `DESKCAM_URL` | The phone's address. Overrides what `deskcam use`, `usb`, `wifi` or pairing saved. The default is `http://127.0.0.1:8080` |
| `DESKCAM_TOKEN` | The access key. Overrides `~/.config/deskcam/token` |
| `DESKCAM_TIMEOUT` | The CLI's own HTTP timeout, in whole seconds. The default is 30 |
| `DESKCAM_SHOTS` | Where captures go when there is no `-o`. The default is the current directory |
| `DESKCAM_SERIAL` | Which phone `adb` talks to, when more than one is plugged in |
| `DESKCAM_PROJECT` | The project a capture is recorded against, when the directory is in no repository |
| `DESKCAM_SESSION` | The name of a run of work. Falls back to `AGENT_SESSION_ID` when a harness sets that |
| `DESKCAM_JOURNAL` | Where the journal lives. The default is `~/.local/state/deskcam/journal` |
| `DESKCAM_ANALYSIS` | Where the Python measurement tools are, when they are not beside the binary |

Configuration lives in `~/.config/deskcam/`, or `$XDG_CONFIG_HOME/deskcam/`. The token file
there is mode 0600.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | It worked, or the measurement was made |
| 1 | An error: the phone could not be reached, a parameter was wrong, a tool could not run |
| 2 | A refusal. A measurement tool whose confidence was below its limit, or `deskcam log wait` with nothing to report. No number is printed |

## Endpoints

Every operation is an HTTP GET, except `/api/script`, which is POST. With a key set, every
request carries `?token=KEY` or an `Authorization: Bearer KEY` header.

| Endpoint | Method | Returns | What it does |
|---|---|---|---|
| `/api/status` | GET | `application/json` | The settings, the limits, what the sensor measured, orientation, heat and battery |
| `/api/still` | GET | `image/jpeg` | A full-resolution still, cropped to the ROI |
| `/api/raw` | GET | `image/x-adobe-dng` | The whole sensor as a DNG, with its calibration metadata |
| `/api/burst` | GET | `application/x-tar` | `n` stills with identical settings, in one archive |
| `/api/frame` | GET | `image/jpeg` | A preview-sized frame, fast |
| `/api/stream` | GET | `multipart/x-mixed-replace` | An MJPEG stream, slowed when the phone is hot |
| `/api/set` | GET | `application/json` | Apply parameters and return the new state |
| `/api/af` | GET | `application/json` | One autofocus sweep |
| `/api/focussweep` | GET | `application/x-tar` | `steps` stills walking the lens from `from` to `to`, in dioptres |
| `/api/focushunt` | GET | `application/json` | Walk the lens on the phone and leave it at the sharpest position, or refuse |
| `/api/bracket` | GET | `application/x-tar` | `stops` stills, each twice the exposure of the one before, from `base` |
| `/api/walk` | GET | `application/x-tar` | One still for each of `values` of the parameter `vary` |
| `/api/script` | POST | `multipart/mixed` | Run a tape as one operation, streaming a JSON event per step and each capture |
| `/api/reset` | GET | `application/json` | Every setting back to its default |
| `/api/marks` | GET | `application/json` | The marks, kept on the sensor so they survive zoom, pan and rotate. `mark=` adds one, `unmark=` removes |
| `/api/orientation` | GET | `application/json` | Tilt to gravity, roll, pitch and ambient light |
| `/api/cameras` | GET | `application/json` | Each camera and its capabilities |
| `/api/shadingmap` | GET | `application/json` | The lens shading map, after `shadingmap=on` |
| `/api/help` | GET | `application/json` | The phone's description of itself, generated from `Params.java` |
| `/api/nettest` | GET | `application/json` | The phone opens a connection back to the caller, to prove it can |
| `/` | GET | `text/html` | The phone's camera page |

## Parameters

Any parameter works on any endpoint that takes parameters, and is applied before the
picture is taken. `/api/stream` is the exception: it takes `fps`, `n`, `w`, `h` and
`jpegq`, and refuses anything that would change the camera. A name the phone does not
know, or a value it cannot read, is a 400 and never a silent default.

### Camera state

These persist until something changes them.

| Parameter | Meaning |
|---|---|
| `camera`, `cam` | The camera id. The rear camera is `0` |
| `zoom`, `zoomby` | The software zoom, where `1.0` is the whole sensor, or a factor to multiply it by |
| `cx`, `cy` | The centre of the crop, 0 to 1 across the whole sensor |
| `dx`, `dy` | A relative move, in fractions of the current crop's width |
| `af` | `off`, `auto`, `macro`, `continuous`, `video`, or `edof` |
| `focus`, `focusm` | The manual focus in dioptres, or in metres |
| `focusbox` | Where focus is judged, as `cx,cy,w,h` of the frame, or `off` to follow the crop |
| `ae` | `on` or `off` |
| `exposure`, `shutter` | `1/120`, `8ms`, `250us`, `0.5s`, or nanoseconds. Sets `ae=off` |
| `iso`, `sensitivity` | The sensitivity. Sets `ae=off` |
| `ev`, `aelock` | The exposure compensation and the lock, while `ae=on` |
| `awb`, `awblock` | The white balance mode and the lock |
| `awbgains` | `R,GE,GO,B`, or `neutral` for 1,1,1,1, or `auto`. Implies `awb=off` |
| `torch` | `0` to `torch_max_level`, or `off`, `on`, or `max` |
| `measure` | `on` switches off every non-linear stage of the pipeline, for measurement |
| `shadingmap` | `on` asks the camera to report its lens shading map |
| `rotate` | `0`, `90`, `180`, or `270`. Turns the pixels, for a phone mounted that way |
| `previewsize`, `stillsize` | The capture sizes. Changing one rebuilds the capture session |

### Presentation

These describe how one picture is delivered, apply to the request that names them, and are
then forgotten. A resize never carries into the next capture, and never pushes it off the
untouched-JPEG path.

| Parameter | Meaning |
|---|---|
| `w`, `h` | Resize after the crop. One of them keeps the aspect ratio |
| `jpegq`, `quality` | The JPEG quality. The default is 92 |

`rotate` is camera state, not presentation, because it describes how the phone is mounted
(decision D9).

### Router

These steer one request and are not camera state.

| Parameter | Meaning |
|---|---|
| `reset` | `reset=1` puts every setting back to its default before the rest of this request |
| `settle` | Milliseconds to wait after a change, before the capture. 0 to 5000. After a camera change the default is 350 with auto exposure on and 120 otherwise, and 0 when nothing changed. A hunt waits 150 at every step, and a walk 300 |
| `timeout` | Milliseconds to wait for the capture itself. 100 to 60000 |
| `fresh` | Preview frames to discard after a change. The default is 2 |
| `n` | The burst length, or the frame limit of a stream |
| `fps` | The stream rate, 0.1 to 30 |
| `wait` | The wait after an autofocus sweep |
| `port` | The port for `/api/nettest` |
| `format` | `format=raw` makes `deskcam burst` take DNG frames one at a time |
| `sharpness` | `sharpness=1` makes `/api/status` read one fresh preview frame first |
| `from`, `to`, `steps` | The focus sweep: first and last lens position in dioptres, and how many frames |
| `coarse`, `fine` | The focus hunt: readings over the whole range, and readings around the best of them |
| `base`, `stops` | The bracket: the shortest exposure, and how many frames of twice the one before |
| `vary`, `values` | The walk: which camera parameter to vary, and the list to vary it over |
| `mark`, `unmark` | A mark to add, `cx,cy` for a point or `cx,cy,w,h` for a box, and an id to remove or `all` |
| `label`, `by` | The words on the mark being added, at most 80 characters, and one word for who made it |

### Limits

These are one phone's numbers, and `deskcam status` reports the ones for yours in its
`limits` block. Read live from the bench Pixel 6a on 2026-09-13:

| Limit | Pixel 6a | What it governs |
|---|---|---|
| `burst_max` | 31 | `n` on a burst, `steps` on a sweep, `stops` on a bracket, and the count of `values` on a walk |
| `iso_range` | 56..7111 | `iso`. Above `max_analog_iso`, 444 here, the gain is digital and adds no light |
| `exposure_human_range` | 53.7us .. 10.177s | `exposure`. A long one needs `timeout=` raised to match |
| `min_focus_diopters` | 10.2, which is 98 mm | The near end of `focus`, and the default `to=` of a sweep or a hunt |
| `max_zoom` | 63 | `zoom`. Past about 8 the crop holds too few pixels to be useful |
| `max_output_edge`, `max_output_pixels` | 2896, 8388608 | `w` and `h`. A larger resize is refused before anything is captured |
| `ev_range`, `ev_step` | -24..24, 0.167 | `ev`, in sixths of a stop, while `ae=on` |
| `torch_max_level` | 45 | `torch` |
| `raw_black_level`, `raw_white_level` | 64, 1023 | The floor and ceiling of a DNG pixel |

## Tape verbs

A tape, run by `deskcam script run FILE` or `POST /api/script`, is one verb per line.

| Verb | Is the endpoint |
|---|---|
| `SNAP` | `/api/still` |
| `FRAME` | `/api/frame` |
| `RAW` | `/api/raw` |
| `BURST` | `/api/burst` |
| `BRACKET` | `/api/bracket` |
| `FOCUSSWEEP` | `/api/focussweep` |
| `WALK` | `/api/walk` |
| `FOCUSHUNT` | `/api/focushunt` |
| `SET` | `/api/set` |
| `RESET` | `/api/reset` |
| `AF` | `/api/af` |
| `STATUS` | `/api/status` |
| `WAIT` | none. `WAIT 500` pauses for 500 ms |

Each verb takes the same `k=v` words its endpoint takes. A line starting `#` is a comment.
[Working with an agent](agents.md#run-a-sequence-as-one-operation) covers how a tape
behaves.

## Errors

A parameter the phone does not know, a value it cannot read, or one out of range is an HTTP
400 with a JSON body:

```json
{
  "ok": false,
  "error": "unknown parameter 'foo'"
}
```

| Status | When |
|---|---|
| 400 | A bad parameter or value, or a tape that does not parse. Nothing was changed |
| 401 | A key is set on the phone and the request did not carry it |
| 409 | A tape is running and holds the camera, and this request would have changed it |
| 500 | The capture failed, including one that did not arrive inside `timeout` |
| 503 | The phone is already serving as many requests as it will take. Try again |
| 206 | A burst came back with fewer frames than asked for |

The CLI prints the phone's reason, which names the bad parameter.

`/api/status` reports the lens shading map as three fields of its `sensor` block.
`sensor.shading_map_supported` is the judgement and the one to act on.
`sensor.shading_map_key_advertised` is what the camera claims, and
`sensor.shading_map_seen` is whether a map has actually arrived since the camera opened.
On the Pixel 6a the two disagree: the camera leaves the map out of the keys it advertises
and then delivers a full 25 by 33 map in every frame with the mode on. When
`/api/shadingmap` has nothing to hand back it says which situation it is in, because the
mode being off and the camera refusing are different problems.

## The sidecar

Every capture writes `NAME.json` beside the image, and the same record goes into the
JPEG's EXIF `UserComment`, or a DNG's `ImageDescription`. It comes from the
`X-DeskCam-Provenance` header of the reply that carried the picture, so it describes that
frame and not the camera a moment later.

This one was written by `deskcam snap -o board.jpg zoom=4 cx=0.5 cy=0.5` on 2026-09-11,
with the camera on automatic exposure and focus. Every key is as the tool wrote it:

```json
{
  "bytes": 123051,
  "capture_path": "decoded_and_reencoded",
  "captured_at": "2026-09-11T22:19:49.565922+10:00",
  "from": "the capture itself",
  "height_px": 756,
  "image": "board.jpg",
  "measured": {
    "ae_state": 2,
    "af_state": "focused",
    "awb_gains": [
      2.001,
      1,
      1,
      1.769
    ],
    "colour_transform": [
      1.574,
      -0.441,
      -0.129,
      -0.16,
      1.438,
      -0.273,
      0.035,
      -0.605,
      1.57
    ],
    "exposure_human": "30.02ms (1/33)",
    "exposure_ns": 30016818,
    "focus_diopters": 7.81,
    "focus_metres_approx": 0.128,
    "iso": 276,
    "sensor_timestamp": 190042114712799
  },
  "orientation": {
    "aim": "tilted; a flat subject on a level surface will be measurably skewed (16.18 degrees from gravity)",
    "ambient_lux": 94.76,
    "available": true,
    "gravity": {
      "x": -2.73,
      "y": 0.12,
      "z": 9.42
    },
    "measures": "the angle between the optical axis and gravity, averaged over 32 samples. It equals the angle to a flat subject only when the subject lies on a level surface.",
    "pitch_degrees": 0.74,
    "roll_degrees": -16.17,
    "samples": 32,
    "tilt_degrees": 16.18
  },
  "pipeline": {
    "aberration": "off",
    "edge": "high_quality",
    "hot_pixel": "fast",
    "noise_reduction": "high_quality",
    "ois": "on",
    "shading": "fast",
    "tonemap": "fast",
    "tonemap_points": 64
  },
  "settings": {
    "ae": "auto",
    "ae_lock": false,
    "af": "continuous",
    "awb": "auto",
    "awb_gains_set": null,
    "awb_lock": false,
    "camera": "0",
    "capture_path": "decoded_and_reencoded",
    "cx": 0.5,
    "cy": 0.5,
    "ev": 0,
    "exposure_ns": null,
    "focus_box": null,
    "focus_diopters": null,
    "iso": null,
    "jpeg_quality": 92,
    "measure": false,
    "out_h": null,
    "out_w": null,
    "preview_size": "1280x960",
    "preview_size_requested": "1280x960",
    "rotate": 0,
    "shading_map": false,
    "still_size": "max",
    "torch": 0,
    "zoom": 4
  },
  "target": "http://192.168.86.191:8080",
  "tool": "DeskCam",
  "width_px": 1008
}
```

| Block | What it holds |
|---|---|
| `settings` | What was asked for. `null` means the automatic mode was in charge |
| `measured` | What the sensor reported for this frame, from the capture result |
| `pipeline` | What the camera applied. With `measure=1` every entry reads `off` and the tone map is linear |
| `orientation` | Tilt to gravity, averaged over 32 samples. `"available": false` and nothing else on a phone with no gravity sensor |
| `scale` | Present once `deskcam scale` has recorded pixels per millimetre, with `applies` saying whether it still describes this framing |
| `asker` | Who asked, and why. See below |

`capture_path` is `camera_jpeg` when the camera's own JPEG was sent untouched, which
happens at zoom 1.0001 or below with no rotation and no resize, and
`decoded_and_reencoded` otherwise. Two captures on opposite sides of that line are
different kinds of image.

`focus_metres_approx` is one over the lens position in dioptres. The camera reports its
focus calibration as `APPROXIMATE`, and on the bench phone it has run 15 to 20% long, so it
is not a distance. [Measuring](measuring.md#distance-to-the-subject) says how to get one.

There is no thermal or battery block in a sidecar. That state is in `/api/status` and on
the stream headers.

### The asker block

This one is from `deskcam snap --why "D19 step 1 live check"`, run in a scratch directory
on 2026-09-19, as the tool wrote it:

```json
"asker": {
  "command": "deskcam snap --why 'D19 step 1 live check'",
  "cwd": "/tmp/claude-1000/-home-matt-Projects-AndroidDeskcam/3e50d8c0-8938-46fa-8d23-129612e68bcb/scratchpad/live1",
  "session": "20260915_4",
  "via": "cli",
  "why": "D19 step 1 live check"
}
```

| Key | Where it comes from |
|---|---|
| `command` | The command as typed, with any key replaced by `token=***` |
| `cwd` | The directory it was run in |
| `project` | The nearest directory above `cwd` holding a `.git`, or `DESKCAM_PROJECT`. Never guessed from a name, so it is missing above |
| `session` | `DESKCAM_SESSION`, or `AGENT_SESSION_ID` |
| `via` | `cli` or `console`: how the request arrived, not whether a person or an agent sent it |
| `why` | The text given to `--why` |

A key with no value is left out.

## The journal

Every command that takes a picture or changes the camera writes one JSON file into the
journal, `~/.local/state/deskcam/journal` or `DESKCAM_JOURNAL`, so two writers at once need
no lock. An entry holds the time, the duration, the operation and its parameters, the exit
code, the `asker` block, and for each file produced its absolute path, its size, and a
copy of its sidecar and its thumbnail. A refusal is journalled as fully as a capture, with
the phone's words in `error`.

Reading the camera (`status`, `show`, `api`) is not journalled. The access key is replaced
by `token=***` wherever it appears. The newest 2,000 entries are kept, and a journal that
cannot be written never fails a capture.

The operation is the command as it was typed, such as `snap`, `zoom`, `torch` or
`recall`. What a person does at the console is written in the same words, so one name
means one thing whichever page did it:

| At the console | Journalled as |
|---|---|
| Snap, Focus hunt, Measure, Normal, Shoot this again | The CLI command each one runs: `snap`, `focus`, `set` |
| Zoom, pan, drag a box, the sliders and menus | `set` |
| Reset all | `reset`, then `unmark` |
| Autofocus, Focus on the crop | `af` |
| A double tap | `focus` |
| Shift-drag, shift-click, Mark this view | `mark` |
| Clear | `unmark` |

Entries written before the console used these names may say `marks` or `af` instead.

## Response headers

Which headers a response carries depends on the endpoint. `HttpServer.java` is the source.

* `X-DeskCam-Provenance`: on `/api/still`, the frame's record as one line of JSON, the same content as the sidecar. Left out when it would exceed 7000 bytes.
* `X-DeskCam-ROI`: on `/api/raw`, the framing that was asked for. A DNG carries the whole sensor, so the crop is reported rather than applied.
* `X-DeskCam-Frames` and `X-DeskCam-Frames-Requested`: on `/api/burst`, how many frames came back against how many were asked for. A short burst answers 206. `/api/focussweep`, `/api/bracket` and `/api/walk` send `X-DeskCam-Frames` alone.
* `X-DeskCam-Millis`: on `/api/burst`, `/api/focussweep`, `/api/bracket` and `/api/walk`, the wall-clock time for the whole capture.
* `X-DeskCam-Fps`: on `/api/burst`, the rate the frames were actually taken at. On each part of `/api/stream`, the rate that part was sent at after shedding.
* `X-DeskCam-Thermal`: on each part of `/api/stream`, the platform thermal state as one word: `none`, `light`, `moderate`, `severe`, `critical`, `emergency`, `shutdown`. It is `unknown` before the platform has reported, and `level N` for a value this build does not know. Parse for all nine.
* `X-DeskCam-Shedding`: on a stream part, and only once the rate has been cut, one sentence saying what the level means and what to do. `emergency` and `shutdown` both say to stop the session.
