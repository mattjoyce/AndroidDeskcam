# DeskCam specification

Status: retrospective. This documents a system that exists and runs, records why it is
shaped the way it is, and defines the contract that lets the two halves evolve apart.

Version 0.1. Target device is a Pixel 6a (`bluejay`) on GrapheneOS, Android 17, API 37.

## 1. Purpose

Turn a spare Android phone into an instrument for desk work. Three jobs drive every
decision below.

1. Iterating on OLED designs, where the camera must report what the panel actually emits
   rather than what looks nice.
2. Debugging circuits, where the camera must resolve small features and focus close.
3. General desk capture, where the camera must be fast and require no ceremony.

The camera is a measuring device first and a camera second. Where those conflict,
measurement wins.

## 2. Primary consumer

**The primary consumer is Claude Code, not a human.** A person using the browser panel is
a secondary case that must keep working, not the case the design optimises for.

That single assumption produces eight binding rules. Every one is satisfied by the current
implementation.

| # | Rule | Why it matters to an agent |
|---|---|---|
| R1 | Every operation is one shell command with a meaningful exit code | An agent can branch on failure without parsing prose |
| R2 | Capture commands print a **path**, never image bytes | The agent reads the file with its own tooling, and the transcript stays small |
| R3 | Any endpoint accepts the full control surface inline as `k=v` | An agent never has to remember or re-query current state before acting |
| R4 | Responses report **measured** values, not just requested ones | The agent verifies its own action in the same round trip |
| R5 | Unknown parameters are errors, not silent no-ops | A typo fails loudly instead of producing a wrong image |
| R6 | The API describes itself at `/api/help` | An agent can discover the surface without reading this repo |
| R7 | Units are accepted as written in datasheets (`1/120`, `8ms`, `250us`, `0.12`) | No unit conversion step for the agent to get wrong |
| R8 | All operations are idempotent | Retrying is always safe |

R3 deserves emphasis because it is the rule that most shapes the API. `deskcam snap
zoom=4 cx=0.3 exposure=1/120` is a complete instruction. There is no session, no mode to
enter, and no ordering requirement. A stateless one-liner is the unit of work.

R4 is the second most important. `/api/status` returns a `measured` block sourced from the
sensor's own `TotalCaptureResult`, so a request for 1/60 comes back confirmed as 16.63ms
at ISO 199. Requested and achieved are different things, and the agent is told both.

## 3. Architecture

The seam between the two halves is the HTTP contract in section 4.5. Nothing else crosses
it. Either half can be rewritten without touching the other.

```
  WORKSTATION (frontend)                 |   PHONE (backend)
                                         |
  Claude Code ──┐                        |
                ├─→ frontend/deskcam ──┐ |
  human shell ──┘                      │ |
                                       ├─┼──→ HTTP :8080 ──→ HttpServer
  browser ─────────────────────────────┘ |                      │
     ↑ (page is served by the backend)    |                     ↓
                                         |                 CameraEngine
  desktop processing (planned)           |                      │
   stack / merge / rectify / diff        |                      ↓
                                         |                  Camera2 HAL
```

Split rule. **The backend produces honest pixels and reports its own state. The frontend
decides what to do with them.** Anything that needs more than one frame, floating point
maths, or a library belongs on the workstation, which has the CPU and the ecosystem. The
phone is a sensor with an HTTP socket.

One deliberate exception. The browser control panel is frontend code that ships inside the
backend (`WebUi.java`) and is served from `/`. It buys a zero-install human interface on
any device on the network. It is a client of the same public contract and gets no
privileged access, so it does not weaken the seam.

## 4. Backend

Runs on the phone. Java, no external dependencies, no AndroidX, no Gradle.

### 4.1 Responsibilities

Owns the camera device and capture session. Applies controls. Produces JPEG stills,
preview frames and an MJPEG stream. Reports settings, sensor limits and measured results.
Stays alive with the screen off.

Explicitly not its job: stacking, merging, alignment, colour science, CV, or storage of
past captures.

### 4.2 Components

| File | Responsibility |
|---|---|
| `CameraEngine.java` | Camera2 device, session, image production, status |
| `CamSettings.java` | Control state, ROI maths, parameter parsing, JSON |
| `HttpServer.java` | HTTP/1.1 on a `ServerSocket`, routing, MJPEG chunking |
| `WebUi.java` | Browser panel and the `/api/help` document |
| `CamService.java` | Foreground service, lifecycle, notification, address discovery |
| `MainActivity.java` | Permissions, start and stop, intent-driven headless start |
| `BootReceiver.java` | Best-effort restart after reboot, see 4.4 |

