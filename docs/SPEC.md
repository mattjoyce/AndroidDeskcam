# DeskCam specification

Status: retrospective. This document describes a system that exists and runs. It gives
the reasons for the design. It defines the contract between the two halves.

Version 0.1. The target device is a Pixel 6a (`bluejay`). The device runs GrapheneOS,
Android 17, API 37.

Language: ASD-STE100 Simplified Technical English.

## 1. Purpose

DeskCam makes a spare Android phone into an instrument for desk work. Three jobs control
each decision in this document.

1. OLED design work. The camera must report the light that the panel emits. It must not
   report an image that looks good.
2. Circuit debug work. The camera must show small parts. It must focus at a short
   distance.
3. General desk capture. The camera must be quick. It must need no set-up steps.

The camera is first an instrument. It is second a camera. If the two aims disagree, the
instrument aim wins.

## 2. Primary consumer

**The primary consumer is Claude Code. The primary consumer is not a person.** A person
who uses the browser panel is a secondary consumer. That case must continue to work. The
design does not optimise for it.

This one assumption gives eight rules. The current build obeys all eight.

| # | Rule | Reason for an agent |
|---|---|---|
| R1 | Each operation is one shell command with a correct exit code | The agent can test for failure. It does not read prose. |
| R2 | A capture command prints a **path**. It does not print image data. | The agent reads the file with its own tools. The transcript stays small. |
| R3 | Each endpoint accepts the full control set as `k=v` | The agent does not have to remember the state. It does not have to ask first. |
| R4 | A response gives the **measured** values. It does not give only the requested values. | The agent tests its own action in the same request. |
| R5 | An unknown parameter is an error. It is not ignored. | A spelling mistake fails immediately. It does not give a wrong image. |
| R6 | The API describes itself at `/api/help` | The agent learns the API. It does not read this repository. |
| R7 | Units are the units in a datasheet (`1/120`, `8ms`, `250us`, `0.12`) | The agent does no unit conversion. |
| R8 | All operations are idempotent | A second attempt is always safe. |

R3 controls the shape of the API more than the other rules. This command is complete:

```sh
deskcam snap zoom=4 cx=0.3 exposure=1/120
```

There is no session. There is no mode to start. There is no necessary order. One command
is the unit of work.

R4 is the second most important rule. `/api/status` gives a `measured` block. The values
come from the `TotalCaptureResult` of the sensor. A request for 1/60 comes back as 16.63
ms at ISO 199. A requested value and a measured value are different things. The API gives
both.

## 3. Architecture

The contract in section 4.5 is the only connection between the two halves. Nothing else
crosses it. You can rewrite one half and not touch the other half.

```
  WORKSTATION (frontend)                 |   PHONE (backend)
                                         |
  Claude Code ──┐                        |
                ├─→ deskcam, one file ─┐ |
  person shell ─┘                      │ |
                                       ├─┼──→ HTTP :8080 ──→ HttpServer
  browser ─────────────────────────────┘ |                      │
     ↑ (the backend sends the page)       |                     ↓
                                         |                 CameraEngine
  desktop processing (planned)           |                      │
   stack / merge / rectify / compare     |                      ↓
                                         |                  Camera2 HAL
```

**Split rule. The backend makes true pixels. The backend reports its own state. The
frontend decides what to do with the pixels.**

Work that needs more than one frame belongs on the workstation. Work that needs floating
point maths belongs on the workstation. Work that needs a library belongs on the
workstation. The workstation has the processor power and the software. The phone is a
sensor with an HTTP socket.

There is one intended exception. The browser panel is frontend code. It is inside the
backend in `WebUi.java`. The backend sends it from `/`. This gives a human interface on
any device on the network with no installation. The panel is a client of the same public
contract. It gets no special access. Thus it does not break the split.

## 4. Backend

The backend runs on the phone. It is Java. It has no external dependencies. It does not
use AndroidX. It does not use Gradle.

### 4.1 Responsibilities

The backend holds the camera device and the capture session. It applies the controls. It
makes JPEG stills, preview frames, and an MJPEG stream. It reports the settings, the
sensor limits, and the measured results. It stays alive when the screen is off.

These tasks are not its work: image stacks, image merges, frame alignment, colour
science, computer vision, and storage of old captures.

**One loop runs on the phone, and the test for a second one is narrow.** The backend
decides nothing about a picture except where to put the lens, in `/api/focushunt`. That
one qualifies because every step of it depends on the frame the step before it produced,
so driven from the workstation each decision costs a round trip: a set, a settle, a fresh
frame and a status apiece. The test for anything else that wants to move here is not
whether it would be convenient or quick. It is whether the loop needs a frame between its
decisions. A focus hunt does. A focus sweep does not, which is why `/api/focussweep` takes
a range and a step count and this takes a range and answers with a decision.

The frames of a hunt do not leave the phone, and neither do the intermediate results. What
comes back is the position chosen, the sharpness there, and the curve that found it. A
hunt is allowed to answer that there is no peak, and does so rather than name the largest
reading it happened to see.

### 4.2 Components

