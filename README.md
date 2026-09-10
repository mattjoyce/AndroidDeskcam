# DeskCam

DeskCam makes a spare Android phone into a bench camera. You control the camera from the
command line or from a coding agent. It is built for a Pixel 6a on GrapheneOS. Its jobs
are OLED panel work, circuit debug work, and general desk capture.

Each control is an HTTP GET. Thus you can use the camera from `curl`, from a shell script,
or from an agent. You do not need a client library.

```sh
deskcam snap zoom=6 cx=0.32 cy=0.68 torch=25 focusm=0.12
```

The documents use ASD-STE100 Simplified Technical English. Refer to
[docs/SPEC.md](docs/SPEC.md) for the full specification.

## Layout

| Directory | Runs on | Holds |
|---|---|---|
| `backend/` | The phone | The Android app and its build script |
| `frontend/` | The workstation | The `deskcam` CLI |
| `docs/` | | The specification |
| `skill/` | Claude Code | The agent skill |

The backend makes true pixels and reports its state. The frontend decides what to do with
the pixels. The HTTP contract is the only connection between them.

## Why the design is like this

The Pixel 6a sensor reports `android.scaler.croppingType = CENTER_ONLY`. The camera HAL
removes the offset from a crop rectangle. It can only zoom around the centre. Thus the
hardware cannot pan.

DeskCam always asks the sensor for the full 4032 x 3024 array. It then crops the region of
interest in software. This gives a pan to any position. Each pixel in a zoomed image stays
a true sensor pixel. The HAL does not enlarge it. At 4x zoom you see a true 1008 x 756
crop.

The focus rectangle and the metering rectangle follow the region of interest. Thus a zoom
onto a part makes the camera focus and meter on that part.

The camera is `LEVEL_FULL` with `MANUAL_SENSOR` and `RAW`. This makes panel work possible.
The exposure range is 53.7 us to 10.2 s. The ISO range is 56 to 7111. The minimum focus
distance is 9.8 cm. The torch has 45 steps.

## One important gotcha

**Android 17 divides local network access from `INTERNET`.** An app can hold `INTERNET`
and reach the internet. At the same time the system drops each local network packet in
both directions.

The symptoms are difficult. The server looks correct in `logcat`. The command `ss` shows
that it listens. A TCP SYN from your workstation gets no answer. A connection from the
phone to its own address works.

The correction is `android.permission.ACCESS_LOCAL_NETWORK`. This app declares it and asks
for it. If the service runs but you cannot reach it, examine this permission first.

## Build

There is no Gradle and no Android Studio. The app has no external dependencies. Thus it
builds directly from the SDK build tools.

```sh
./backend/build.sh                 # writes backend/build/deskcam.apk
```

You need a JDK. You also need `platforms/android-37.0` and `build-tools/37.0.0` in
`$ANDROID_HOME`. The default is `~/Android/Sdk`. The first build makes a signing key.

The build compiles with `-Xlint:all -Werror` and runs `backend/test/dev/deskcam/Tests.java`
before it packages anything. Those tests need no phone: the ROI maths at each rotation, the
exposure parser, the clamp, and the tar writer are pure functions, and every framing and
parsing fault this project has had lived in one of them. There is no test framework, for
the same reason the app has no libraries.

The workstation half has its own gates:

```sh
./frontend/check.sh    # ruff, ruff format, mypy, bandit, pytest, then gofmt, vet, go test
```

They need no phone either. The console tests run a real console on a loopback port with
real files under a temporary directory, and the contract tests read `Params.java` and fail
if this README or the specification names a parameter that does not exist, or misses one
that does.

### The Go binary

`frontend/go/` builds one static binary with the CLI and the console in it:

```sh
cd frontend/go && go build -o deskcam .
```

Nothing runs behind it. Taking a picture needs no Python, no Node and no runtime at all;
measuring what is in one still needs `pip install -e '.[analysis]'`, because that is array
maths and NumPy's job. The binary invokes those tools by path and passes their exit codes
through: 0 measured, 2 refused, 1 could not run.

The parameter table in `params_gen.go` is generated from `Params.java`, the same file the
Python contract test reads, so the client is not a fifth hand-typed copy of the contract.
`go generate ./...` refreshes it, a test fails if it is stale, and another test compares it
against a live phone's `/api/help` when `DESKCAM_URL` is set.

The phone stays Java, and that is a decision rather than an accident. `DngCreator`,
`BitmapRegionDecoder`, `ExifInterface` and `YuvImage` have no NDK equivalent, camera access
is granted per app UID so a binary run from `adb shell` cannot open the camera at all, and
a foreground service of type `camera` has to be a Java class. See card 53.

