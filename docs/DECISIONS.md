# DeskCam — decisions, and the reasoning behind them

**This is not a specification.** It was one, and a specification for a thing that already
exists is a second copy of the thing: the parts of the old document that described the
surface went stale, and a review before the first public release found them stale in
exactly the places a hand-written copy of generated output always goes stale. Those parts
are gone. `/api/help` describes the surface, `deskcam help` describes the commands, and the
code is the reference for both.

What is left is the part neither the code nor `/api/help` can tell you: **why it is like
this.** A decision, a measurement that forced it, a thing deliberately not built. Code
records what was chosen. It cannot record what was rejected, or what was measured on a
bench at a particular hour to settle an argument, and a reader who does not have those
re-litigates every one of them.

The rules are `R1` to `R8` and the decisions are `D1` to `D19`. Both are referred to by
number throughout the repository, in the code comments, in the kanban cards and in the
commit messages, so the numbers are stable and are never reused.

The kanban cards those comments cite are a private board and are not in this repository.
Where a comment cites a card, the comment beside it carries the reasoning.

*(Previously `docs/SPEC.md`. The full prior document, including the reference sections, is
in git history.)*

## The primary consumer, and the eight rules it gives

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
| R8 | Setting a value is idempotent; the relative moves `dx`, `dy`, `zoomby` are not | A second attempt is always safe, except a relative move, which moves again. |

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

## What the backend is responsible for

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

## Hardware facts that control the design

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
| Lens shading map | Delivered while the mode is on, and NOT advertised in the result keys | Refer to the note below |
| Torch | 45 steps | Controlled light for the bench |
| High speed video | 1080p120 and 1080p240 | Display timing measurement |
| Rolling shutter skew | Reported for each frame | The PWM frequency from one still image |

**The lens shading map does arrive on this device.** Measured 2026-09-12 on the bench
Pixel 6a: three consecutive `GET /api/shadingmap` each answered `ok: true` with a 25 by 33
RGGB map, 3300 gain factors running from 1.0 in the middle to 3.452 at the corner. The same
call with `shadingmap=0` answered with no map at all, so the map follows
`STATISTICS_LENS_SHADING_MAP_MODE` exactly as the platform documents it.

What is false is the device's own account of itself. The camera lists
`availableLensShadingMapModes` as `[0, 1]` and gives a `shadingMapSize` of 33 x 25, and
`android.statistics.lensShadingMap` is nonetheless absent from
`getAvailableCaptureResultKeys()`. The engine read that list at start-up and published it as
`sensor.shading_map_supported: false` while the map was arriving on every frame.

**The paragraph that stood here is withdrawn.** It said the map "is not usable on this
device", that "the map never arrives", and "Measure a flat field. Do not depend on the map."
It was believed from the key list alone. Nothing had ever asked for a frame with the mode on
and then looked, which is the only measurement that could have settled it.

It is now two facts and a judgement. `sensor.shading_map_key_advertised` is what the camera
claims, `sensor.shading_map_seen` is whether a map has actually arrived since the camera was
opened, and `sensor.shading_map_supported` is the judgement made from the pair. `Shading`
holds the rule and the workstation tests it. A missing map now says which of three things
happened, because the one message it used to give named the wrong cause for two of them and
sent the reader off to measure a flat field by hand when the mode had simply been off.

This is a general lesson for this device, and it runs both ways. A mode list says that a
control is settable. It does not say that the result arrives. **And a result-key list does
not say that the result will not arrive.** Neither list is evidence. The only evidence is a
frame.

## Platform facts

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

## How the backend makes images

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

## Life cycle

The service is a foreground service of type `camera`. It holds a partial wake lock. This
permits camera access when the screen is off.

The service is not exported. A headless start goes through the exported activity. Android
permits a camera foreground service to start only from the foreground. The activity
satisfies this rule.

```sh
adb shell am start -n dev.deskcam/.MainActivity -a dev.deskcam.START --ez finish true
```

## What the workstation does

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

The merging and stacking work still does not exist.
The plan for it remains Python with OpenCV, rawpy and NumPy.

## Decisions

**D1. The software makes the ROI. The hardware does not.** `CENTER_ONLY` makes this
necessary. It is also better, because it keeps true sensor pixels. Refer to the hardware facts above.

**D2. The backend has no dependencies.** This makes the build without Gradle possible. The
build is then repeatable in seconds from a shell. A written HTTP server was about 400 lines when this was decided; `HttpServer.java` is 1,340 today, most of it the endpoints.
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
records it beside the captures. `linearity` enforces it today; the other tools do not
read it yet.

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