| File | Responsibility |
|---|---|
| `CameraEngine.java` | The Camera2 device, the session, the images, and the status |
| `CamSettings.java` | The control state, the ROI maths, the parameters, and the JSON |
| `HttpServer.java` | HTTP/1.1 on a `ServerSocket`, the routes, and the MJPEG parts |
| `WebUi.java` | The browser panel and the `/api/help` document |
| `Sensors.java` | Gravity and ambient light, giving the angle between the optical axis and gravity, averaged over 32 samples |
| `Sharp.java` | The variance of the Laplacian over a region, which is the sharpness of a frame as one number |
| `Health.java` | What the device says about its own condition: the platform's thermal level, and the battery |
| `Thermal.java` | How much a stream gives up at each thermal level, and what each level means |
| `Hunt.java` | What a focus curve says: the peak, the contrast, and the two shapes that are a refusal |
| `Tape.java` | Reads a script: the verbs, their parameters, and the refusals that cost nothing |
| `Answer.java` | What an endpoint produced, before anything decides whether it is an HTTP response or a part of a script's stream |
| `Tar.java` | A small USTAR writer, for a burst in one response |
| `CamService.java` | The foreground service, the life cycle, the notice, and the address |
| `MainActivity.java` | The permissions, start and stop, and the headless start |
| `BootReceiver.java` | The restart attempt after a reboot. Refer to section 4.4. |

### 4.3 Hardware facts that control the design

These values come from `dumpsys media.camera` on the target device.

**`android.scaler.croppingType = CENTER_ONLY`.** The HAL removes the offset from a crop
rectangle. The HAL can only zoom around the centre. This sensor has no hardware pan.

This gives the most important decision in the backend. The engine always asks for the
**full 4032 x 3024 array**. The engine then crops the region of interest in software.
This gives a pan to any position. Each pixel in a zoomed image stays a true sensor pixel.
The HAL does not enlarge it. At 4x zoom the result is a true 1008 x 756 crop.

The design also uses these properties.

| Property | Value | Use |
|---|---|---|
| Hardware level | `LEVEL_FULL` | Manual control of each frame |
| Capabilities | `MANUAL_SENSOR`, `MANUAL_POST_PROCESSING`, `RAW`, `BURST_CAPTURE` | Measurement mode and roadmap work |
| Exposure range | 53.659 us to 10.177 s | Anti-flicker and brackets |
| Sensitivity range | ISO 56 to 7111 | Manual exposure |
| Max analog sensitivity | **444** | Above this value the gain is digital. Apply digital gain on the workstation. |
| RAW format | 10 bit. Black is 64. White is 1023. RGGB. | Linear capture |
| DNG calibration | The illuminants and the matrices are present | Correct colour on the workstation |
| Min focus distance | 10.204 dioptres (98 mm) | Close work. This is also the macro limit. |
| Focus calibration | `APPROXIMATE` | A sweep must be monotonic. Do not use the absolute values. |
| Metering regions | AF 1, AE 1, AWB 0 | The focus and the metering follow the ROI |
| Lens shading map | Advertised, but NOT delivered | Refer to the note below |
| Torch | 45 steps | Controlled light for the bench |
| High speed video | 1080p120 and 1080p240 | Display timing measurement |
| Rolling shutter skew | Reported for each frame | The PWM frequency from one still image |

**The lens shading map is not usable on this device.** The camera lists
`availableLensShadingMapModes` as `[0, 1]` and gives a `shadingMapSize` of 33 x 25. But
`android.statistics.lensShadingMap` is not one of its capture result keys, so the map never
arrives. The engine tests the result keys at start-up and reports
`sensor.shading_map_supported`. `/api/shadingmap` then gives a clear message instead of an
empty result. Measure a flat field. Do not depend on the map.

This is a general lesson for this device. A mode list says that a control is settable. It
does not say that the result arrives.

### 4.4 Platform facts

Two Android 17 behaviours cost much debug time. This section records them. Do not find
them again.

**Local network access is separate from internet access.** An app can hold `INTERNET` and
reach the internet. At the same time the system drops each local network packet in both
directions. There is no error message. The server looks correct. It answers on loopback.
It never answers a SYN from the LAN.

The correction is `android.permission.ACCESS_LOCAL_NETWORK`. The command
`pm list permissions` does not show this permission. It is in the API 37 `android.jar`.

**A camera foreground service cannot start after a reboot.** A reboot test confirms this.
The system refuses both available methods.

```
ForegroundServiceStartNotAllowedException: FGS type camera not allowed to start from BOOT_COMPLETED
Background activity launch blocked! ... (BAL_BLOCK)
```

`BootReceiver` makes the attempt. `CamService` catches the refusal. Before this
correction, the exception crashed the service again and again. To start the camera after
a reboot, use `deskcam start`. You can also touch the app one time.

`SYSTEM_ALERT_WINDOW` would remove the activity launch limit. That permission is too wide.
The app does not ask for it.

### 4.5 The contract

This is the connection between the halves. It is HTTP/1.1. All operations are GET. There
is no session. There are no cookies. The server sends `Access-Control-Allow-Origin: *`.
The server also accepts POST with a query string or a flat JSON body.