## Installation

```sh
cd frontend/go && go build -o deskcam . && cd ../..
adb install -r -g backend/build/deskcam.apk   # -g gives the permissions immediately
./frontend/go/deskcam start                   # or open the app and touch Start
./frontend/go/deskcam wifi                    # find the Wi-Fi address of the phone
./frontend/go/deskcam show
```

The `-g` option is important. Without it you must give the camera permission and the local
network permission by hand.

Put the binary on your `PATH`. For an agent, link the skill as well:

```sh
ln -s "$PWD/frontend/go/deskcam" ~/.local/bin/deskcam
ln -s "$PWD/skill" ~/.claude/skills/deskcam
```

Nothing else is needed. One file, no runtime. Measuring what is in a picture is the one
thing that asks for more: `pip install -e '.[analysis]'`.
 The CLI stores the target in
`~/.config/deskcam/url`. The variable `DESKCAM_URL` replaces the stored value.

## The CLI

```
deskcam snap [-o FILE] [k=v ...]     A full resolution still, cropped to the ROI
deskcam frame [-o FILE] [k=v ...]    A quick preview frame
deskcam stream [-o FILE] [n=N]       An MJPEG stream

deskcam show                         A one line summary
deskcam status                       The full JSON state
deskcam set k=v [k=v ...]            Apply any control parameters
deskcam reset                        Set all values to the default

deskcam zoom N                       The value 1.0 is the full sensor
deskcam pan up|down|left|right [amt] Move the view. The step uses the current view.
deskcam center
deskcam af                           Do one autofocus sweep
deskcam focus METRES|auto
deskcam exposure 1/120|8ms|250us|0.5s
deskcam iso N
deskcam auto                         Return to automatic exposure and focus
deskcam torch 0-45|off|max

deskcam cameras                      The cameras and their capabilities
deskcam api                          The API description as JSON
deskcam open                         Open the browser panel

deskcam use URL | usb | wifi | which | start | stop
```

`snap` and `frame` print the path of the file that they write. Thus an agent can use them
easily:

```sh
img=$(deskcam snap zoom=4 cx=0.3 cy=0.7)
```

Each command also accepts `k=v` words. The CLI applies them before it takes the image.

## The HTTP API

Each operation is a GET. `deskcam api` prints the same reference from `/api/help`, which
is generated from the one parameter list in `backend/app/src/dev/deskcam/Params.java`. If
this table ever disagrees with `/api/help`, `/api/help` is right and this is stale.

| Endpoint | Purpose |
|---|---|
| `/api/status` | The settings, the sensor limits, and the measured exposure, ISO and focus **of the preview** |
| `/api/still` | A full resolution JPEG, cropped to the ROI |
| `/api/raw` | A full sensor RAW frame as a DNG. Refer to the note below. |
| `/api/burst` | `n` frames with identical settings, as one tar archive. **206** when it produced fewer than `n`. |
| `/api/shadingmap` | The lens shading map, if the device gives one |
| `/api/frame` | One preview JPEG. Much quicker. |
| `/api/stream` | MJPEG. A view only: `fps`, `n`, `w`, `h`, `jpegq`. |
| `/api/set` | Apply the parameters. Give the result. |
| `/api/af` | Do one autofocus sweep |
| `/api/focussweep` | `steps` stills as the lens walks from `from` to `to` in dioptres, as one tar |
| `/api/bracket` | `stops` stills at doubling exposures from `base`, as one tar |
| `/api/walk` | One still at each of `values`, walking the camera parameter named by `vary` |
| `/api/reset` | Set all values to the default |
| `/api/orientation` | The angle to gravity and the ambient light |
| `/api/cameras` | List the cameras |
| `/api/help` | This reference as JSON |
| `/api/nettest` | An outbound test for the permission fault above. It connects only back to the address that called it. |
| `/` | The browser panel. Touch to centre. Turn the wheel to zoom. |

### Camera state

These persist until something changes them again, and they are correct on every endpoint
except `/api/stream`.