### 4.3 Hardware constraints that shaped the design

Measured from `dumpsys media.camera` on the target device.

**`android.scaler.croppingType = CENTER_ONLY`.** The HAL discards the offset of any crop
rectangle it is given and can only zoom about the centre. Hardware pan does not exist on
this sensor.

Consequence, and the single most important design decision in the backend. The engine
always requests the **full 4032x3024 active array** and crops the region of interest in
software. This buys arbitrary pan, and it makes every zoomed pixel a real sensor pixel
rather than a HAL upscale. At 4x zoom the result is a native 1008x756 crop.

Other capabilities the design leans on.

| Property | Value | Used for |
|---|---|---|
| Hardware level | `LEVEL_FULL` | per-frame manual control |
| Capabilities | `MANUAL_SENSOR`, `MANUAL_POST_PROCESSING`, `RAW`, `BURST_CAPTURE` | measurement mode, roadmap items |
| Exposure range | 53.659us .. 10.177s | anti-flicker, bracketing |
| Sensitivity range | ISO 56 .. 7111 | manual exposure |
| Max analog sensitivity | **444** | above this is digital gain, so do it in post instead |
| Raw encoding | 10-bit, black 64, white 1023, RGGB | linear capture |
| DNG calibration | illuminants, colour and forward matrices present | correct colour on the workstation |
| Min focus distance | 10.204 dioptres (98mm) | close work, and the macro ceiling |
| Focus calibration | `APPROXIMATE` | sweeps must be monotonic, not absolute |
| Metering regions | AF 1, AE 1, AWB 0 | ROI-following focus and metering |
| Lens shading map | 33x25 | flat-field cross-check |
| Torch | 45 discrete levels | controllable bench illumination |
| High speed video | 1080p120 and 1080p240 | display timing analysis |
| Rolling shutter skew | reported per frame | PWM frequency from a single still |

### 4.4 Platform constraints

Two Android 17 behaviours cost real debugging time and are recorded so they are never
rediscovered.

**Local network access is separate from internet access.** An app holding `INTERNET` can
reach the public internet while every local-network packet is dropped in both directions,
with no error anywhere. The symptom is a healthy-looking server that never answers a SYN
from the LAN while answering fine over loopback. The fix is
`android.permission.ACCESS_LOCAL_NETWORK`. It is not enumerated by `pm list permissions`;
it was found in the API 37 `android.jar`.

**A camera foreground service cannot be auto-started after boot.** Verified by reboot.
Both available routes are refused.

```
ForegroundServiceStartNotAllowedException: FGS type camera not allowed to start from BOOT_COMPLETED
Background activity launch blocked! ... (BAL_BLOCK)
```

`BootReceiver` still attempts it and `CamService` catches the refusal, because the
unhandled exception previously crash-looped the service. Recovery after reboot is
`deskcam start` over adb, or one tap. Granting `SYSTEM_ALERT_WINDOW` would exempt the app
from the activity-launch rule; that is a broad permission and is not requested.

### 4.5 The contract

The seam. HTTP/1.1, all verbs GET, no session, no cookies, `Access-Control-Allow-Origin: *`.
POST is accepted with a query string or flat JSON body for convenience.

| Endpoint | Returns | Notes |
|---|---|---|
| `/api/status` | JSON | settings, limits, sensor geometry, `measured` block |
| `/api/still` | `image/jpeg` | full resolution, ROI cropped |
| `/api/frame` | `image/jpeg` | preview resolution, much faster |
| `/api/stream` | `multipart/x-mixed-replace` | MJPEG, `fps` and `n` |
| `/api/set` | JSON | apply parameters, return result |
| `/api/reset` | JSON | restore defaults |
| `/api/af` | JSON | one autofocus sweep |
| `/api/cameras` | JSON | enumerate cameras and capabilities |
| `/api/help` | JSON | self-description, see R6 |
| `/api/nettest` | JSON | outbound probe, diagnoses 4.4 |
| `/` | `text/html` | browser panel |

Control parameters are valid on **every** endpoint per R3, applied before the image is
produced.