| Endpoint | Result | Notes |
|---|---|---|
| `/api/status` | JSON | The settings, the limits, the geometry, the `measured` block, a `device` block for heat and battery, and a `sharpness` block when a preview frame has been converted. `sharpness=1` converts a fresh one first. |
| `/api/still` | `image/jpeg` | Full resolution. Cropped to the ROI. |
| `/api/raw` | `image/x-adobe-dng` | The full sensor array. The ROI does NOT apply. The header `X-DeskCam-ROI` gives the framing. |
| `/api/burst` | `application/x-tar` | `n` frames with identical settings. The headers give the frame count, the time, and the rate. |
| `/api/orientation` | JSON | The angle between the optical axis and gravity, averaged over 32 samples, and the ambient light. It equals the angle to a flat subject only on a level surface. |
| `/api/shadingmap` | JSON | The lens shading map, if the device delivers one. Refer to section 4.3. |
| `/api/frame` | `image/jpeg` | Preview resolution. Much quicker. |
| `/api/stream` | `multipart/x-mixed-replace` | MJPEG. Use `fps` and `n`. Each part carries `X-DeskCam-Fps` and `X-DeskCam-Thermal`, and `X-DeskCam-Shedding` while the rate is below what was asked for. Refer to decision D15. |
| `/api/set` | JSON | Apply the parameters. Give the result. |
| `/api/reset` | JSON | Set all values to the default. |
| `/api/af` | JSON | Do one autofocus sweep. |
| `/api/walk` | `application/x-tar` | One still at each of `values`, walking the camera parameter named by `vary`. Only camera state can be walked. No step rule is implied: the caller gives the values. |
| `/api/bracket` | `application/x-tar` | `stops` stills at doubling exposures from `base`, so every frame is one stop apart and a whole multiple of the base period. The archive holds `walk.json`, which records the exposure asked for and reached at every frame. |
| `/api/focussweep` | `application/x-tar` | `steps` stills as the lens walks from `from` to `to`, spread equally in dioptres. The archive holds `walk.json`, which records the lens position asked for and reached at every frame. |
| `POST /api/script` | `multipart/mixed` | A tape of verbs, one per line, run as one operation. One JSON event per step, each capture's pixels following its own event as the next part. The phone stores nothing. A script holds the camera: a second script, or any request that would change the camera, is refused with 409 while one runs. Refer to decision D14. |
| `/api/focushunt` | JSON | Walks the lens between `from` and `to`, measures the sharpness of a frame at each position, and stops at the peak. A coarse pass of `coarse` readings over the range, then a fine pass of `fine` around the best of it. No frame crosses the network. It answers the chosen position, the peak, and the whole curve. It refuses, with `ok: false`, when the curve is flat or its peak is at an end of the range, and then puts the focus back. Refer to section 4.1. |
| `/api/cameras` | JSON | List the cameras and the capabilities. |
| `/api/help` | JSON | The self description. Refer to R6. |
| `/api/nettest` | JSON | An outbound test. It finds the fault in section 4.4. It connects only to the address the request came from. |
| `/` | `text/html` | The browser panel |

Rule R3 applies to each endpoint. The server applies the control parameters before it
makes the image. `/api/stream` is the one exception, and it is decision D10.

`/api/burst` answers **206 Partial Content**, not 200, when it produced fewer frames than
were asked for. The headers `X-DeskCam-Frames` and `X-DeskCam-Frames-Requested` give both
numbers. Each capture endpoint also returns `X-DeskCam-Provenance`, which holds the record
of that frame as JSON, so a client never has to ask a second question about a picture it
already has.

**A change meant to alter nothing is checked, not asserted.** `frontend/surface.py` asks a
running phone for every endpoint in this table, refusals included, and compares two
recordings: the status line, the header names, and the type of every field of every JSON
body. Not the values, because two captures of one scene differ in every byte. It fixes the
camera state before recording, since several fields are present only in some states, and
it refuses to pass while `/api/help` advertises an endpoint it does not visit. Run it
across any refactor of the router or the handlers. It was written for the one in D14.

**The parameters live in one place.** `Params.java` declares every name, its group, and
its help text. The parser reads that list and `/api/help` is printed from it, so this
document and the README describe it rather than repeat it. `deskcam api` prints the
current list; if it disagrees with anything written here, it is right and this is stale.

The groups are decision D9:

| Group | Parameters | Life |
|---|---|---|
| Camera state | `camera` (`cam`), `zoom`, `zoomby`, `cx`, `cy`, `dx`, `dy`, `af`, `focus`, `focusm`, `focusbox`, `ae`, `exposure` (`shutter`), `iso` (`sensitivity`), `ev`, `aelock`, `awb`, `awblock`, `awbgains`, `torch`, `measure`, `shadingmap`, `rotate`, `previewsize`, `stillsize` | persists |
| Presentation | `w`, `h`, `jpegq` (`quality`) | one request |
| Router | `reset`, `settle`, `timeout`, `fresh`, `n`, `fps`, `format`, `wait`, `port`, `sharpness`, `from`, `to`, `steps`, `coarse`, `fine`, `base`, `stops`, `vary`, `values`, `t` | one request |

**The coordinate model.** `zoom` is a scale. The value 1.0 is the full sensor. `cx` and
`cy` give the centre of the ROI from 0 to 1. `dx` and `dy` are relative. They use
fractions of the **current** ROI width. Thus one step moves the same visible distance at
each zoom value. The server keeps the ROI inside the frame.