| Parameter | Meaning |
|---|---|
| `camera`, `cam` | The camera id. The rear camera is `0`. |
| `zoom`, `zoomby` | The software zoom. The value `1.0` is the full sensor. |
| `cx`, `cy` | The centre of the ROI, from 0 to 1 |
| `dx`, `dy` | A relative move, in fractions of the current ROI width |
| `af` | `off`, `auto`, `macro`, `continuous`, `video`, or `edof` |
| `focus`, `focusm` | The manual focus in dioptres, or in metres |
| `ae` | `on` or `off` |
| `exposure`, `shutter` | `1/120`, `8ms`, `250us`, `0.5s`, or nanoseconds. This sets `ae=off`. |
| `iso`, `sensitivity` | The sensitivity. This sets `ae=off`. |
| `ev`, `aelock` | The compensation and the lock, when `ae=on` |
| `awb`, `awblock` | The white balance mode and the lock |
| `awbgains` | `R,GE,GO,B`, or `neutral` for 1,1,1,1, or `auto`. Implies `awb=off` |
| `torch` | `0` to `torch_max_level`, or `off`, `on`, or `max` |
| `measure` | `on` stops all non-linear processing. Use it for measurement. |
| `shadingmap` | `on` asks the HAL to report its lens shading map |
| `rotate` | `0`, `90`, `180`, or `270`. This turns the pixels. |
| `previewsize`, `stillsize` | The capture sizes. These make a new session. |

### Presentation

These describe how **one** picture comes back. They apply to the request that names them
and are then forgotten.

| Parameter | Meaning |
|---|---|
| `w`, `h` | Change the size after the crop. One value keeps the aspect ratio. |
| `jpegq`, `quality` | The quality. The default is 92. |

When `w` and `h` persisted they silently rescaled the next capture and disabled the
untouched-JPEG path for ever, which is a measurement fault rather than an inconvenience.
`rotate` stays camera state, because it describes how the phone is bolted down.

### Router

| Parameter | Meaning |
|---|---|
| `reset` | `reset=1` sets all values to the default before the rest of this request |
| `settle` | The wait in milliseconds after a change, before the capture. 0 to 5000. |
| `timeout` | The wait for the capture itself, in milliseconds |
| `fresh` | The number of preview frames to discard after a change. The default is 2. |
| `n` | The burst length, or the frame limit of a stream |
| `fps` | The stream rate, 0.1 to 30 |
| `wait` | The wait after an autofocus sweep |
| `port` | The port for `/api/nettest` |
| `format` | `format=raw` makes `deskcam burst` take DNG frames one at a time |
| `sharpness` | `sharpness=1` makes `/api/status` convert one fresh preview frame first |
| `from`, `to`, `steps` | The focus sweep: the first and last lens position in dioptres, and how many frames |
| `base`, `stops` | The exposure bracket: the shortest exposure, and how many frames of twice the one before |
| `vary`, `values` | The walk: which camera parameter to vary, and the list to vary it over |

### Sweeping the focus

```sh
deskcam focussweep from=3 to=6 steps=7
```

Seven full-resolution stills as the lens walks, each with its own sidecar, plus
`walk.json` for the set. **The steps are equal in dioptres, never in millimetres.** Depth
of field is very nearly constant per dioptre and wildly unequal per millimetre: near the
98 mm closest focus one millimetre is about a tenth of a dioptre, and at half a metre it is
four thousandths. A sweep spread evenly in millimetres would crawl at one end and step over
the subject at the other.

`focusDistanceCalibration` on this device is `APPROXIMATE`, so a dioptre here is a lens
position and not a distance. A stack needs the positions ordered and evenly spread, and
that is all this claims. A real sweep of 7 frames over 3 to 6 dioptres took 4.2 s, and the
lens landed within 0.02 of every step it was asked for.

The starting focus is put back afterwards, including when a step fails. A sweep is an
excursion, not a change.

### Walking anything else

`/api/focussweep` and `/api/bracket` exist because their step rule is knowledge: dioptres
for one, whole PWM periods for the other. For every other axis you already know the values
you want, so you give them:

```sh
deskcam walk vary=torch values=0,10,20,45 exposure=40ms iso=100 awbgains=neutral
```

One still per value, as a tar, with a sidecar apiece recording what it was asked for beside
what the camera reported. Only camera state can be walked, since presentation dies with the
request that names it and the router never reaches the camera. It knows no step rule and
invents no values, which is the point: a list you typed cannot hand you a wrong rule.

**Fix everything you are not walking.** A walk varies one thing on purpose, and anything
the camera is still deciding varies alongside it. The same torch walk at 0, 20 and 45, run
twice on this bench:

| | torch 0 | torch 20 | torch 45 |
|---|---|---|---|
| automatic exposure | 139 DN | 173 DN | 141 DN |
| exposure fixed | 32 DN | 107 DN | 140 DN |

The first row is not even monotone: the exposure loop gave back the light the torch added,
and nothing in the frames says so. The second is the curve that was there all along. The
manifest warns when `ae` or the white balance was left to the camera, and the CLI prints
the warning.