`camera`, `zoom`, `zoomby`, `cx`, `cy`, `dx`, `dy`, `af`, `focus`, `focusm`, `ae`,
`exposure`, `iso`, `ev`, `aelock`, `awb`, `awblock`, `torch`, `jpegq`, `rotate`, `w`, `h`,
`previewsize`, `stillsize`, `reset`, `settle`, `timeout`.

Coordinate model. `zoom` is a scale factor where 1.0 is the whole sensor. `cx` and `cy`
are the normalised ROI centre in 0..1. `dx` and `dy` are relative and expressed in
fractions of the **current** ROI width, so one nudge moves the same visual distance at any
zoom. The ROI is clamped to stay inside the frame.

Errors. Unknown or unparseable parameters return HTTP 400 with `{"ok": false, "error":
"..."}` and change nothing, per R5.

Auth. Optional shared token via `?token=` or `Authorization: Bearer`. Disabled by default.
There is no TLS; see section 7.

### 4.6 Image production

Two paths with different cost and purpose.

**Still.** A `TEMPLATE_STILL_CAPTURE` request into a full-resolution JPEG `ImageReader`.
When zoom is 1 with no rotate or resize, the camera's JPEG is returned **untouched**,
which is both the fastest and highest quality result. Otherwise `BitmapRegionDecoder`
decodes only the requested tile, so a deep zoom costs less than a shallow one.

**Preview.** A repeating request into a YUV_420_888 reader, converted to NV21 and cropped
during JPEG encode via `YuvImage.compressToJpeg(rect, ...)`. Crop origins are forced even
because NV21 chroma is subsampled 2x2 and an odd origin shifts the colour planes.

Conversion is **demand-gated**. Frames are drained and discarded unless a stream client is
attached or `/api/frame` was called within two seconds, so an idle service costs nothing.

Focus and exposure metering rectangles are mapped from the ROI onto sensor coordinates, so
zooming onto a component focuses and meters for that component.

### 4.7 Lifecycle

Foreground service of type `camera` holding a partial wake lock, which is what permits
camera access with the screen off. The service is not exported. Headless start goes
through the exported activity, which is also what satisfies Android's requirement that a
camera foreground service start from the foreground.

```sh
adb shell am start -n dev.deskcam/.MainActivity -a dev.deskcam.START --ez finish true
```

### 4.8 Build

`backend/build.sh` runs `aapt2` then `javac` then `d8` then `zipalign` then `apksigner`.
No Gradle and no AGP, which is possible only because the app has zero external
dependencies. A clean build takes about two seconds and needs `platforms/android-37.0` and
`build-tools/37.0.0`.

## 5. Frontend

Runs on the workstation. Currently one Bash script; the processing layer is planned.

### 5.1 Responsibilities

Present the contract as commands. Resolve and remember the target. Manage the device
lifecycle over adb. In future, everything multi-frame or numerical.

### 5.2 CLI

`frontend/deskcam`. Target resolution order is `--url`, then `$DESKCAM_URL`, then
`~/.config/deskcam/url`, then localhost.

Verbs map to the contract. `snap`, `frame`, `stream`, `status`, `show`, `set`, `reset`,
`zoom`, `pan`, `center`, `af`, `focus`, `exposure`, `iso`, `auto`, `torch`, `cameras`,
`api`, `open`.

Target and device management, which have no backend equivalent because they are
workstation concerns. `use`, `usb`, `wifi`, `which`, `start`, `stop`.

Two behaviours exist purely to serve R1 and R2. Capture verbs print the path they wrote
and nothing else, so `img=$(deskcam snap zoom=4)` is the idiom. And `show` collapses the
status JSON to one line, because an agent checking state should not pay for a full JSON
dump.

`usb` deserves note. It runs `adb forward` and repoints the target at loopback, which
sidesteps the local network permission entirely and is the reliable fallback when Wi-Fi
misbehaves.

### 5.3 Browser panel

Served from `/`. Live MJPEG with click-to-centre and scroll-to-zoom, mapped through the
current ROI so a click means the same thing at any zoom. Controls for focus, exposure,
white balance and torch. It exists for aiming the camera, which is genuinely easier with a
pointer than with coordinates.

### 5.4 Desktop processing (planned)

Not built. This is where the roadmap in section 8 lands, and the reason the split is drawn
where it is. Expected to be Python with OpenCV, rawpy and NumPy.

## 6. Design decisions

**D1. Software ROI instead of hardware crop.** Forced by `CENTER_ONLY`, and better anyway
because it preserves native pixels. See 4.3.