The answer is `multipart/mixed`, not server-sent events, and that follows from what the backend is responsible for, above.
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
`visibilitychange`, and again after five minutes with no pointer, key, wheel or touch,
starting it on the next thing anyone does. The first value was 30 seconds, and in use it
paused the view on someone watching it with both hands on the work. The fault it guards
against is a page left overnight, which minutes cure as well as seconds. **Being visible is
not the same as being watched**: a page open on a second monitor with nobody in the room is
the case most likely to be left running, and the first version of this covered every case
except that one.
Idling the engine achieves nothing while a forgotten page holds it awake.

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

Measured on 2026-09-11, five hunts per region, interleaved, at an unchanged `zoom=1` with
`exposure=1/33 iso=250` fixed so the hunt climbs the lens and not the exposure loop. The
subject was a white disc carrying graph paper, a pen-hatched square, and a black watch:

| Region measured | Peak sharpness, mean of 5 | Spread | Lens position chosen |
|---|---|---|---|
| The whole frame | 70.7 (95% 70.6 to 70.8) | 70.58 to 70.86 | 7.015 d four times, 7.653 d once |
| A box on the hatched square | 435.7 (95% 433.8 to 437.7) | 433.7 to 438.0 | 7.653 d every time |
| A box on plain white paper | 85.4 (95% 80.6 to 90.2) | 81.5 to 90.6 | 7.653 d every time |
| A box on the black watch face | refused all five times as flat: the curve moved 15% to 22% across the range | 1.6 to 2.1 at every position | none |

The box on the detail scores six times the whole frame, and the two intervals are far
apart, so the difference is real and not hunt-to-hunt noise. Plain white paper is not
empty at 30 micrometres per pixel: the hunt found the fibre and chose the same lens
position as the hatched square. Only the glossy black face was flat enough to refuse, and
that refusal is the contrast test of card 56 doing its job. The first version of this
paragraph gave one hunt per region, 20.92, 47.33 and 0.70, against a known hunt-to-hunt
spread of 34.9 to 64.5 on an unchanged subject, which made the first two numbers
indistinguishable from noise. They are withdrawn.

**D18. A surface sits with what it has to touch.** The phone owns the camera. The
workstation owns the captures and their sidecars, the access key, and the APK. A tool for a
person goes to whichever side owns what it touches, so the test for any feature is whether
it needs the camera or needs the disk and the key.

The page the phone serves is therefore the bench tool, for a person looking at the thing on
the bench: aiming, framing, focusing and lighting it. It needs nothing but the camera. Any
browser on the network opens it with nothing installed, and its stream comes straight from
the phone instead of being relayed through the workstation. It is the only live view.

The Go binary is everything else. The CLI drives the camera for an agent and holds the key.
`deskcam serve` hosts the APK and the pairing code, because a phone installing without adb
needs something to download from. It has no live view, no relay and no framing controls.
Whether it keeps showing a person the captures on disk is open. If it does, that is because
the captures are files on the workstation and the phone stores nothing (D14).

Before this, both pages carried a live view with gestures of their own, drag to frame on one
and click to centre on the other, and they drifted apart. Card 63 then added one overlay to
both, with the same defect in both, and it hid both live views from the first paint. Nobody
saw it until the phone's page was opened in a browser on 2026-09-12. Two copies of one tool
is how that happens.

This does not reorder the consumers. The agent is still the primary consumer and R1 to R8
still hold; this decides where the secondary consumer's tools live. A person at the bench
and an agent can both change the camera, and D14 keeps an agent's sequence whole while they
do, so the bench tool has to say so when a tape holds the camera rather than drop the
person's click.

One consequence comes first. The access check covers every path, the page included, and the
page's own requests carry no key, so with a key set the bench tool fails. The console's relay
exists to get around that. The fix belongs on the phone, and until it lands the console keeps
its live view.

D19 revises one part of this. The split between the phone and the workstation stands. The
line that the console has no live view does not, because D18 judged the console as a second
bench tool and it is not one.

**D19. The console is the other view of the same operations.** The system has two ends. The
phone is the measurement end: the sensor, the lens, and the bench tool a person uses to aim
them. The workstation is the operations end: the requests an agent made, the captures that
came back, the refusals, and the files those became.