Good axes: `torch` (1..45 here) for specular subjects, `iso` (56..7111, analogue only to
444) for a gain walk, and `exposure` with the lens covered for dark frames or against a
flat field for flats. **Not `zoom`** — zoom is a crop of the sensor, so at zoom 1 you
already have every pixel and walking it gains no resolution.

### Bracketing a lit panel

A lit panel in a dark bezel is wider than the sensor can hold in one frame, so it takes
several exposures and a merge. The trap is that a panel is not a steady source: it is
switched at some hundreds of hertz, and an exposure that is not a whole number of its PWM
periods reads a different part of the duty cycle. Frames that should differ by exactly one
stop then disagree about a panel that never changed, and the merge is wrong in a way that
looks like data.

So the bracket steps in **powers of two from one period**, which keeps every frame one stop
apart AND a whole number of periods. Set the base to one period of the panel:

```sh
deskcam bracket base=1/240 stops=4 iso=56
```

Whether the sensor actually delivered whole periods is a separate question, and it is
checked rather than assumed. Every frame records what it really did, and `deskcam bracket`
prints it:

```
deskcam: what the sensor actually did
  exposure-00-4.17ms1-240.jpg    4.15ms (1/241)       0.9954 periods
  exposure-01-8.33ms1-120.jpg    8.30ms (1/121)       1.9909 periods
  exposure-02-16.67ms1-60.jpg    16.63ms (1/60)       3.9920 periods
  exposure-03-33.33ms1-30.jpg    33.31ms (1/30)       7.9942 periods
```

A frame more than two hundredths of a period out is named on the line, because the error
lands at whatever phase the exposure started at and so wobbles between frames rather than
being an offset they share. Asking for a base far below what the sensor can resolve shows
it plainly: `base=1/8000` on this device returns 85.5 us for a requested 125 us, which is
0.68 of a period, and every frame says so.

**Merge on the measured exposure and never on the nominal stop.** The real ratios above are
2.0000, 2.0051 and 2.0026, not 2. The sidecars carry `exposure_ns`, `base_periods` and
`period_error` for exactly this.

The ISO is not touched, because two frames that differ in both time and gain cannot be
merged without knowing how the gain behaved. Above `max_analog_iso`, which this device
reports as 444, the extra gain is arithmetic on values the sensor already read, so the
bracket warns and tells you to apply it on the workstation instead.

A bracket of 12 stops from 1/240 spans 4.17 ms to 8.53 s, a range of 2048 to 1, and took
64 seconds. The waiting time scales with the exposure being asked for; a long one is slow,
not broken.

### Focus by number, without sending a picture

`/api/status` reports a `sharpness` block: the variance of the Laplacian over the region of
interest of a preview frame. It is one of the two calculations that belong on the device,
because it lets an agent close a focus loop by moving the lens and reading a number instead
of pulling frames across the network.

```sh
deskcam set focus=4.25 && deskcam show sharpness=1
zoom 1x  at 0.5,0.5  af off  ae manual  29.97ms (1/33)  iso 100  MEASURE  sharp 33.6
```

A real sweep of a rule 235 mm from the lens, exposure and ISO held fixed, one reading a
step:

| dioptres | 1.0 | 3.0 | 3.5 | 4.0 | **4.25** | 4.5 | 5.0 | 5.5 | 6.0 | 9.0 |
|---|---|---|---|---|---|---|---|---|---|---|
| sharpness | 3.5 | 11.4 | 20.3 | 30.8 | **33.6** | 31.0 | 18.0 | 9.4 | 5.8 | 2.9 |

One maximum, monotone either side of it, at the distance the phone's own autofocus picks.

Three things about the number. It is a **comparison and never a measurement**: it moves
with the subject, with how much of the frame the region of interest holds, and with the
noise, which at high ISO is itself high-frequency detail. Only compare readings taken with
everything but the focus held still.

It **describes the last preview frame that was converted**, which may be old, so its age is
reported beside it and `deskcam show` prints the age once it is over half a second.
Without `sharpness=1` nothing new is converted, because a status poll that demanded a frame
would have an open console page converting every frame at thirty a second for a page that
is not showing video (decision D7).

It costs about **9 ms** on a Pixel 6a and the cost is reported with the value. The sample
count is capped for that: rows are skipped, never columns and never the kernel's
neighbours, because a kernel over subsampled pixels measures a blurrier image than the one
in front of the camera and would put the peak in the wrong place.

### Errors

An unknown parameter, or a value the server cannot read, is an HTTP 400 with
`{"ok": false, "error": "..."}` and nothing changes. This holds on every endpoint. Each
numeric parameter has a range, and the ranges that depend on the device are in
`limits` on `/api/status`: `max_output_edge`, `max_output_pixels` and `burst_max`. A
request larger than the phone's heap is refused before the capture rather than during it.