**Errors.** The server rejects an unknown parameter. The server rejects a parameter that
it cannot read. The result is HTTP 400 with `{"ok": false, "error": "..."}`. The server
changes nothing. Refer to R5. This holds on **every** endpoint, including `/api/status`,
`/api/reset`, `/api/af`, `/api/cameras`, `/api/orientation` and `/api/nettest`, which used
to ignore both the parameters and the check. A router parameter with a value the server
cannot read is a 400 as well; nothing falls back to a default in silence.

**Limits.** Each numeric parameter has a range, and `/api/status` reports the ones that
depend on the device under `limits`: `max_output_edge`, `max_output_pixels` and
`burst_max`. They come from the heap this process was given, so a request that cannot fit
in memory is refused before the capture instead of raising an `OutOfMemoryError` during
it. `OutOfMemoryError` is an `Error` and not an `Exception`, so it used to walk past every
handler and stop the service; the handlers now catch `Throwable`.

**Access control.** There is an optional shared token. Send it as `?token=` or as
`Authorization: Bearer`. The token is off by default. There is no TLS. Refer to section 7.

### 4.6 How the backend makes images

There are two paths. They have different costs and different purposes.

**The still path.** The engine sends a `TEMPLATE_STILL_CAPTURE` request to a full
resolution JPEG `ImageReader`. The server can send the JPEG of the camera **without a
change**. This needs three conditions. The zoom is at or below **1.0001**. There is no
rotation. There is no resize. This is the quickest path. It is also the best quality. In
other conditions `BitmapRegionDecoder` reads only the necessary tile. Thus a large zoom
costs less than a small zoom.

**The limit of 1.0001 matters, so each capture records which side of it it was on.** Two
captures that differ only in a zoom of 1.0 against 1.001 are different kinds of image: one
is the camera's own JPEG, the other has been decoded and encoded again. A comparison
across the limit measures the pipeline and not the subject. The settings block of every
capture carries `capture_path`, which is `camera_jpeg` or `decoded_and_reencoded`.

**Each capture carries the result of its own frame.** Every request goes with a
`CaptureCallback`, and a frame is matched to its request by the sensor timestamp the HAL
reports when the exposure starts, never by the order images arrive. Each capture path owns
its own frames, so a still taken during a burst cannot take a frame of the burst. Refer to
decision D8.

**The preview path.** A repeating request fills a YUV_420_888 reader. The engine converts
the frame to NV21. `YuvImage.compressToJpeg(rect, ...)` then crops the frame during the
JPEG encode. The engine makes each crop origin an even number. NV21 chroma has half the
resolution in each direction. An odd origin moves the colour planes.

The engine converts a frame only on demand. If no client reads the stream, and no client
called `/api/frame` in the last two seconds, the engine discards the frame. An idle
service costs almost nothing.

The engine maps the ROI onto sensor coordinates. It then sets the focus rectangle and the
metering rectangle. Thus a zoom onto a part makes the camera focus and meter on that part.

### 4.7 Life cycle

The service is a foreground service of type `camera`. It holds a partial wake lock. This
permits camera access when the screen is off.

The service is not exported. A headless start goes through the exported activity. Android
permits a camera foreground service to start only from the foreground. The activity
satisfies this rule.

```sh
adb shell am start -n dev.deskcam/.MainActivity -a dev.deskcam.START --ez finish true
```

### 4.8 Build

`backend/build.sh` runs `aapt2`, then `javac`, then `d8`, then `zipalign`, then
`apksigner`. It does not use Gradle. It does not use AGP. This is possible because the app
has no external dependencies.

A clean build takes about two seconds. The build needs `platforms/android-37.0` and
`build-tools/37.0.0`.

## 5. Frontend

The frontend runs on the workstation. At this time it is one Bash script. The processing
layer is planned work.

### 5.1 Responsibilities

The frontend shows the contract as commands. It finds and stores the target address. It
controls the device with adb. In the future it does all work on many frames and all
numerical work.

### 5.2 The CLI

The CLI is one static Go binary, built from `frontend/go/`. It finds the target in this
order: the `--url` option, then `$DESKCAM_URL`, then `~/.config/deskcam/url`, then
localhost. Taking a photograph needs no runtime behind it; measuring what is in one needs
`pip install -e '.[analysis]'`, and the binary says so when a tool is missing. Decision D13.

These commands map to the contract: `snap`, `frame`, `stream`, `status`, `show`, `set`,
`reset`, `zoom`, `pan`, `center`, `af`, `focus`, `exposure`, `iso`, `auto`, `torch`,
`cameras`, `api`, `open`.

These commands have no equivalent in the contract. They control the target and the device.
They are workstation work: `use`, `usb`, `wifi`, `which`, `start`, `stop`.

Two behaviours exist only for R1 and R2. A capture command prints the path and nothing
else. Thus `img=$(deskcam snap zoom=4)` is the correct method. And `show` makes the status
into one line. An agent that reads the state must not pay for a full JSON document.

Note the `usb` command. It runs `adb forward`. It then points the target at loopback. This
method does not use the local network permission. Use it when Wi-Fi fails.

### 5.3 The browser panel

The backend sends the panel from `/`. The panel shows live MJPEG. Touch the image to move
the centre. Turn the wheel to zoom. The panel maps a touch through the current ROI. Thus a
touch has the same result at each zoom value.