**D2. Zero dependencies in the backend.** Makes the Gradle-free build possible, which in
turn makes the whole thing reproducible in seconds from a shell. A hand-written HTTP
server is roughly 400 lines and gives direct control over MJPEG chunking.

**D3. GET for everything.** Violates REST purity. Correct here, because the primary
consumer composes URLs in a shell, and a mutation expressed as a GET is trivially
scriptable and trivially retryable.

**D4. Measured values in every status response.** See R4. Costs nothing, since the values
are already in the `TotalCaptureResult` the engine keeps.

**D5. Unknown parameters are errors.** The alternative is an agent silently getting an
unmodified image and reasoning about it as though the setting applied.

**D6. Relative pan in ROI-widths, not absolute fractions.** Makes `pan left` mean the same
thing at 1x and 8x, which is what both a human and an agent expect.

**D7. Demand-gated preview conversion.** An always-on bench camera should be free when
nobody is looking.

## 7. Non-goals

No TLS. The service targets a trusted LAN. A self-signed certificate would force `-k` on
every request and complicate every agent, for no real gain against the actual threat model.
Use the token for casual protection, and put the phone on WireGuard or Tailscale if the
stream must be encrypted or reachable off-LAN.

No on-device image processing beyond crop, rotate and resize. See the split rule in 3.

No auto-start on boot. Blocked by the platform, see 4.4.

No cosmetic photography features. No portrait mode, scene modes, or display-oriented tone
mapping. They actively harm measurement.

No Super Res Zoom. It depends on hand tremor for sub-pixel diversity and OIS position is
not commandable through Camera2, so it does not transfer to a rigid mount.

## 8. Roadmap

Ordered by value per unit of work. Items 1 to 3 carry most of the benefit.

**1. RAW/DNG capture (backend).** Everything in measurement territory depends on linear
data. The sensor is 10-bit with black 64 and white 1023, and the full calibration metadata
needed for a valid DNG is present. `DngCreator` is in the framework.

**2. Measurement mode (backend).** One preset that stops the pipeline from lying.
Noise reduction off, edge enhancement off, tone map set to a linear `CONTRAST_CURVE`,
OIS off, white balance locked. All available per 4.3. The default pipeline is tuned to
make photographs look good, which is the opposite of comparing two renders.

**3. Burst capture (backend) plus averaging (frontend).** The one idea worth taking from
HDR+ is a burst of deliberately underexposed frames, merged. Highlights never clip and
noise falls as the square root of the frame count. On a rigid mount, alignment is free.

**4. Focus sweep (backend) plus stacking (frontend).** Depth of field at 98mm is a
millimetre or two. Sweep in **dioptre space**, because depth of field is roughly uniform
per dioptre, and rely only on monotonicity because calibration is `APPROXIMATE`.

**5. Exposure bracket (backend) plus merge (frontend).** For panels, bracket in **whole
multiples of the PWM period**, not arbitrary stops. Powers of two from a base period
(1/240, 1/120, 1/60, 1/30) stay both one stop apart and phase-aligned. Arbitrary stops
sample different fractions of the duty cycle and the merge is then wrong.

**6. Sharpness metric in `/api/status` (backend).** Variance of Laplacian. Cheap, and it
lets the frontend close the loop on focus without shipping candidate frames over the
network. One of only two things that earn a place on the device.

**7. Calibration frames (frontend).** Dark frame subtract, flat field divide, grey
reference normalise. The flat field is not optional for panel work, because lens vignetting
otherwise reads as display non-uniformity.

**8. Display rectification (frontend).** Detect the panel quad, rectify by homography,
return a scale-normalised image. This is what makes two design iterations diff
pixel-for-pixel instead of drifting because the phone moved.

Explicitly rejected. ISO bracketing, because max analog sensitivity is 444 and everything
above it is digital gain better applied to raw in post. White point bracketing, because
raw makes white balance free and lossless in post.

## 9. Known limits

Macro is optical, not software. At the 98mm minimum focus the main camera gives 0.047x
magnification, a 120.7mm field of view, and 33.4 px/mm, which is 30 micrometres per pixel.
That reads silkscreen and locates components. It will not show a solder fillet. A clip-on
macro lens is the fix; the physical ultrawide (`id 3`) is not directly openable and offers
no escape.

The phone takes a new DHCP address after reboot. `deskcam wifi` rediscovers it. A static
reservation on the router would be better.