## RAW capture

Use `/api/raw` or `deskcam raw` for measurement work. The JPEG pipeline applies tone maps,
noise reduction, and sharpening. These steps remove the linear relation between light and
pixel value. RAW keeps that relation.

```sh
deskcam raw -o panel.dng exposure=1/120 iso=56
```

**The software crop does not apply to a DNG.** A DNG holds the full sensor array, because
the workstation must demosaic before it crops. The server reports your framing in the
`X-DeskCam-ROI` response header.

The file holds all data that correct colour needs. This includes the black level, the
white level, both colour matrices, both forward matrices, and the two calibration
illuminants. `dcraw`, `rawpy`, and `darktable` all read it.

Keep the ISO at 56 and change only the exposure time. Above ISO 444 the sensor gain is
digital. It is better to apply digital gain to the RAW data on the workstation.

### The white balance a session can repeat

`measure=1` locks the white balance, which holds the gains at whatever the camera happened
to choose. Two sessions lock different gains, so two sets of captures of one subject differ
in colour for a reason nothing chose. Every capture has recorded the achieved gains as
`measured.awb_gains` since card 42; now they can be set as well.

```sh
deskcam set awbgains=neutral            # 1,1,1,1: no white balance, the sensor's own ratios
deskcam set awbgains=1.99,1.0,1.0,2.07  # the gains a previous session used
deskcam set awb=auto                    # give it back to the camera
```

Four numbers and not three, because a Bayer cell has two green photosites and Camera2 gives
them separate gains. Setting them puts the colour correction on the identity matrix as well,
since a chosen gain under an unchosen matrix is still a colour nobody wrote down. Verified
on the phone: `awbgains=neutral` reports back `[1, 1, 1, 1]` with the transform at identity,
against `[2.055, 1, 1, 2.002]` and a real matrix on automatic.

The measurement tools enforce the other half. `deskcam analyse aatest` refuses two captures
taken through gains more than half a percent apart, because that is a difference in colour
and not in the instrument, and it names `awbgains` as the fix. It tolerates the last digit,
because an automatic white balance wanders between adjacent frames. `linearity` warns when
the gains move during a series.

## Measurement mode

Use `measure=1` for any capture that you intend to measure. The default pipeline makes a
photograph look good. It applies a tone map, noise reduction, edge enhancement, and lens
shading correction. Each step breaks the relation between light and pixel value.

```sh
deskcam set measure=1 iso=56 exposure=1/120
deskcam snap -o panel.jpg
```

Measurement mode sets noise reduction, edge enhancement, hot pixel correction, lens shading
correction, and aberration correction to off. It sets a linear tone curve. It sets OIS off,
because OIS moves on a fixed mount. It locks the white balance.

Measured on 2026-09-10 with `deskcam analyse linearity`, from six captures between 50 ms
and 283 ms at ISO 56, on a static bench scene:

| Quantity | Result |
|---|---|
| Value change per doubling | **2.062x** (95% 2.041 to 2.083) |
| Samples | 6 captures, one dropped for clipping |
| Power-law fit | R squared 1.000 |
| Pedestal at zero exposure | **-2.39 DN** |

The pedestal is the interesting part. A constant offset in the levels bends the measured
exponent, upward when it is negative, and this one is large enough to account for the whole
excess: remove 2.05 DN and the exponent is 1.0028, which is 2.004x per doubling. So the
sensible reading is **the response is linear, with a black-level offset of about two
digits**, not that it is slightly better than linear.

That is what the old figure of 2.02x was hiding. It was the mean of four ratios with the
spread discarded, one of them sitting on the floor of the 8-bit range, and it happened to
land near the truth for two reasons that cancelled. Reproduce it yourself:

```sh
for ms in 50 71 100 141 200 283; do
    deskcam snap -o lin/e$ms.jpg measure=1 iso=56 exposure=${ms}ms settle=600
done
deskcam analyse linearity lin/ --region 0.5,0.68,0.30,0.12
```

`deskcam status` gives a `pipeline` block. The block reports what the camera applied, not
what you asked for.

Lens shading correction is off in measurement mode, so the frame shows the true optical
response. You must divide out the vignetting yourself with a measured flat field. This
camera does not give a lens shading map. It lists the map mode and the map size, but the
map is not one of its result keys.

## Burst capture

A burst gives many frames with identical settings. Average them on the workstation and the
noise falls with the square root of the frame count.