The panel has controls for the focus, the exposure, the white balance, and the torch. It
exists to aim the camera. A pointer is easier than coordinates for this task.

### 5.4 Desktop processing

`frontend/analysis/` holds the measurement tools: the noise floor of the instrument, the
linearity of the response, the reduction of noise by averaging a burst, and the scale in
pixels per millimetre. They need NumPy and Pillow, declared as the optional `analysis`
extra, because taking a photograph needs neither and the packaging should say so.

Three properties, and they are decision D12:

**Nothing here speaks HTTP.** These read captures and sidecars off disk. Driving the camera
is the CLI's job. That keeps the seam clean and means the tools work on captures taken last
month, or by somebody else.

**Every result carries its value, its interval, its sample count and its confidence
together**, and a tool refuses below a stated limit rather than printing a number with a
caveat beside it. A caveat beside a number does not travel with the number; a missing
number does. The type in `analysis/result.py` enforces it: a refused measurement cannot
hold a value.

**Every limit is written down with the evidence for it**, in the module that applies it, so
it can be argued with. See `scale.PEAK_LIMIT` for the clearest case.

Exit codes are 0 for a measurement, 2 for a refusal, 1 for a tool that could not run, and
`--json` gives the full record. That is what a caller acts on, rather than parsing English.

The rest of the roadmap in section 8, the merging and stacking work, still does not exist.
The plan for it remains Python with OpenCV, rawpy and NumPy.

## 6. Decisions

**D1. The software makes the ROI. The hardware does not.** `CENTER_ONLY` makes this
necessary. It is also better, because it keeps true sensor pixels. Refer to section 4.3.

**D2. The backend has no dependencies.** This makes the build without Gradle possible. The
build is then repeatable in seconds from a shell. A written HTTP server is about 400 lines.
It gives direct control of the MJPEG parts.

**D3. All operations are GET.** This does not agree with REST. It is correct here. The
primary consumer writes URLs in a shell. A change of state as a GET is easy to script and
easy to repeat.

`/api/script` is the one POST, and it is the exception that keeps the rule: a tape of verbs
is a document and not a set of parameters, and there is no query string that expresses one.
Every step inside it is written exactly as the GET it corresponds to.

**D4. Each status response gives the measured values.** Refer to R4. `/api/status`
reports the values of the **preview**, and says so in `measured_from`, because the
repeating preview request is the only thing it can describe. A capture describes itself.

This decision used to claim the values cost nothing because the engine already held the
`TotalCaptureResult`. It held the wrong one: every capture passed `null` as its callback,
so each sidecar and each EXIF comment described a preview frame, which runs noise
reduction and edge enhancement at `_FAST` where a still runs them at `_HIGH_QUALITY`.
Decision D8 corrects it, and it does cost a callback per capture.

**D5. An unknown parameter is an error.** If the server ignored it, the agent would get an
unchanged image. The agent would then think that the setting applied.

**D6. A pan step uses ROI widths. It does not use frame fractions.** Thus `pan left` has
the same result at 1x and at 8x. A person and an agent both expect this.

**D7. The engine converts a preview frame only on demand.** The count of watching clients
is an atomic counter, because a read-then-write on a plain field could lose an update and
leave the count above zero for ever, which defeats the decision quietly.

**This decision covers less than its wording once suggested.** It said "a bench camera that
runs all day must cost nothing when nobody looks at it", and what it actually stops is one
CPU cost: the YUV to NV21 conversion. The sensor, the ISP and the HAL carried on at 29
frames a second for the life of the service, and a phone left running overnight was found
at the platform's `severe` thermal level because of it. A decision that answers a narrower
question than its title suggests is harder to notice than no decision at all. What that
sentence was reaching for is now D16, and this one is about the conversion alone.

**D8. A capture describes itself.** Every capture request carries its own
`CaptureCallback`, and a frame is paired with its result by sensor timestamp. The record
comes back with the picture, in `X-DeskCam-Provenance` and in the EXIF `UserComment`, so
the CLI never asks a second question about a moment that has already passed. Two captures
running at once cannot exchange their records, and a still during a burst cannot take a
frame of the burst.

**D9. Camera state persists. Presentation lives for one request.** Rule R3 says a client
never has to remember the state, and that is what makes the CLI easy to use, so
persistence stays. The division is what changed.

| Kind | Examples | Persists |
|---|---|---|
| Camera state | zoom, cx, cy, focus, exposure, iso, torch, awb, measure, rotate | yes |
| Presentation | `w`, `h`, `jpegq` | no, one request only |

`w` and `h` describe how one picture is returned. When they persisted they silently
rescaled the next capture and disabled the untouched-JPEG path for ever, which is a
measurement fault and not a convenience fault.

`rotate` is **camera state**, because it describes how the phone is bolted down and not
how one picture is presented. A person who mounts the phone upside down sets it once. It
follows that `deskcam recall` restores it, and that `deskcam show` prints it.

**D10. A stream is a view, never a way to set the camera.** `/api/stream` takes `fps`,
`n`, `w`, `h` and `jpegq`. A parameter that would change the camera is refused with an
HTTP 400 naming `/api/set`. Before this, a browser tab wrote its own rotation into the
shared state on every stream reconnect, so opening the console changed the next capture an
agent took.

