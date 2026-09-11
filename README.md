# DeskCam: Precision Optical Bench Instrument & Agent Camera Server

DeskCam transforms a spare Android smartphone into a calibrated optical bench instrument and camera server. You control the camera from the command line, from shell scripts, or from an autonomous AI coding agent (such as Claude Code). It is built for a Google Pixel 6a (or compatible Camera2 device) running GrapheneOS or stock Android.

Its primary workbench tasks are **circuit debugging and SMD inspection**, **OLED/LCD display evaluation**, and **giving AI coding agents deterministic visual feedback**.

Each camera control is an HTTP GET or POST request. You can control the instrument from `curl`, from a Python script, or from a coding agent with zero external client libraries.

```sh
deskcam snap zoom=6 cx=0.32 cy=0.68 torch=25 focusm=0.12
```

For why it is built this way, refer to [docs/DECISIONS.md](docs/DECISIONS.md). For an interactive visual walk-through of sensor physics, software ROI cropping, and dioptric focus curves, open [explainer/index.html](explainer/index.html).

---

## Trust and Architecture at a Glance

What you are trusting when you run this, and where to check each claim:

* **Zero Gradle, Zero Android Studio, Zero AGP**: The phone app compiles directly with Android SDK build tools (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`) in under 5 seconds. No Gradle daemon, no downloading unknown Maven plugins or transitive dependencies.
* **Zero Go Dependencies**: The workstation CLI compiles to a single, static binary with zero external packages.
* **Zero Cloud Calls or Telemetry**: The Android app does not contact external internet services. It operates strictly on your trusted local Wi-Fi LAN or over an `adb reverse` USB loopback cable.
* **Stateless & Contract-Tested**: The HTTP interface is the single, clean seam between the phone and the workstation. A Python test suite (`frontend/test_contract.py`) guards the parameter surface against documentation or code drift.

**There is no HTTPS, and there is no authentication unless you set a token.** The phone serves plain HTTP on port 8080 to anyone on the same network, and that open state is the default (`Access.java`). This is a bench tool for a trusted LAN. If the network is shared, set a token (see [Console Security](#console-security-qr-pairing-and-access-tokens)). If you need encryption, or reach from outside the LAN, put the phone on a WireGuard or Tailscale network and keep the server on plain HTTP behind it. A self-signed certificate would only add `-k` to every request.

There is no signed release, checksum, or reproducible build. You build the APK yourself from source with the SDK tools listed below, and the build script signs it with a debug keystore it generates on first run.

| Component | Runs On | Technology | Purpose |
|---|---|---|---|
| `backend/` | Android Phone | Pure Java (SDK API 33–37) | Foreground camera service, `HttpServer`, raw sensor readout, software ROI crop |
| `frontend/go/` | Workstation | Static Go binary | `deskcam` CLI, browser workbench console, USB/Wi-Fi pairing |
| `frontend/analysis/` | Workstation | Python 3.11 (`numpy`, `pillow`) | Optional measurement tools (linearity, scale, HDR, focus stacking) |
| `explainer/` | Browser | Static HTML / CSS / JS | Interactive visual guide to optics, PWM synchronization, and API mechanics |
| `skill/` | Workstation | Claude Code Skill | Agent integration definition and tool calling instructions |
| `docs/` | Workstation | Markdown | Architecture decisions, the decisions and their reasoning, and DSL documentation |

---

## Table of Contents

1. [Quick Start (Tutorial)](#quick-start-tutorial)
2. [How-To Guides (Workbench & Agent Workflows)](#how-to-guides-workbench--agent-workflows)
   - [Give AI Agents Eyes (Claude Code Integration)](#give-ai-agents-eyes-claude-code-integration)
   - [Inspect Surface-Mount PCBs & Hardware](#inspect-surface-mount-pcbs--hardware)
   - [Capture Flicker-Free Displays (PWM Synchronization)](#capture-flicker-free-displays-pwm-synchronization)
   - [Focus Stacking & Macro Depth of Field](#focus-stacking--macro-depth-of-field)
   - [Calibrate Physical Scale & Measure Distances](#calibrate-physical-scale--measure-distances)
   - [Execute Atomic Action Tapes (Scripts)](#execute-atomic-action-tapes-scripts)
   - [Linear Radiometric Measurement & RAW DNG](#linear-radiometric-measurement--raw-dng)
3. [Reference (The Contract)](#reference-the-contract)
   - [CLI Reference](#cli-reference)
   - [HTTP REST API Endpoints](#http-rest-api-endpoints)
   - [Camera state](#camera-state)
   - [Presentation](#presentation)
   - [Router](#router)
   - [Errors](#errors)
   - [JSON Sidecars and Telemetry Headers](#json-sidecars-and-telemetry-headers)
4. [Explanation & Engineering Theory](#explanation--engineering-theory)
   - [Why Software Region-of-Interest (ROI) Cropping?](#why-software-region-of-interest-roi-cropping)
   - [Dioptres vs. Millimetres in Optical Focus](#dioptres-vs-millimetres-in-optical-focus)
   - [Sensor Linearity and Radiometric Mode](#sensor-linearity-and-radiometric-mode)
   - [Android 17 Local Network Permission Isolation](#android-17-local-network-permission-isolation)
   - [Thermal Rate Shedding and Battery Care](#thermal-rate-shedding-and-battery-care)
   - [Console Security, QR Pairing, and Access Tokens](#console-security-qr-pairing-and-access-tokens)
5. [Quality Gates and Verification](#quality-gates-and-verification)
6. [License](#license)

---

## Quick Start (Tutorial)

Set up DeskCam and capture your first bench image in under three minutes.

### Prerequisites

* **Workstation**: Linux or macOS with Go 1.22+ and JDK 17+.
* **Android SDK**: `platforms/android-37.0` and `build-tools/37.0.0` (or set `$ANDROID_HOME`, `$PLATFORM`, and `$BT_VER`).
* **Android Phone**: Pixel 6a (or Android 13+ device with Camera2 `LEVEL_FULL` support) connected via USB with USB debugging enabled.

### 1. Build the Phone App and Workstation CLI

```sh
# 1. Build the Android APK (takes ~5 seconds, no Gradle)
./backend/build.sh

# 2. Build the static workstation CLI
cd frontend/go && go build -o deskcam . && cd ../..

# 3. Put deskcam on your PATH (optional)
mkdir -p ~/.local/bin && ln -sf "$PWD/frontend/go/deskcam" ~/.local/bin/deskcam
```

### 2. Install and Start the App

Install the APK onto your phone. The `-g` flag grants every runtime permission the manifest declares at install time, so no dialog appears on the phone. For this app that is camera, local network, and notifications:

```sh
adb install -r -g backend/build/deskcam.apk
```

Start the foreground camera service on the phone:

```sh
deskcam start
```

*(Alternatively, tap the DeskCam app icon on the phone and tap **Start**).*

### 3. Connect and Capture Your First Frame

You can connect over USB (via `adb forward`, zero network configuration) or over local Wi-Fi:

```sh
# Option A: Connect over USB loopback (recommended for initial setup)
deskcam usb

# Option B: Connect over Wi-Fi
deskcam wifi

# Verify connection and show live instrument state
deskcam show
```

Capture your first still image:

```sh
deskcam snap
```

The command saves the full-resolution still to disk and prints its absolute path:
```
deskcam-20260911-061500.jpg
```

Beside the image, DeskCam creates `deskcam-20260911-061500.json` containing the complete optical state, sensor exposure, ISO, focus dioptres, and device thermal telemetry.

---

## How-To Guides (Workbench & Agent Workflows)

### Give AI Agents Eyes (Claude Code Integration)

DeskCam is designed from first principles as an instrument for autonomous coding agents (Claude Code, Gemini CLI, local agents). It obeys eight machine-usability rules:

1. **R1**: Each operation is one shell command with an unambiguous exit code (0 = success, 2 = refusal, 1 = error).
2. **R2**: A capture command prints a single **file path**, never raw binary image data. The agent inspects the file using its own tools, keeping the context window uncluttered.
3. **R3**: Every endpoint accepts the full control set as `k=v` arguments. The agent does not need to maintain state sessions.
4. **R4**: Every response reports **measured** sensor values alongside requested values.
5. **R5**: Unknown or malformed parameters are rejected immediately (HTTP 400).
6. **R6**: The API describes its complete parameter surface at `/api/help`.
7. **R7**: Physical units match engineering datasheets (`1/120`, `8ms`, `250us`, `0.12`).
8. **R8**: All capture and inspection operations are idempotent.

Link the agent skill into Claude Code:

```sh
ln -sf "$PWD/skill" ~/.claude/skills/deskcam
```

In any bash shell or agent conversation:

```sh
# Capture a 4x zoom crop of an IC at normalized coordinates (0.35, 0.42)
img=$(deskcam snap zoom=4 cx=0.35 cy=0.42 torch=20 focusm=0.15)
echo "Agent reviewing: $img"
```

### Inspect Surface-Mount PCBs & Hardware

When inspecting tiny surface-mount components, pin pitches, and solder joints:

```sh
# Zoom 6x onto a component at (cx=0.32, cy=0.68), turn on the LED torch to level 25,
# and lock the manual focus to 120 mm (0.12 m):
deskcam snap zoom=6 cx=0.32 cy=0.68 torch=25 focusm=0.12 -o u4_solder.jpg
```

If the phone is mounted upside-down on an articulating desk boom arm:

```sh
deskcam set rotate=180
```

To focus on a specific IC without changing the overall image framing, use `focusbox`:

```sh
deskcam set focusbox=0.35,0.35,0.15,0.15
deskcam focus hunt
```

### Capture Flicker-Free Displays (PWM Synchronization)

LED and OLED displays cycle power at high pulse-width modulation (PWM) frequencies (e.g., 60 Hz, 120 Hz, 240 Hz, or 480 Hz). If the camera exposure is not an exact integer multiple of the PWM period, rolling shutter capture produces dark horizontal bands across the screen.

1. **Calculate the base period**: For a 60 Hz panel, the period is $1/60\text{ s} \approx 16.67\text{ ms}$. For a 240 Hz panel, it is $1/240\text{ s} \approx 4.17\text{ ms}$.
2. **Lock exposure, ISO, and white balance**:

```sh
# Lock exposure to exact panel PWM multiples and lock white balance
deskcam set exposure=1/60 iso=200 awb=daylight awblock=on
deskcam snap -o display_clean.jpg
```

To capture an exposure bracket across multiple stops without introducing PWM phase errors:

```sh
# Step in exact powers-of-two multiples of the 240 Hz PWM period:
deskcam bracket base=1/240 stops=4 iso=56
```

DeskCam verifies the sensor's physical timing and reports the exact period fractions in the response.

### Focus Stacking & Macro Depth of Field

At close macro distances (10 cm to 30 cm), optical depth of field is fractions of a millimetre.

To capture a focus stack across a circuit board:

```sh
# Sweep the lens over 7 evenly spaced dioptric steps from 3.0 to 6.0 dioptres:
deskcam focussweep from=3 to=6 steps=7
```

DeskCam captures seven full-resolution stills, bundles them into a `.tar` stream, and writes individual JSON sidecars for each step.

To blend the sharp slices into a single deep-focus composite on the workstation:

```sh
# Optional: install workstation analysis dependencies
pip install -e '.[analysis]'

# Blend the stack with focus-breathing correction
deskcam analyse stack deskcam-sweep-*/
```

### Calibrate Physical Scale & Measure Distances

Pixel dimensions vary whenever the phone stand is raised or lowered. To convert pixels into real millimetres:

1. Place a precision steel ruler or 1 mm graph paper in the frame.
2. Run `deskcam scale` on the captured reference image:

```sh
# Measure calibration scale using autocorrelation along a 1.0 mm pitch ruler:
deskcam scale shot.jpg --pitch-mm 1.0 --region 0.365,0.41,0.66,0.05
```

This writes `deskcam-scale.json`. Subsequent captures at the same zoom, pan, and rotation automatically inherit this scale in their JSON sidecars.

3. Measure any distance between two pixel coordinates on the board:

```sh
deskcam measure shot.jpg 412,308 1190,306
# Output: 47.4 mm (95% CI: 47.3 to 47.5 mm)
```

### Execute Atomic Action Tapes (Scripts)

When running multi-step inspection sequences (such as unlit capture $\rightarrow$ LED lit capture $\rightarrow$ focus hunt $\rightarrow$ macro capture), network latency and concurrent browser polling can introduce race conditions.

DeskCam solves this with **Action Tapes** via `POST /api/script`. A tape is a series of verbs executed atomically on the phone. While a tape runs, the camera is locked and external modifications are rejected (HTTP 409).

Create a script file `inspect.dcl`:

```
# Inspect a component under natural light, then with LED torch
SET zoom=2 cx=0.5 cy=0.5 exposure=1/33 iso=200 awbgains=neutral
FOCUSHUNT
SET torch=25
WAIT 500
SNAP
SET torch=0
SNAP
```

Execute the tape atomically:

```sh
deskcam script run inspect.dcl
```

All images and step events stream back over a single `multipart/mixed` connection and are saved locally.

### Linear Radiometric Measurement & RAW DNG

Consumer smartphone camera pipelines apply aggressive non-linear transformations: tone-mapping curves, dynamic noise reduction, edge sharpening, and vignetting compensation. These corrupt physical light measurements.

For true linear radiometry ($R^2 = 1.000$ with respect to exposure):

```sh
# Set measurement mode (disables tone curves, sharpening, OIS, and NR)
deskcam set measure=1 iso=56 exposure=1/120 awbgains=neutral
deskcam snap -o linear_capture.jpg
```

To capture untouched RAW Bayer frames:

```sh
deskcam raw -o sensor_dump.dng exposure=1/120 iso=56
```

The resulting DNG contains the full 12-megapixel Bayer matrix, black and white saturation levels, color calibration matrices, and illuminant metadata. You can process it directly with `rawpy`, `dcraw`, or `darktable`.

---

## Reference (The Contract)

### CLI Reference

```
Capture Verbs:
  deskcam snap [-o FILE] [k=v ...]     Full-resolution still JPEG, cropped to ROI
  deskcam frame [-o FILE] [k=v ...]    Quick preview JPEG frame
  deskcam stream [-o FILE] [n=N]       MJPEG live video stream
  deskcam raw -o FILE [k=v ...]        Full-sensor untouched RAW DNG capture
  deskcam burst N [k=v ...]            Burst of N frames with identical settings (JPEG or RAW)

Inspection & Optics:
  deskcam show                         One-line optical and thermal summary
  deskcam status                       Full instrument state and sensor telemetry as JSON
  deskcam set k=v [k=v ...]            Apply one or more camera parameters
  deskcam reset                        Reset all parameters to default state
  deskcam zoom N                       Set software zoom (1.0 = full sensor)
  deskcam pan up|down|left|right [amt] Pan ROI by fraction of current view
  deskcam center                       Center ROI at (0.5, 0.5)
  deskcam af                           Execute one autofocus sweep
  deskcam focus METRES|auto            Set manual focus in metres (or auto)
  deskcam focus hunt                   Execute phone-side sharpness search and lock peak
  deskcam exposure 1/120|8ms|250us     Set manual exposure time
  deskcam iso N                        Set sensor sensitivity (ISO)
  deskcam auto                         Return exposure and focus to automatic mode
  deskcam torch 0-45|off|max           Set LED torch brightness level

Sequencing & Sweeps:
  deskcam focussweep from=D to=D steps=N  Dioptric focus sweep bundled into tar
  deskcam bracket base=EXP stops=N        Powers-of-two exposure bracket
  deskcam walk vary=PARAM values=V1,V2    Walk any camera parameter across explicit values
  deskcam script run FILE.dcl             Run atomic action tape on phone

System & Connection:
  deskcam cameras                      List available cameras and hardware capabilities
  deskcam api                          Print API parameter contract from /api/help as JSON
  deskcam open                         Open browser workbench console
  deskcam use URL|usb|wifi             Switch connection endpoint
  deskcam token new|clear              Generate or clear access key
  deskcam start|stop                   Start or stop phone foreground camera service
```

### HTTP REST API Endpoints

Every operation is an HTTP GET, except `/api/script` which is POST.

| Endpoint | Method | Output | Purpose |
|---|---|---|---|
| `/api/status` | GET | `application/json` | Current settings, sensor limits, and measured exposure/ISO/focus |
| `/api/still` | GET | `image/jpeg` | Full-resolution still JPEG, cropped to ROI |
| `/api/raw` | GET | `image/x-adobe-dng` | Full-sensor RAW frame as a DNG with calibration metadata |
| `/api/burst` | GET | `application/x-tar` | `n` frames with identical settings as a single tar archive |
| `/api/frame` | GET | `image/jpeg` | Low-latency preview JPEG frame |
| `/api/stream` | GET | `multipart/x-mixed-replace` | MJPEG video stream (honours thermal shedding) |
| `/api/set` | GET | `application/json` | Apply camera parameters and return updated state |
| `/api/af` | GET | `application/json` | Trigger one-shot autofocus cycle |
| `/api/focussweep` | GET | `application/x-tar` | `steps` stills walking lens from `from` to `to` in dioptres |
| `/api/focushunt` | GET | `application/json` | Search focus curve on-device and lock lens at sharpness peak |
| `/api/bracket` | GET | `application/x-tar` | `stops` stills doubling exposure from `base`, bundled in tar |
| `/api/walk` | GET | `application/x-tar` | Stills walking parameter `vary` across list `values` |
| `/api/script` | POST | `multipart/mixed` | Run atomic action tape; streams JSON step events and capture files |
| `/api/reset` | GET | `application/json` | Reset camera settings to factory defaults |
| `/api/orientation` | GET | `application/json` | Live angle to gravity vector and ambient light (lux) |
| `/api/cameras` | GET | `application/json` | Enumerate available sensors and hardware capability levels |
| `/api/shadingmap` | GET | `application/json` | Lens shading correction map (if provided by HAL) |
| `/api/help` | GET | `application/json` | Self-describing parameter documentation generated from Java source |
| `/api/nettest` | GET | `application/json` | Test local network connectivity back to caller |
| `/` | GET | `text/html` | Zero-dependency browser workbench panel |

### Camera state

These parameters persist until explicitly modified. They are valid on every capture and configuration endpoint (except `/api/stream`).

| Parameter | Meaning |
|---|---|
| `camera`, `cam` | The camera id. The rear camera is `0`. |
| `zoom`, `zoomby` | The software zoom. The value `1.0` is the full sensor. |
| `cx`, `cy` | The centre of the ROI, from 0 to 1 |
| `dx`, `dy` | A relative move, in fractions of the current ROI width |
| `af` | `off`, `auto`, `macro`, `continuous`, `video`, or `edof` |
| `focus`, `focusm` | The manual focus in dioptres, or in metres |
| `focusbox` | Where focus is judged, as `cx,cy,w,h` of the frame, or `off` to follow the crop |
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

These parameters describe how **one** picture is delivered. They apply only to the immediate request and are not stored in camera state.

| Parameter | Meaning |
|---|---|
| `w`, `h` | Change the size after the crop. One value keeps the aspect ratio. |
| `jpegq`, `quality` | The quality. The default is 92. |

When `w` and `h` persisted, they silently rescaled subsequent captures and permanently bypassed the zero-copy untouched JPEG path. In the current design, presentation parameters are strictly ephemeral. `rotate` remains part of camera state because it reflects physical mounting orientation.

### Router

These parameters configure execution, pacing, intervals, and sweep ranges.

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
| `coarse`, `fine` | The focus hunt: how many readings over the whole range, and how many around the best of them |
| `base`, `stops` | The exposure bracket: the shortest exposure, and how many frames of twice the one before |
| `vary`, `values` | The walk: which camera parameter to vary, and the list to vary it over |

### Errors

Any unrecognized parameter, illegal value, or out-of-range argument returns an **HTTP 400 Bad Request** with a structured JSON error body:

```json
{
  "ok": false,
  "error": "unknown parameter 'foo'"
}
```

When a request is refused because an atomic action tape holds the camera, DeskCam returns **HTTP 409 Conflict**. When an operation times out waiting for sensor convergence, it returns **HTTP 504 Gateway Timeout**.

Device-specific physical limits (`max_output_edge`, `max_output_pixels`, `burst_max`) are advertised in the `limits` block of `/api/status`. Requests exceeding the phone's memory capacity are refused before capture begins.

### JSON Sidecars and Telemetry Headers

Every capture writes an adjacent `NAME.json` sidecar on disk and embeds the identical telemetry inside EXIF `UserComment` (for JPEG) or `ImageDescription` (for DNG).

Example sidecar summary:
```json
{
  "camera": 0,
  "zoom": 4.0,
  "roi": {"cx": 0.32, "cy": 0.68, "w": 0.25, "h": 0.25},
  "settings": {
    "exposure_ns": 30270000,
    "iso": 163,
    "focus_diopters": 4.464,
    "focus_metres": 0.224,
    "torch_level": 25,
    "capture_path": "decoded_and_reencoded"
  },
  "measured": {
    "exposure_ns": 30268500,
    "iso": 163,
    "awb_gains": [1.99, 1.00, 1.00, 2.07]
  },
  "orientation": {
    "tilt_deg": 1.57,
    "gravity": {"x": 0.22, "y": 0.16, "z": 9.81},
    "ambient_lux": 85
  },
  "device": {
    "thermal": "none",
    "battery_celsius": 28.4,
    "battery_percent": 80
  }
}
```

HTTP responses also carry diagnostic headers:
* `X-DeskCam-ROI`: The normalized crop rectangle applied to the frame.
* `X-DeskCam-Fps`: The actual preview frame delivery rate.
* `X-DeskCam-Thermal`: The Android platform thermal state (`none`, `light`, `moderate`, `severe`, `critical`).
* `X-DeskCam-Shedding`: Explains when and why video stream rates are throttled due to thermal load.

---

## Explanation & Engineering Theory

### Why Software Region-of-Interest (ROI) Cropping?

The Google Pixel 6a sensor hardware reports `android.scaler.croppingType = CENTER_ONLY`. In hardware zoom, the Camera HAL forces the crop rectangle to remain centered at $(0.5, 0.5)$. The hardware scaler cannot pan.

DeskCam circumvents this hardware limitation. It always commands the camera sensor to deliver the full, uncropped $4032 \times 3024$ image array. It then performs Region-of-Interest cropping in software via `BitmapRegionDecoder`.

This design yields three critical advantages:
1. **True Panning**: You can center the view on any arbitrary coordinate $(c_x, c_y)$ across the entire sensor field.
2. **True Sensor Pixels**: Pixels are never interpolated, enlarged, or scaled by digital zoom algorithms. At $4\times$ zoom, you receive an authentic $1008 \times 756$ crop directly from the photosites.
3. **Synchronized Metering**: When you pan and zoom onto a component, DeskCam maps the 3A metering and autofocus regions directly to that sub-rectangle. The camera meters and focuses strictly on the component of interest.

### Dioptres vs. Millimetres in Optical Focus

DeskCam measures and steps manual lens focus in **dioptres** ($d = 1/\text{distance in metres}$), rather than in linear millimetres.

$$d = \frac{1}{f_{\text{metres}}}$$

In geometrical optics, **depth of field is approximately constant per dioptre**, regardless of distance. Conversely, depth of field in millimetres is wildly non-linear:
* Near the closest focus distance ($98\text{ mm}$), $1\text{ mm}$ corresponds to approximately $0.10\text{ dioptres}$.
* At a distance of $0.5\text{ metres}$, $1\text{ mm}$ corresponds to only $0.004\text{ dioptres}$.

If a focus sweep were stepped uniformly in millimetres, it would take hundreds of redundant, overlapping frames at close range while stepping completely over the subject at medium range. Stepping uniformly in dioptres produces evenly spaced, optimal depth slices across the entire range.

### Sensor Linearity and Radiometric Mode

Standard Android camera output is tailored for human aesthetic preference rather than scientific measurement. Tone curves compress highlights, edge sharpening introduces ringing artifacts, and dynamic noise reduction eliminates subtle spatial gradients.

Passing `measure=1` puts the Camera2 pipeline into a calibrated instrument state:
* Noise reduction, edge sharpening, hot pixel correction, and chromatic aberration correction are disabled.
* The tone curve is forced to a strictly linear response.
* Optical image stabilization (OIS) is locked, preventing physical lens movement on stationary mounts.
* White balance gains are locked.

Measured on 2026-09-10 with `deskcam analyse linearity`, from seven captures between 50 ms and 400 ms at ISO 56, zoom 4, white balance locked, on a static bench scene. The tool dropped the 400 ms frame for clipping:

| Quantity | Result |
|---|---|
| Value change per doubling, raw fit | **2.062x** (95% 2.041 to 2.083) |
| Power-law fit | R squared 1.000, n = 6 |
| Pedestal at zero exposure | **-2.39 DN** |
| Exponent with the pedestal removed | **0.999** (1.999x per doubling, 95% 1.992 to 2.006) |
| Same-against-same noise floor for the run | 1.21 DN, smallest step between captures 10.68 DN |

The pedestal is the interesting part. A constant negative offset bends the raw exponent upward, and this one accounts for the whole excess over 2.0. Read the result as **linear with a black-level offset of about two digits**, not as a sensor that responds better than linearly. Every figure in the table comes from one run of the tool; nothing here is corrected by hand. Reproduce it:

```sh
deskcam set zoom=4 awblock=1 measure=1 iso=56
deskcam aatest -o lin/ exposure=200ms          # records the noise floor into lin/
for ms in 50 71 100 141 200 283 400; do
    deskcam snap -o lin/e$ms.jpg exposure=${ms}ms settle=600
done
deskcam analyse linearity lin/ --region 0.5,0.68,0.30,0.12
```

Your numbers will differ. The pedestal and the floor belong to your scene and your camera, and the region is the patch of the frame that was neither dark nor clipped in this one.

### Android 17 Local Network Permission Isolation

Android 17 introduces a strict architectural division between `INTERNET` and `ACCESS_LOCAL_NETWORK`:

* An application can hold the traditional `android.permission.INTERNET` permission and successfully reach external public web servers.
* Simultaneously, the Android operating system drops all inbound and outbound packets to private local network addresses ($192.168.x.x$, $10.x.x.x$).

In this failure mode, the HTTP server appears healthy in `logcat` and `ss` confirms it is listening on port 8080. However, connection attempts from your workstation time out with no response.

DeskCam explicitly declares and requests `android.permission.ACCESS_LOCAL_NETWORK`. If you install the APK manually without `-g`, you must grant this permission in the phone's App Settings.

Connecting over USB via `deskcam usb` bypasses this mechanism entirely by tunneling through `adb forward` onto the phone's loopback interface (`127.0.0.1:8080`).

### Thermal Rate Shedding and Battery Care

A smartphone bolted to an inspection arm running a camera sensor and holding an active wake lock generates substantial heat.

DeskCam incorporates an automated load shedding architecture:

1. **Stream Throttling, Capture Priority**: Video streaming (`/api/stream`) is a continuous workload. When platform thermals rise, stream frame rates are automatically throttled:
   * `none` / `light`: Full requested rate (up to 30 fps).
   * `moderate`: 50% rate.
   * `severe`: 25% rate.
   * `critical`: 12.5% rate.
   Discrete captures (`snap`, `burst`, `raw`) are **never slowed down or dropped**. A measurement capture takes the exact exposure requested.
2. **Automatic Preview Idling**: If no client requests a frame or connects to a stream for 20 seconds, DeskCam halts repeating preview requests to allow the image sensor and processor to cool. The next incoming capture wakes the sensor, allows the exposure loop to converge, and captures seamlessly.
3. **Battery Charge Thresholding**: Running continuously at 100% battery while plugged into USB degrades lithium-ion cells and generates heat. GrapheneOS and modern Android builds support stopping charging at 80%. DeskCam's `/api/status` distinguishes between `plugged_in: true` and `charging: false` to avoid false alerts about broken cables.

### Console Security, QR Pairing, and Access Tokens

**There is no HTTPS.** The service is for a trusted LAN. A self-signed certificate would make `-k` necessary on each request, for no real gain. If you need encryption, or access from outside the LAN, put the phone on a WireGuard or Tailscale network and keep the server on plain HTTP behind it.

By default the camera is open: any client on the network can control it and take captures. The token is for the days that is not acceptable.

For shared or untrusted networks, DeskCam supports token authentication:
1. Run `deskcam token new` on the workstation to generate a secure random token stored at `~/.config/deskcam/token` (mode 0600).
2. Start the workstation console via `deskcam open`. It generates a pairing QR code containing `deskcam://pair?cb=...`.
3. Scan the QR code with the phone camera to pair the token with the Android service in real time, with no manual keyboard entry.
4. Subsequent API calls require `?token=...` or an `Authorization: Bearer <token>` header.

---

## Interactive Visual Explainer

This repository includes an interactive, browser-based explainer site located in [`explainer/`](explainer/).

```sh
# Open the explainer in your default browser:
xdg-open explainer/index.html   # Linux
open explainer/index.html       # macOS
```

The explainer includes:
* **Interactive Sensor ROI Simulator**: Visually pan and zoom across a 12-megapixel sensor plane and inspect real-time software crop mechanics.
* **Dioptres vs. Distance Curve**: Interactive optical depth-of-field visualizer showing why linear dioptric stepping prevents focus gaps.
* **PWM Rolling Shutter Banding Canvas**: Simulate display flicker artifacts and verify how integer period bracketing restores clean captures.
* **Live Action Tape Runner**: Interactive step-by-step preview of multi-verb inspection sequences.

---

## Quality Gates and Verification

DeskCam maintains strict quality gates across both the Android backend and workstation frontend:

### Workstation Test Suite

Runs ruff linting, ruff formatting check, mypy type verification, bandit security analysis, pytest unit tests (including parameter contract integrity checks), and Go unit tests:

```sh
./frontend/check.sh
```

### Pure Java Backend Unit Tests

The backend includes pure Java unit tests (`backend/test/dev/deskcam/Tests.java`) covering coordinate rotation geometry, exposure string parsing, tar archive packing, sharpness calculations, and thermal ladders. These execute on the host JVM during every build before packaging the APK:

```sh
./backend/build.sh
```

### End-to-End Surface Parity Check

To verify that an internal refactoring introduces zero behavioral drift against a live camera:

```sh
# Record all endpoint schemas and status codes before a change
frontend/surface.py record /tmp/before

# Rebuild and install
./backend/build.sh && adb install -r -g backend/build/deskcam.apk

# Record after and compare
frontend/surface.py record /tmp/after
frontend/surface.py compare /tmp/before /tmp/after
```

---

## License

DeskCam is open-source software released under the [MIT License](LICENSE).