```sh
deskcam burst 16 measure=1 iso=56 exposure=1/60
deskcam burst 8 format=raw            # DNG frames, one request each
```

The JPEG burst goes to the camera as one submission, so the HAL runs the frames back to
back. One test gave 12 full resolution frames in 642 ms. That was published as 18.7 frames
per second, which counts 12 frames across 11 intervals; 11/0.642 is 17.1. **Still
unconfirmed**, because no committed tool measures the frame rate yet. Expect roughly 17 to
19 frames per second at 12 megapixels and time it yourself if it matters.

Set the exposure below the correct value. Then the highlights never clip, and the average
recovers the shadows. This is the one useful idea from HDR+, and it works better here than
on a phone, because a fixed mount needs no frame alignment.

Measured on 2026-09-10 with `deskcam analyse burst-noise`, from one burst of 16 frames at
200 ms and ISO 56 in measurement mode:

| Frames averaged | Measured | Square root of the count |
|---|---|---|
| 4 | **2.026x** (95% 1.953 to 2.082) | 2.000 |
| 6 | **2.515x** (95% 2.359 to 2.659) | 2.449 |

Both intervals contain the prediction, so on this camera a burst average behaves as theory
says. The older claim of 2.25x, read as 92% of the prediction, is not confirmed; that
figure came from one particular split of twelve frames into groups, and which split was
never recorded. This tool averages over two hundred random splits instead of choosing one,
and the interval covers both the split and the choice of image region.

```sh
deskcam burst 16 -o burst/ measure=1 iso=56 exposure=200ms settle=600
deskcam analyse burst-noise burst/ --group 4
```

## Measuring, and knowing when not to

`frontend/analysis/` holds the measurement tools. They read captures and sidecars off disk
and never talk to the camera, so they work on anything you photographed last month. They
need `numpy` and `pillow`, which taking a picture does not:

```sh
pip install -e '.[analysis]'
```

**Start with the noise floor.** Two captures of the same subject with the same settings
differ by the noise of the instrument, and a measurement smaller than that difference is
the camera talking to itself:

```sh
deskcam aatest measure=1 iso=56 exposure=200ms
```

```
aa-test: 1.415 DN, n=68252, fraction of pixels not pinned 1.000
  a measurement of this scene must differ by more than 1.42 DN (1.38% of the level)
  before it is a difference and not this camera
```

That figure is recorded next to your captures as `deskcam-noisefloor.json`, and the other
tools read it and **refuse** a result that sits inside it.

Every tool returns its value, its interval, its sample count and its confidence together,
and refuses below a stated limit rather than printing a number with a caveat next to it. A
caveat beside a number does not travel with the number.

| Tool | Confidence is | Limit | Why that limit |
|---|---|---|---|
| `scale` | autocorrelation peak height | 0.60 | correct strips measured 0.76 to 0.96, harmonic misreads about 0.33 |
| `linearity` | power-law fit R squared | 0.980 | both a linear response and an sRGB curve fit above 0.99 |
| `burst-noise` | interval tightness | 0.85 | the question turns on an 8% difference, so a wider interval decides nothing |
| `aatest` | fraction of pixels not pinned | 0.99 | a pixel at 0 or 255 records no difference and flatters the floor |

Exit codes are 0 for a measurement, 2 for a refusal, 1 for a tool that could not run. Add
`--json` for the full record.

### Scale is not a property of this camera

`deskcam scale` measures pixels per millimetre from a regular reference in the frame, a
steel rule or graph paper. **It changes every time the stand moves**, so it is never quoted
as a camera specification. Measure it in the picture you care about:

```sh
deskcam scale shot.jpg --pitch-mm 1.0 --region 0.365,0.41,0.66,0.05
deskcam measure shot.jpg 412,308 1190,306      # 47.4 mm (95% 47.3 to 47.5)
```

`deskcam scale` writes `deskcam-scale.json` beside the captures, and every capture taken
after it carries the number in its own sidecar for as long as the framing holds. Change the
zoom, the pan, the rotation or the camera, and the sidecar says which one changed and that
the scale no longer describes it, rather than going quiet:

```json
"scale": {"applies": false, "measured_from": "deskcam-20260910-124151.jpg",
          "why": "the scale was measured at zoom 2 and this capture is at zoom 4"}
```

A still and a preview frame of the same view are the same field of view sampled into a
different number of pixels, so the scale converts between them by the width ratio and says
so. What none of it can check is the distance: nothing in this system can see the stand
move, and a sidecar that carries a scale is making a claim about the settings and never
about the bench. `deskcam analyse scale` is the same measurement without recording it.