**D11. The server starts before the camera.** The part that reports a fault must not stop
with the part that has the fault. A camera another app was holding at start time used to
take the whole service down, so a client saw a refused connection and no reason, while the
same camera lost one second later was recovered by the running engine within fifteen. The
engine now reports `state` and `last_error` on `/api/status` and keeps trying. The watchdog
runs on a thread of its own, not on the camera handler, and its test is that frames are
still arriving rather than that the device handle is not null; a camera that is open and
delivering nothing is what heat throttling looks like.

**D12. A measurement refuses rather than guesses.** Three wrong measurements were made in
one session and every one had the same shape: a method produced a number, nothing knew how
large a difference had to be before it was a difference, and the number was published. The
tools in `frontend/analysis/` return a confidence with every value and refuse below a
stated limit. The same-against-same test measures what the instrument cannot tell apart and
records it beside the captures, and the other tools enforce it.

This is not theoretical. The first implementation of the burst noise tool reported 3.18x
for an average of six frames, which is above the square root of six and therefore
impossible, and it reported maximum confidence while doing so, because its interval was
tight and its estimator was biased. Precision is not correctness, so the gate also knows
the physical bound.

**D13. One static binary on the workstation, Java on the phone, Python for the maths.**
Three languages, and each one is there for a reason that cannot be argued away by taste.

The workstation half was a Bash script that shelled out to `python3` five times for JSON,
so Python was required for every operation including taking a photograph, plus a Python
console with its own dependency for QR codes. It is one Go binary now: drop a file on the
PATH and a photograph needs no runtime at all.

The phone stays Java because four framework classes do the load-bearing work and none has
an NDK equivalent. `DngCreator` writes the DNG with the sensor calibration matrices out of
the capture result, which is the whole RAW story. `BitmapRegionDecoder` decodes only the
requested tile, which is why a deep zoom costs less than a shallow one. `ExifInterface` and
`YuvImage` are the same argument, smaller. Underneath that, camera access is granted per
app UID, so a binary run from `adb shell` cannot open the camera at all, and a foreground
service of type `camera` must be a Java `Service`.

`frontend/analysis/` stays Python because it is array maths over image data, which is
NumPy's job. In Go that means cgo bindings to OpenCV, so the C toolchain comes back and
nothing is gained. The seam holds because the two halves talk through files: a directory of
captures and their sidecars, with no database to keep in step.

The cost of the split is one boundary, and it is drawn where the work changes kind rather
than where the languages happen to differ. Card 53.

**D14. A script holds the camera, and answers in one stream.** `/api/script` takes a tape
of verbs as plain text and runs it as one operation. The argument for it is not speed. A
round trip on this LAN is about 100 ms against a settle and a capture of several hundred,
so a seven step sweep run from the shell loses well under a second to the network. The
argument is that every multi-step sequence driven from outside is **racy**: nothing stops
the console, which polls the state every two seconds and can post new settings, or a
second agent, from changing the camera between a `SET` and a `SNAP`. The walk endpoints
avoid that by doing a whole sequence inside one request. A script generalises it, so while
a tape runs, a second script and any request that would change the camera are refused with
**409**. Reads are not: watching a tape run does not interfere with it.

The answer is `multipart/mixed`, not server-sent events, and that follows from section 4.1.
An events-only stream would have to name a file on the phone for each capture, which means
storage, a cleanup policy, disk-full behaviour, a listing endpoint and a download endpoint.
Instead the JSON events and the pixels travel in one ordered stream on the machinery the
MJPEG stream already uses, and the phone still stores nothing.

**A tape is not a language, and will not become one.** No branching, no variables, no
labels, no arithmetic. The caller is the intelligence; the tape is the execution record.
That is what keeps the objection to a scripting DSL answered rather than ignored: the step
rule is the knowledge, and a tape with no ranges and no steps cannot make a wrong rule as
easy to write as a right one. `BRACKET base=1/240 stops=4` leaves that knowledge in
`/api/bracket`, where the reasoning about PWM periods lives.

A step that fails ends the tape, the camera goes back to where the tape found it, and the
last event says what failed and what it was put back to. A tape that finishes is left
where it put the camera, because the `SET` lines of a finished tape are changes the caller
asked for. A verb whose own answer says `ok: false`, which today is only a focus hunt that
found no peak, is a failed step: carrying on would take the next capture out of focus and
report it as a success.

**D15. A stream sheds load when the device is hot. A capture does not.** The service holds
a camera and a wake lock for hours on a phone that is usually bolted to a stand with its
screen off, and until card 44 it knew nothing about the state of that phone. `/api/status`
now reports the platform's thermal level and the battery.

The **stream** is the only continuous load this project puts on the device, so it is the
thing that gives way: the interval between frames is multiplied by two at `moderate`, four
at `severe`, eight at `critical`, and twenty at `emergency` and above. Nothing changes at
`light`, which the platform defines as throttling nobody can feel, and a bench camera that
halved its rate every time a phone warmed slightly would not be trusted. A **capture** is
never slowed. It is one-off work the caller asked for, and a measurement that silently
took longer because of heat would be worse than one that took the time.

The stream is never stopped, at any level, because it carries the reason the rate fell.
Every part has `X-DeskCam-Fps` and `X-DeskCam-Thermal`, and `X-DeskCam-Shedding` once the
rate is below what was asked for, so a client can tell heat from a network fault.