The agent sees an operation as a command and its result. The console shows a person the same
operations from the outside: what was asked, from which project and directory, what came
back, what was refused, and how many attempts a task took. It is a diagnostic tool about
DeskCam, and it is the agent's seat: a person sitting there sees what the agent saw and can
do what the agent did.

Three rules follow.

**It observes, and it is never in the path.** An agent works the same with the console
stopped. No request to the phone goes through the console in order to be recorded, because
a recorder in the path turns a fault in the diagnostic tool into a fault in the instrument.
The console reads what the CLI and the phone already wrote down.

**It acts as the agent acts, through the agent's code.** There are two ways for a person to
use the camera and they are different jobs. The bench tool aims: continuous gestures on a
live view, by someone looking at the thing on the bench (D18). The console operates: it
issues the CLI's own operations, a still, a hunt, a bracket, a tape, a recall, and the
result lands on disk with a sidecar exactly as an agent's does. To find out why an agent
failed, a person has to be able to do what the agent did and get what the agent got, and
that holds only if both go through one code path. The console is the same binary as the CLI,
so its handlers call the functions the commands call and it has no camera logic of its own.
What a person does there is recorded beside what the agents did, and marked as a person's.

So the framing gestures go, because they are aiming and the bench tool has them. The live
view stays, for the reason in D10: a stream is a view, and the seat needs to show what the
agent sees beside what the agent captured.

**Tooling that does not belong on the measurement end lives here.** Every feature on the
phone costs heat, memory and an install, and D15 records this phone throttling to `severe`
under the load it already carries. The phone stores nothing (D14) and processes no image beyond crop, rotate and resize. So
the test for a new tool is whether it needs the camera or needs the record. A tool that
needs the record belongs to the console: the history of captures, grouping by project and
session, comparison of one capture with another, retention, repeating a capture from its
sidecar, the install and pairing codes, and the access key. None of these may slow a frame.

A session is derived and not managed. It is a run of captures from one project with no long
gap between them. Nobody creates, names or closes one, and the console keeps no state that
the files do not hold. The sidecar stays the only index, as in `roll.go`.

The code met none of this on the morning of 2026-09-19, and the four faults are worth
keeping because each is the general one in a small form.

The capture roll read one directory, the working directory of `deskcam serve`, while 99 of
the 100 captures on the workstation sat in 11 temporary session directories that agents had
chosen for themselves. That is a count by `find` of `deskcam-20*.jpg`, thumbnails excluded,
under the home directory and `/tmp`. The roll cannot show a capture it is not pointed at,
so it showed none of those. The CLI now writes every operation into a journal that does
not move, with a copy of the thumbnail and the sidecar, and the roll is that journal. A
refusal is journalled as fully as a capture.

A sidecar recorded the camera completely and nothing about who asked. It now carries an
`asker` block: the directory, the project, the command as typed, the session and the reason
given to `--why`. A scratch directory is in no repository, so the project is found from the
directory the command ran in or stated in `DESKCAM_PROJECT`, and it is never guessed.

The console carried framing buttons and gestures, a second copy of the bench tool. They are
gone, and so is the route that forwarded any parameter to the phone. The console offers
named operations and refuses the rest.

Its one operation, "Shoot this again", took no picture. It restored the settings and
stopped, and it built them in the page's own JavaScript from six of the keys that
`recallQuery` in `recall.go` knows, so the two had already drifted. Both now run
`recallFrom`, and a recall checked against the phone sent fourteen. `operate()` in
`main.go` is the one door: the CLI and the console both go through it, to one dispatch and
one journal.

One thing is still open. The phone keeps a log of the last forty requests and shows it on
its own screen only. Serving it would let the console show every client, including one that
does not use the CLI. That is a change to the phone and has not been made.

## Non-goals

**No TLS.** The service is for a trusted LAN. A self-signed certificate would make `-k`
necessary on each request. It would make each agent more complex. It would give no real
protection against the true risk. Use the token for simple protection. Put the phone on
WireGuard or Tailscale if the stream needs encryption, or if you need access from outside
the LAN.

**No image processing on the device**, except crop, rotate, and resize. Refer to the split
split rule.

**No automatic start after a reboot.** The platform prevents it. `BootReceiver` still makes the
attempt when the autostart box is ticked, and the platform refuses it. Refer to the platform facts above.

**No photographic features.** No portrait mode. No scene modes. No tone maps for a display.
These features damage a measurement.

**No Super Res Zoom.** It needs hand movement to get sub-pixel data. Camera2 cannot command
the OIS position. Thus the method does not work on a fixed mount.

## Known limits

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