On one setup on 2026-09-10 the rule gave 16.42 px/mm (95% 16.38 to 16.46, 40 strips) and
the graph paper in the same frame gave 16.54 px/mm (95% 16.52 to 16.57, 37 strips). Note
that those two intervals do not overlap. Two references 0.7% apart, each with an internal
spread far tighter than that, is a useful reminder that **the interval a method reports is
its precision, not its accuracy**.

The tool also says when more than one regular pattern is in the frame, because a bench
usually has several and only you know which one is the reference. The first run of it here
locked onto graph paper, was told it was looking at millimetres, and reported a scale five
times too large with 107 strips agreeing and a healthy correlation. Nothing about the fit
was wrong.

### Making one image out of many

Three tools that turn a set of frames into a single image. Each writes the picture and then
says what it can prove about it, and each refuses rather than handing back something that
looks like a result and is not.

```sh
deskcam burst 16 && deskcam analyse average DIR   # one clean image, 16-bit
deskcam focussweep from=3 to=6 steps=7 && deskcam analyse stack DIR
deskcam bracket base=1/240 stops=6 && deskcam analyse hdr DIR
```

**Average** is the useful half of HDR+. It writes 16-bit, because averaging sixteen frames
lowers the noise by a factor of four and 8 bits would throw away the two bits that bought.
The improvement is measured rather than predicted: `before / sqrt(N)` is arithmetic, so the
figure comes from `burst-noise`, which splits the burst many ways and measures both ends of
the ratio the same way. Measured on twelve frames here: 1.757x less noise, 95% interval
1.689 to 1.807, against a prediction of sqrt(3) = 1.732.

**Stack** takes the sharp part of every frame of a focus sweep. It corrects focus breathing,
measured between neighbouring frames and chained rather than taken against one reference,
because frames from opposite ends of a sweep are sharp in different places and correlate at
-0.38. It refuses when one frame is sharpest over most of the picture: that subject fits
inside one depth of field, its best frame is already the answer, and blending can only blur
it.

**Hdr** merges a bracket into radiance, in DN per second, as 32-bit float with a 16-bit
linear view beside it. No tone map, ever; that is a separate step. It merges on each frame's
**measured** exposure and refuses a bracket taken without `measure=1`, because
`value / exposure` is only radiance when the response is linear. It also checks that the
frames agree with each other: neighbouring exposures of one scene should give the same
radiance, and on this camera they differ by 1 to 3%, which is a small negative offset in the
pipeline that a dark frame would measure.

## What each capture records

Every capture writes a JSON sidecar beside the image, and the same record goes into the
file itself. A sidecar is easier to read, but it gets separated from its image when files
are copied. Anything you need to trust a measurement later travels in both places.

| Where | Field |
|---|---|
| `NAME.json` | The full settings, the measured values, the pipeline state, the orientation |
| JPEG | EXIF `UserComment` and `Software` |
| DNG | `ImageDescription` |

The record holds the tilt of the camera, from the gravity sensor:

```
tilt 1.57 deg, nearly straight down (1.57 degrees from gravity), 32 samples
gravity {x: 0.22, y: 0.16, z: 9.81}   ambient 85 lux
```

**Read what this quantity is.** It is the angle between the optical axis and **gravity**.
Skew of a flat subject comes from the angle between the camera and the **plane of the
subject**, and the two are equal only when the subject lies on a level surface. On a
tilted jig they differ by the tilt of the jig, so a small reading is evidence of square
framing only when you know the bench is level.

The angle is averaged over the last 32 samples rather than taken from one reading, because
one unfiltered accelerometer sample carries the noise of the sensor and of the bench, and
three figures from one sample claim a precision that is not there. The count comes back
with the angle. The words that go with it carry the number, so a reading near a band
boundary reads as what it is rather than as a different state.

A tilted camera stretches one side of a flat subject, which corrupts a measurement of
size. The angle belongs with the picture. `deskcam status` and `/api/orientation` report
it live.

**The sensors give the angle only.** They give no distance and no position, so a picture
still needs a scale reference in the frame, such as a ruler or graph paper, before you can
measure real sizes.

## Examples

**Photograph an OLED or LCD without PWM bands.** Set the exposure to a whole multiple of
the panel refresh period. If you do not, the rolling shutter reads different parts of the
duty cycle down the frame.

```sh
deskcam set exposure=1/60 iso=200 awb=daylight awblock=on   # a 60 Hz panel
deskcam snap
```

Lock the white balance also. If you do not, the automatic white balance moves between
shots. It then makes colour differences that are not real.

**Look closely at a board. Use the LED as a light.**