**Plugged in and charging are different facts.** A phone told to stop at 80 percent, which
is a sensible way to run one that lives on a stand, has the cable in and is not charging.
`plugged_in` comes from whether there is power at the socket and `charging` from the
platform's own battery status, so a field named for one never reports the other.

**The thermal level and the battery temperature are different quantities.** The level is
the platform's own judgement, on the scale it acts by, and needs no threshold of ours. The
battery temperature is the only real thermometer an ordinary app may read, and it is
neither the sensor nor the processor and lags both. A phone can be throttling severely with
a battery at a comfortable 36 degrees. Measured on this bench, `dumpsys thermalservice`
against the app at the same moment: status 3 (severe), battery 29.5 degrees, skin 34.1 and
35.0, display 29.6, and the TPU at 53.0. Every thermometer an app can reach said the phone
was comfortable; the platform was throttling because of a part none of them measures.

**D16. The camera stops reading the sensor when nobody is asking.** After 20 seconds with
no stream client and nothing requesting a frame, the repeating preview request is stopped.
The session and the device stay open: closing them costs a second or more to undo and gives
the reopen path something to race with, while stopping the repeating request is the cheap
end of the same idea and the end where the power goes.

**The waiting on the way back is the part that matters.** A repeating request that has just
restarted delivers frames at once, and with automatic exposure the first of them were
exposed while the loop was still converging. Every path that needs a frame, including a
still, a DNG and a burst, wakes the preview and waits: four frames always, and for the
exposure loop to report itself converged when the exposure is automatic, bounded at two
seconds. A capture is never given a frame from a pipeline that has not settled. **The first
capture after a quiet period must not be quietly worse than the same capture during a busy
one**, because that is a fault nothing downstream could detect.

Measured on a Pixel 6a, three runs each: nothing at all while idle, against 29 frames a
second awake; a wake of 305 to 348 ms with the exposure fixed and 377 to 803 ms with it
automatic; a still taken against a sleeping camera 764 to 822 ms in total. The exposure of
the first frame after a wake was identical to one taken two seconds later in every
automatic run, and the ISO agreed to within 5 of 200.

**A wake counts as a demand.** Without that the watchdog idled the camera 273 ms after
waking it, while the still that woke it was still being captured, and the next capture paid
the wake again. The idle test also requires that no capture is in flight, because a long
bracket is minutes of work that asks for nothing until it finishes.

**The watchdog has to know the difference.** It reopens a camera that has produced no frame
for 15 seconds, which is exactly what a deliberately idle camera looks like. Without the
distinction it would reopen the camera every fifteen seconds for ever, costing far more
than the frames it saved. It now reads the time of the last frame rather than comparing a
counter between its own ticks, and it skips the stall test entirely while the preview is
idle. `/api/status` carries a `preview` block, so an idle camera and a broken one are
distinguishable from outside instead of both being a frame counter that stopped.

**A page that nobody is looking at does not hold the camera awake.** An `<img>` on an MJPEG
stream keeps its connection for as long as its `src` is set, whether the tab is visible,
buried, or on a machine with the lid shut. Both panels stop their stream on
`visibilitychange` and start it again when shown. Idling the engine achieves nothing while
a forgotten tab holds it awake, which is how this was found.

**D17. The crop and the focus region are two rectangles, not one.** `meteringForRoi()`
derived the autofocus region, the metering region and the sharpness window from the crop,
so "what I want in the picture" and "what should be sharp" were the same statement. They
coincide most of the time, which is why the conflation went unnoticed until someone asked
for a wide frame with one connector sharp.

`focusbox=cx,cy,w,h` names the second rectangle, in the same coordinates as `cx` and `cy`
and through the same rotation, because a second coordinate system in one API is how a
measurement comes out wrong. It moves the autofocus region and the region a sharpness
reading and `/api/focushunt` measure. It moves neither the crop nor the metering: a
parameter named for focus that quietly changed the exposure would be the same conflation
in a new place, and metering can have its own rectangle and its own decision if it ever
needs one.

A box that does not overlap the crop is **refused rather than clamped**. Clamping would
silently focus somewhere other than where the caller said, and the caller has asked the
camera to focus on something the capture will not contain, which is a mistake and not a
preference.

Measured on this bench, three hunts at an unchanged `zoom=1`: the whole frame peaked at
20.92, a box on a detailed part at 47.33, and a box on a near-empty part at 0.70. The
framing did not move between them.

## 7. Non-goals

**No TLS.** The service is for a trusted LAN. A self-signed certificate would make `-k`
necessary on each request. It would make each agent more complex. It would give no real
protection against the true risk. Use the token for simple protection. Put the phone on
WireGuard or Tailscale if the stream needs encryption, or if you need access from outside
the LAN.

**No image processing on the device**, except crop, rotate, and resize. Refer to the split
rule in section 3.

**No automatic start after a reboot.** The platform prevents it. Refer to section 4.4.

**No photographic features.** No portrait mode. No scene modes. No tone maps for a display.
These features damage a measurement.

**No Super Res Zoom.** It needs hand movement to get sub-pixel data. Camera2 cannot command
the OIS position. Thus the method does not work on a fixed mount.

## 8. Roadmap

The list is in order of value against work. Items 1 to 3 give most of the benefit. The
kanban board holds these items as cards 1 to 11.