```sh
deskcam set focusm=0.12 torch=30 zoom=6
deskcam snap -o u3-pin1.jpg
```

**The phone is upside down on an arm.**

```sh
deskcam set rotate=180
```

**Move across a region and keep each frame.**

```sh
for x in 0.25 0.5 0.75; do deskcam snap -o "scan-$x.jpg" zoom=4 cx=$x cy=0.5; done
```

## Notes and limits

The zoom stops where a crop is no longer useful. On this sensor the limit is about 63x.
Above about 6x you see very few pixels. It is better to move the phone closer. Then use
`focusm` down to 0.098 m.

Macro is an optical limit. At the 98 mm minimum focus distance the camera gives about 33
pixels for each millimetre, roughly 30 micrometres for each pixel. That figure is
arithmetic from the sensor size and the stated minimum focus distance, not a measurement,
and `focusDistanceCalibration` on this device is `APPROXIMATE`. It is enough to read
silkscreen and find a part. It is not enough to see a solder fillet. A clip-on macro lens
is the correction. **The scale of an actual picture depends on where the stand is**, so
measure it from a reference in the frame rather than trusting a stored number.

`/api/still` sends the JPEG of the camera without a change when the zoom is at or below
1.0001, and there is no rotation, and there is no resize. This is the quickest path and the
best quality. Any crop, rotation, or resize costs a decode and a new encode.
`BitmapRegionDecoder` reads only the necessary tile. Thus a large zoom costs less than a
small zoom.

**The limit of 1.0001 puts a still on one of two pipelines**, so each capture records which
one it took in `settings.capture_path`, as `camera_jpeg` or `decoded_and_reencoded`. Two
captures on opposite sides of the limit are different kinds of image, and comparing them
measures the pipeline rather than the subject.

The app converts a preview frame only when a client asks for one. An idle service costs
almost nothing.

### The console and the access key

`deskcam token new` makes a random key, keeps it in `~/.config/deskcam/token` at mode 600,
and the next pairing code carries it to the phone. `deskcam token clear` removes it and the
next pairing tells the phone to forget it too. The console has the same two as buttons. The
key is never typed on the phone's on-screen keyboard, which was the reason nobody turned it
on.

A pairing changes the key on a phone that is already serving, with no restart. The service
reads it from its preferences at every request, because the alternative was worse than it
sounds: the key used to be copied into the server when the server started, so pairing a key
into a running service left the copy behind, the console reported a key was set, and the
camera went on answering anyone on the network.

`deskcam serve` binds to every interface, because the phone has to reach it to pair, and
then offers the network exactly one route, `/p/`, the pairing callback. The page, the roll
and the QR image answer the browser on the machine the console runs on and nobody else,
because the QR carries the access key. Pairing takes the phone's address from where the
request came from and not from what the request says about itself, and one code pairs once.
The console serves only the captures in the shots directory, by name, and only `.jpg`,
`.jpeg`, `.dng` and `.json`.

The page never talks to the phone. Controls and the live view both go through the console,
which is the only party holding the key; an `<img>` pointed straight at the phone had no
key to send and went black the moment anyone set one. When the stream fails, the console
puts the phone's own answer on the page rather than showing an empty frame.

The live view is a view. The console forwards nothing but `fps`, and `/api/stream` refuses
any parameter that would change the camera, so opening the console in a browser cannot
change the next capture an agent takes.

There is still no TLS and the API token is still off by default; on an untrusted network,
set one.

USB is a reliable alternative. It does not use the local network permission, because it
goes through loopback.

```sh
deskcam usb        # adb forward. The target becomes 127.0.0.1:8080.
```

**The app cannot start after a reboot.** This is a platform limit, not a fault. A reboot
test confirms that Android refuses both methods:

```
ForegroundServiceStartNotAllowedException: FGS type camera not allowed to start from BOOT_COMPLETED
Background activity launch blocked! ... (BAL_BLOCK)
```

`BootReceiver` makes the attempt. `CamService` catches the refusal, so the service stops
quietly. Before this correction it crashed again and again. After a reboot, use `deskcam
start`, or touch the app one time.

The permission `SYSTEM_ALERT_WINDOW` would remove the activity launch limit. That
permission is too wide. This app does not ask for it.

**There is no HTTPS.** The service is for a trusted LAN. A self-signed certificate would
make `-k` necessary on each request, for no real gain. Set a token in the app to make
`?token=...` or an `Authorization: Bearer` header necessary. If you need encryption, or
access from outside the LAN, put the phone on a WireGuard or Tailscale network. Keep the
server on plain HTTP behind it.