**1. RAW and DNG capture (backend). DONE.** All measurement work needs linear data. The
sensor is 10 bit. The black level is 64. The white level is 1023. `DngCreator` writes the
file. The engine adds a RAW_SENSOR output to the session. If a device refuses that stream
combination, the engine configures the session again without RAW. Then the camera still
works.

**2. Measurement mode (backend). DONE.** The parameter `measure=1` stops the pipeline from
changing the image. It sets noise reduction off, edge enhancement off, hot pixel correction
off, lens shading correction off, and chromatic aberration correction off. It sets the tone
map to a linear `CONTRAST_CURVE`. It sets OIS off and locks the white balance.

One test on the device, on 2026-09-09, is consistent with the design. The exposure was
doubled four times. In measurement mode the pixel value rose by 2.02x for each doubling,
which is linear. With the default pipeline it rose by 1.30x, near the 1.37x of an sRGB
curve. At 1/120 s the default curve read 69.6 where the linear curve read 6.9.

**These numbers are unconfirmed and should not be quoted.** They are one point estimate
from one run with no interval; the 2.02x is the mean of four ratios with the variation
dropped, and the lowest of those samples sits at the floor of the 8 bit range, which alone
covers the difference between 2.02 and 2.00. The code that produced them is not in this
repository. What the test does support is the shape: the default pipeline lifts the shadows
by roughly an order of magnitude, and that is the error a measurement must not contain.
Card 34 puts the method in `frontend/analysis/`; card 35 adds the same-against-same test
that says how large a difference has to be before it means anything.

`/api/status` gives a `pipeline` block. The block reports what the HAL applied, not what
the request asked for. Rule R4 applies to the pipeline as much as to the exposure.

**3. Burst capture (backend). DONE. Average (frontend) is card 4.** `/api/burst` sends the
frames to the camera as one submission, so the HAL runs them back to back. The result is a
tar archive. One test gave 12 full resolution frames in 642 ms. The 18.7 frames per second
that was reported counts 12 frames across 11 intervals; 11/0.642 is 17.1. Either way it is
one run with no interval. **Unconfirmed.**

Two changes were necessary. The still reader now holds 6 buffers, so the HAL can run ahead
of the server. And the reader takes each image with `acquireNextImage`. The old code used
`acquireLatestImage`, which discards frames and is correct for a preview and wrong for a
burst.

One result from 12 frames: an average of 6 frames had 2.25 times less noise than one frame,
against a prediction of 2.45. **Unconfirmed.** One run, and the split of the 12 frames into
two groups was one of several possible splits; which one was chosen was not recorded. The
mechanism holds regardless: JPEG compression makes the noise of neighbouring frames a
little alike, and fixed pattern noise is equal in each frame, so an average never removes
it. Card 10 removes that part with a dark frame.

**4. Focus sweep (backend) and focus stack (frontend).** At 98 mm the depth of field is
one or two millimetres. Move the lens in **dioptre steps**. The depth of field is almost
equal for each dioptre. The calibration is `APPROXIMATE`, so use only the order of the
steps.

**5. Exposure bracket (backend) and merge (frontend).** For a panel, step the exposure in
**whole multiples of the PWM period**. Do not use arbitrary stops. Powers of two from one
period (1/240, 1/120, 1/60, 1/30) stay one stop apart and stay in phase. An arbitrary stop
reads a different part of the duty cycle. The merge is then wrong.

**6. A sharpness value in `/api/status` (backend).** Use the variance of the Laplacian. It
is cheap. The frontend can then close the focus loop. It does not send candidate frames
over the network. This is one of only two calculations that belong on the device.

**7. Calibration frames (frontend).** Subtract a dark frame. Divide by a flat field. Use a
grey card for the white balance. The flat field is necessary for panel work. Lens
vignetting looks the same as panel non-uniformity. The flat field must be measured, because
this device does not deliver a lens shading map. Refer to section 4.3.

**8. Display rectification (frontend).** Find the panel corners. Correct the perspective
with a homography. Give an image of a constant size. This lets you compare two design
iterations pixel by pixel. Without it, a small movement of the phone changes the image.

**Rejected work.** ISO brackets are not useful. The max analog sensitivity is 444. Above
that value the gain is digital, so apply it to RAW data later. White point brackets are
not useful. RAW makes the white balance free and lossless later.

## 9. Known limits

**Macro is an optical limit. Software cannot correct it.** At the 98 mm minimum focus
distance the main camera gives 0.047x magnification. The field of view is then 120.7 mm
wide and the resolution 33.4 pixels for each millimetre, about 30 micrometres for each
pixel. Those three are arithmetic from the sensor size, the focal length and the stated
minimum focus distance; they are not a measurement, and `focusDistanceCalibration` on this
device is `APPROXIMATE`, so treat them as the right order and not as figures. The scale of
an actual picture depends on where the stand is and has to be measured from a reference in
the frame (card 23).
This is sufficient to read silkscreen and to find a part. It is not sufficient to see a
solder fillet. A clip-on macro lens is the correction. The physical ultrawide camera
(`id 3`) does not open directly, so it gives no other method.

**The phone gets a new DHCP address after a reboot.** The command `deskcam wifi` finds the
new address. A fixed address on the router is better.
