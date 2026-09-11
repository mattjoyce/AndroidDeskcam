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
* **One Go Dependency**: The workstation CLI compiles to a single static binary. Its only module outside the standard library is `rsc.io/qr` v0.2.0, for the pairing QR code, pinned in `go.sum`.
* **Zero Cloud Calls or Telemetry**: The Android app talks only to your LAN or to an `adb forward` USB loopback, and the CLI talks only to the phone. The one internet address anywhere is the release download that `deskcam serve` puts in its install code, and the phone fetches it only when you scan that code.
* **One HTTP Seam, Contract-Tested**: The HTTP interface is the only seam between the phone and the workstation. A Python test suite (`frontend/test_contract.py`) holds the parameter tables, endpoint lists, CLI reference, and enum values in these documents equal to the code they describe. It reads the source as text; `frontend/surface.py` is the check against a running phone.

**There is no HTTPS, and there is no authentication unless you set a token.** The phone serves plain HTTP on port 8080 to anyone on the same network and to every app on the phone, and that open state is the default (`Access.java`). This is a bench tool for a trusted LAN. If the network is shared, set a token (see [Require an Access Token](#require-an-access-token)). If you need encryption, or reach from outside the LAN, put the phone on a WireGuard or Tailscale network and keep the server on plain HTTP behind it. A self-signed certificate would only add `-k` to every request.

Releases are signed with the DeskCam release key and list a SHA-256 checksum for every file. The release certificate's SHA-256 fingerprint is:

```
638810f09d75a8f2cdd9c85c9139bae01f67f7f8b6095c6d06de5c2b1beca6a9
```

That is the form `apksigner` prints. AppVerifier and `keytool` show the same digest as `63:88:10:F0:9D:75:A8:F2:CD:D9:C8:5C:91:39:BA:E0:1F:67:F7:F8:B6:09:5C:6D:06:DE:5C:2B:1B:EC:A6:A9`.

Check an APK before you install it with `apksigner verify --print-certs deskcam.apk`, or on GrapheneOS with AppVerifier. There is no reproducible build yet, so a release is the maintainer's statement that it was built from the tagged source; building it yourself is the only independent check.

A home build signs with a key that `backend/build.sh` generates on first run: `backend/deskcam.keystore`, gitignored, password `deskcam`. Keep it: `git clean -x` deletes it, and an APK signed with a different key will not install over the old one until you uninstall the app, which clears its settings. For the same reason a release and a home build do not install over each other.

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
   - [Fix a Phone That Listens but Cannot Be Reached (Android 17)](#fix-a-phone-that-listens-but-cannot-be-reached-android-17)
   - [Require an Access Token](#require-an-access-token)
3. [Reference (The Contract)](#reference-the-contract)
   - [CLI Reference](#cli-reference)
   - [HTTP REST API Endpoints](#http-rest-api-endpoints)
   - [Camera state](#camera-state)
   - [Presentation](#presentation)
   - [Router](#router)
   - [Errors](#errors)
   - [JSON Sidecars and Telemetry Headers](#json-sidecars-and-telemetry-headers)
4. [Explanation & Engineering Theory](#explanation--engineering-theory)
   - [Why the crop is done in software](#why-the-crop-is-done-in-software)
   - [Limits, and the camera idling](#limits-and-the-camera-idling)
   - [Why focus is stepped in dioptres](#why-focus-is-stepped-in-dioptres)
   - [Focus by number, without sending a picture](#focus-by-number-without-sending-a-picture)
   - [Hunting the focus](#hunting-the-focus)
   - [Running a sequence as one operation](#running-a-sequence-as-one-operation)
   - [Sensor linearity and measurement mode](#sensor-linearity-and-measurement-mode)
   - [Measuring, and knowing when not to](#measuring-and-knowing-when-not-to)
   - [What the tilt reading is](#what-the-tilt-reading-is)
   - [Heat and battery](#heat-and-battery)
5. [Interactive Visual Explainer](#interactive-visual-explainer)
6. [Versions and Releases](#versions-and-releases)
7. [Quality Gates and Verification](#quality-gates-and-verification)
8. [License](#license)

---

## Quick Start (Tutorial)

There are three ways in. Pick one; they end in the same place.

| Route | You need | The app comes from |
|---|---|---|
| **1. Release** | a phone and a computer | the latest GitHub release, by QR code |
| **2. Build it, install by QR** | Go, JDK 17, `zip`, the Android SDK | your own build, by QR code from your workstation |
| **3. Build it, install with adb** | route 2's tools, USB debugging | your own build, over USB |

Every route needs an Android 13 or newer phone with a Camera2 `LEVEL_FULL` camera; it is built for a Pixel 6a. The workstation is Linux or macOS.

### Route 1: the release

1. Download the CLI for your workstation from the [latest release](https://github.com/mattjoyce/AndroidDeskcam/releases/latest) and put it on your path. The files are `deskcam-linux-amd64`, `deskcam-linux-arm64`, `deskcam-darwin-amd64` and `deskcam-darwin-arm64`:
   ```sh
   mkdir -p ~/.local/bin
   curl -L -o ~/.local/bin/deskcam https://github.com/mattjoyce/AndroidDeskcam/releases/latest/download/deskcam-linux-amd64
   chmod +x ~/.local/bin/deskcam
   ```
   `SHA256SUMS` on the same page lists every file's checksum.
2. Start the console and open it in a browser on the workstation:
   ```sh
   deskcam serve        # then open http://127.0.0.1:9000 and click **Pair**
   ```
   It shows two codes. With no build of your own, the install code points at the latest release.
3. Scan the install code with the phone's camera. The APK downloads from github.com over HTTPS. Allow the browser to install unknown apps when Android asks.
4. Open DeskCam, accept its permission prompts, and tap **Start**.
5. Scan the pairing code. The phone asks before it pairs; tap **Pair**. The workstation's `deskcam` now points at the phone.

### Route 2: build it, install by QR

1. Install Go 1.22 or newer, JDK 17 or newer, `zip`, and the Android SDK [command-line tools](https://developer.android.com/studio#command-line-tools-only). With `sdkmanager`, install the platform and build-tools versions named at the top of `backend/build.sh`. The script looks in `~/Android/Sdk` unless you set `ANDROID_HOME`, `PLATFORM` and `BT_VER`.
2. Build both halves:
   ```sh
   git clone https://github.com/mattjoyce/AndroidDeskcam.git && cd AndroidDeskcam
   ./backend/build.sh                                   # the APK, in about 5 seconds, no Gradle
   cd frontend/go && go build -o deskcam . && cd ../..
   mkdir -p ~/.local/bin && ln -sf "$PWD/frontend/go/deskcam" ~/.local/bin/deskcam
   ```
   Neither step needs a phone. `build.sh` runs the backend unit tests on the workstation JVM before it packages the APK, and `cd frontend/go && go test ./...` runs the CLI tests against a stub server. The first `go build` downloads the one Go dependency.
3. Run `deskcam serve`. It finds `backend/build/deskcam.apk` and hands it out itself, so the install code points at your workstation. Then follow route 1 from step 3.

The app travels over plain HTTP on your LAN, so a phone installing it for the first time trusts whatever arrives, which is the trusted-network stance of the whole tool. Some browsers refuse plain HTTP addresses; GrapheneOS's Vanadium did during development. If yours does, use route 3, or `deskcam serve --apk release` to hand out the release instead.

### Route 3: build it, install with adb

1. Build as in route 2, steps 1 and 2.
2. Turn on USB debugging, plug the phone in, then install and start the app. The `-g` flag grants every runtime permission the manifest declares at install time, so no dialog appears on the phone; for this app that is camera, local network, and notifications:
   ```sh
   adb install -r -g backend/build/deskcam.apk
   deskcam start
   ```
3. Connect over USB with `deskcam usb`, which forwards the phone's port to your workstation. `deskcam wifi` switches to the phone's Wi-Fi address, which it also reads through adb. Or pair it as in route 1, step 5.

### Your first capture

```sh
deskcam show         # one line of live state: proves the connection
deskcam snap
```

If the phone shows the wrong address, for instance its VPN address while you are on its Wi-Fi, tap the network chip beside the address in the app and choose. After a reboot, tap the app once: Android does not let a camera app start itself.

The command saves the full-resolution still and prints the path it wrote. Without `-o` the file goes in `DESKCAM_SHOTS`, or the current directory when that is unset:
```
/home/you/bench/deskcam-20260911-061500.jpg
```

Beside the image, DeskCam creates `deskcam-20260911-061500.json`: the settings that were asked for, what the sensor measured for that frame (exposure, ISO, focus, white balance gains), what the image pipeline applied, and the phone's orientation. The same record is in the JPEG's EXIF `UserComment`.

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
8. **R8**: Setting a value is idempotent: `zoom=4` sent twice leaves the camera where sending it once did. The relative moves `dx`, `dy`, `zoomby` and `deskcam pan` are the exception, because each one moves from where the camera is.

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

To focus on a specific IC without changing the overall image framing, use `focusbox`. Fix the exposure first: sharpness is a comparison, and with automatic exposure the hunt climbs the exposure loop instead of the lens.

```sh
deskcam set exposure=1/33 iso=200 focusbox=0.35,0.35,0.15,0.15
deskcam focus hunt
```

The hunt exits non-zero, and puts the focus back, when the box holds nothing to focus on, such as a glossy black surface.

### Capture Flicker-Free Displays (PWM Synchronization)

LED and OLED displays cycle power at high pulse-width modulation (PWM) frequencies (e.g., 60 Hz, 120 Hz, 240 Hz, or 480 Hz). If the camera exposure is not an exact integer multiple of the PWM period, rolling shutter capture produces dark horizontal bands across the screen.

1. **Calculate the base period**: For a 60 Hz panel, the period is 1/60 s, about 16.67 ms. For a 240 Hz panel, it is 1/240 s, about 4.17 ms.
2. **Lock exposure, ISO, and white balance**:

```sh
# Lock exposure to exact panel PWM multiples and lock white balance
deskcam set exposure=1/60 iso=200 awb=daylight awblock=on
deskcam snap -o display_clean.jpg
```

To capture an exposure bracket across multiple stops without introducing PWM phase errors:

```sh
# Step in exact powers-of-two multiples of the 240 Hz PWM period:
deskcam bracket base=1/240 stops=4 iso=56 measure=1
```

Take the bracket in measurement mode if you will merge it. `deskcam analyse hdr` refuses frames taken through a tone map, because `value / exposure` is only radiance when the response is linear.

DeskCam verifies the sensor's physical timing and reports the exact period fractions in the response.

### Focus Stacking & Macro Depth of Field

At close macro distances (10 cm to 30 cm), optical depth of field is fractions of a millimetre.

To capture a focus stack across a circuit board:

```sh
# Sweep the lens over 7 evenly spaced dioptric steps from 3.0 to 6.0 dioptres:
deskcam focussweep -o stack/ from=3 to=6 steps=7
```

DeskCam captures seven full-resolution stills and writes each one, with its JSON sidecar, into `stack/`. Without `-o` the directory is named `deskcam-sweep-DATE-TIME` in your shots directory.

To blend the sharp slices into a single deep-focus composite on the workstation:

```sh
# Optional: install workstation analysis dependencies
pip install -e '.[analysis]'

# Blend the stack with focus-breathing correction
deskcam analyse stack stack/
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
# 47.4 mm (95% 47.3 to 47.5)
```

### Execute Atomic Action Tapes (Scripts)

Nothing stops the browser panel or a second agent from changing the camera between your `SET` and your `SNAP`. A tape runs a sequence as one request and holds the camera while it runs, so any request that would change the camera gets HTTP 409. Write the steps to a file, one verb per line:

```
# inspect the part, lit and unlit
SET zoom=2 cx=0.5 cy=0.5 exposure=1/33 iso=200 awbgains=neutral
FOCUSHUNT
SET torch=25
WAIT 500
SNAP
SET torch=0
SNAP
```

```sh
deskcam script run inspect.dcl
```

Each capture is written as it arrives, with its sidecar. A failed step ends the tape and puts the camera back where the tape found it. The verbs, the failure rules, and why a tape has no branching are in [Running a sequence as one operation](#running-a-sequence-as-one-operation).

### Linear Radiometric Measurement & RAW DNG

Consumer smartphone camera pipelines apply aggressive non-linear transformations: tone-mapping curves, dynamic noise reduction, edge sharpening, and vignetting compensation. These corrupt physical light measurements.

For linear radiometry, where doubling the exposure doubles the pixel value (measured in [Sensor linearity and measurement mode](#sensor-linearity-and-measurement-mode)):

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

### Fix a Phone That Listens but Cannot Be Reached (Android 17)

Android 17 introduces a strict architectural division between `INTERNET` and `ACCESS_LOCAL_NETWORK`:

* An application can hold the traditional `android.permission.INTERNET` permission and successfully reach external public web servers.
* Simultaneously, the Android operating system drops all inbound and outbound packets to private local network addresses (192.168.x.x, 10.x.x.x).

In this failure mode, the HTTP server appears healthy in `logcat` and `ss` confirms it is listening on port 8080. However, connection attempts from your workstation time out with no response.

DeskCam declares `android.permission.ACCESS_LOCAL_NETWORK` and asks for it the first time it opens. If you declined, grant it in the app's settings. `adb install -g` grants it at install time.

Connecting over USB via `deskcam usb` bypasses this mechanism entirely by tunneling through `adb forward` onto the phone's loopback interface (`127.0.0.1:8080`).

### Require an Access Token

**There is no HTTPS.** A token decides who may use the camera. It encrypts nothing, and it crosses the network in the clear with every request. For encryption, see [Trust and Architecture at a Glance](#trust-and-architecture-at-a-glance).

By default the camera is open: any client on the network can control it and take captures. The token is for the days that is not acceptable:
1. Run `deskcam token new` on the workstation to generate a secure random token stored at `~/.config/deskcam/token` (mode 0600).
2. Run `deskcam serve` and open `http://127.0.0.1:9000` on the workstation. The page shows a pairing QR code holding `deskcam://pair?cb=...&token=...`.
3. Scan the QR code with the phone camera to pair the token with the Android service in real time, with no manual keyboard entry.
4. Subsequent API calls require `?token=...` or an `Authorization: Bearer <token>` header.

**Accept only a code your own console shows.** A pairing link can set or clear the key and makes the phone report its address, so the phone shows what a link will do and waits for **Pair**. It refuses outright a link whose callback is not a console's pairing route on a private address.

`deskcam serve` listens on port 9000 on every interface, because the phone has to reach it to fetch the app and to finish pairing. Only those two routes answer from off the machine. The page, the codes, the capture roll and the key answer on `127.0.0.1` alone.

---

## Reference (The Contract)

### CLI Reference

This is the text `deskcam help` prints, copied here verbatim. `frontend/test_contract.py` fails when the two differ, so what you read is what the binary you built says.

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
  deskcam analyse scale FILE           the measurement without recording it
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
  deskcam token new|show|clear         make, show or remove the access key

  deskcam use URL                      remember a target, e.g. http://192.168.86.120:8080
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

Environment: DESKCAM_URL, DESKCAM_TOKEN, DESKCAM_TIMEOUT, DESKCAM_SHOTS, DESKCAM_SERIAL
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

Presentation parameters apply to the one request that names them and are then forgotten, so a resize never carries into the next capture or pushes it off the untouched JPEG path. `rotate` is camera state, not presentation, because it describes how the phone is mounted.

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

This sidecar was written by `deskcam snap -o board.jpg zoom=4 cx=0.5 cy=0.5` on 2026-09-11, with the camera on automatic exposure and focus. Every key is as the tool wrote it:
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

`settings` is what was asked for; `null` means the automatic mode was in charge. `measured` is what the sensor reported for that frame, taken from the capture result and not from a later status call (`from` says so). `focus_metres_approx` is one divided by the lens position in dioptres; the camera reports its focus calibration as `APPROXIMATE`, so it is not a measured distance and the word stays in the key. `pipeline` is what the HAL applied, and with `measure=1` every entry there reads `off` and the tone map is linear. `orientation` carries `"available": false` and nothing else when the phone has no gravity sensor. A `scale` block appears when `deskcam scale` has recorded pixels per millimetre for this framing. There is no thermal or battery block in a sidecar; that state is in `/api/status` and on the stream headers below.

HTTP responses also carry headers, and which ones depends on the endpoint (`HttpServer.java` is the source of truth):
* `X-DeskCam-Provenance`: on `/api/still`, the frame's own record as one line of JSON, the same content as the sidecar. Omitted if it would exceed 7000 bytes.
* `X-DeskCam-ROI`: on `/api/raw`, the framing that was asked for. A DNG carries the whole sensor, so the crop is reported rather than applied.
* `X-DeskCam-Frames` and `X-DeskCam-Frames-Requested`: on `/api/burst`, how many frames came back against how many were asked for. A short burst answers 206 rather than 200. `/api/focussweep`, `/api/bracket` and `/api/walk` send `X-DeskCam-Frames` alone.
* `X-DeskCam-Millis`: on `/api/burst`, `/api/focussweep`, `/api/bracket` and `/api/walk`, wall-clock time for the whole capture.
* `X-DeskCam-Fps`: on `/api/burst`, the rate the frames were actually taken at. On each part of `/api/stream`, the rate this part was sent at after shedding.
* `X-DeskCam-Thermal`: on each part of `/api/stream`, the platform thermal state as one word: `none`, `light`, `moderate`, `severe`, `critical`, `emergency`, `shutdown`. It is `unknown` before the platform has reported, and `level N` for a value this build does not know. Parse for all nine.
* `X-DeskCam-Shedding`: on a stream part, and only once the rate has been cut, one sentence saying what the level means and what to do. `emergency` and `shutdown` both say to stop the session.

---

## Explanation & Engineering Theory

### Why the crop is done in software

The Google Pixel 6a sensor hardware reports `android.scaler.croppingType = CENTER_ONLY`. In hardware zoom, the Camera HAL forces the crop rectangle to remain centered at (0.5, 0.5). The hardware scaler cannot pan.

DeskCam circumvents this hardware limitation. It always commands the camera sensor to deliver the full, uncropped 4032 x 3024 image array. It then performs Region-of-Interest cropping in software via `BitmapRegionDecoder`.

This design yields three critical advantages:
1. **True Panning**: You can center the view on any arbitrary coordinate (cx, cy) across the entire sensor field.
2. **True Sensor Pixels**: Pixels are never interpolated, enlarged, or scaled by digital zoom algorithms. At 4x zoom, you receive an authentic 1008 x 756 crop directly from the photosites.
3. **Synchronized Metering**: When you pan and zoom onto a component, DeskCam maps the 3A metering and autofocus regions directly to that sub-rectangle. The camera meters and focuses strictly on the component of interest. When what should be sharp is not the whole crop, `focusbox` moves the autofocus region and the sharpness window to a rectangle of their own and leaves the metering on the crop (D17 in [docs/DECISIONS.md](docs/DECISIONS.md)).

### Limits, and the camera idling

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

**The camera stops reading the sensor when nobody is asking.** After 20 seconds with no
stream client and nothing requesting a frame, the repeating preview request is stopped.
The next request that needs a frame starts it again and waits for the exposure loop to
settle before answering, so the first capture after a quiet period is not quietly worse
than one taken during a busy one. `/api/status` carries a `preview` block saying whether
it is idle, for how long, and what the last wake cost.

This sentence used to read "the app converts a preview frame only when a client asks for
one; an idle service costs almost nothing." The first half is decision D7 and is true: a preview frame is encoded only while someone is watching. The
second half was not: the conversion stopped, and the sensor, the ISP and the HAL carried on
at 29 frames a second for the life of the service. A bench phone left running overnight was
found at the platform's `severe` thermal level for that reason.

Measured on a Pixel 6a, three runs each:

| | |
|---|---|
| Frames while idle | **0 in 10 s**, against 29 a second awake |
| Wake, exposure fixed | 305 to 348 ms |
| Wake, exposure automatic | 377 to 803 ms |
| A still taken against a sleeping camera | 764 to 822 ms, about 320 ms of it the wake |

The exposure of the first frame after a wake was **identical** to one taken two seconds
later in every automatic run, and the ISO agreed to within 5 of 200. The cost of idling is
a slower first capture, never a worse one.

A stream client stops it idling, which is why a browser tab left open on the panel used to
hold the camera awake all night. Both panels now stop their stream while their tab is
hidden.

### Why focus is stepped in dioptres

DeskCam measures and steps manual lens focus in **dioptres** (d = 1/distance in metres), rather than in linear millimetres.

In geometrical optics, **depth of field is approximately constant per dioptre**, regardless of distance. Conversely, depth of field in millimetres is wildly non-linear:
* Near the closest focus distance (98 mm), 1 mm corresponds to approximately 0.10 dioptres.
* At a distance of 0.5 metres, 1 mm corresponds to only 0.004 dioptres.

If a focus sweep were stepped uniformly in millimetres, it would take hundreds of redundant, overlapping frames at close range while stepping completely over the subject at medium range. Stepping uniformly in dioptres produces evenly spaced, optimal depth slices across the entire range.

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
is not showing video (decision D7 in [docs/DECISIONS.md](docs/DECISIONS.md)).

It costs about **9 ms** on a Pixel 6a and the cost is reported with the value. The sample
count is capped for that: rows are skipped, never columns and never the kernel's
neighbours, because a kernel over subsampled pixels measures a blurrier image than the one
in front of the camera and would put the peak in the wrong place.

### Hunting the focus

That loop is fourteen round trips: a set, a settle, a fresh frame and a status for every
reading. `/api/focushunt` is the same loop on the phone, where each step costs none of
that, and it is the one loop in this project that has to live there, because every step
depends on the frame the last step produced.

```sh
deskcam set measure=on exposure=1/33 iso=200
deskcam focus hunt
```

```
   0.000 d       3.4  ##
   1.276 d       4.3  ###
   2.551 d      11.3  #######
   3.827 d      62.5  #######################################
   5.102 d      28.9  ##################
   6.378 d       5.6  ###
   7.653 d       3.4  ##
   8.929 d       3.1  ##
  10.204 d       2.7  ##
  the fine pass, around the best of the coarse one
   2.551 d      11.5  #######
   3.189 d      29.8  ##################
   3.827 d      64.4  ######################################## <-
   4.464 d      61.7  ######################################
   5.102 d      29.8  ###################
chosen 3.827 d (about 261 mm), sharpness 64.38, contrast 0.959, 14 readings in 4.0 s
```

A coarse pass over the range, then a fine pass around the best of it. `coarse=9` cannot
step over a peak that is several dioptres wide at half height, and `fine=5` inside one
coarse step lands within about a sixth of a dioptre of the best the coarse pass found.
Fourteen readings over the whole ten-dioptre range of this lens take about four seconds,
which the answer reports rather than asks you to remember. The curve comes back with the
answer, and the CLI draws it, because both of the ways this can fail are shapes.

**The position repeats; the peak value does not.** Twelve hunts of one subject on this
bench, started from both ends of the lens travel: eleven chose 3.827 d and one chose the
next fine step at 4.464 d, whose sharpness was within a few percent of it. The peak value
across those same twelve runs ran from 34.9 to 64.5, nearly two to one. That is the point
about the metric being a comparison, made in the strongest way available: two hunts of the
same subject agree about where the lens goes and disagree about the number, so use
`diopters` and never compare `sharpness` between hunts.

**It is allowed to refuse**, which is the difference between this and `/api/af`. A flat
curve means nothing in the region of interest came into focus anywhere in the range:

```
$ deskcam focus hunt exposure=1/4000 iso=56
   0.000 d       0.0
   ... every reading the same ...
deskcam: no focus chosen (flat): the sharpness moved by 0% across 0.00 to 10.20 dioptres,
and a peak moves it by far more.
```

A peak sitting on an end of the range means the search stopped while the curve was still
climbing, so the real peak is outside it:

```
$ deskcam focus hunt from=0 to=3
   ... climbing all the way to the last reading ...
       3 d      20.3  ######################################## <-
deskcam: no focus chosen (peak_at_edge): the sharpest reading, 3.00 dioptres, is the near
end of the range that was searched ... Widen the range and hunt again, e.g. to=6.00
```

Both answer `ok: false` with the reason and the curve, put the focus back where they found
it, and exit non-zero. `af_state: focused` on a low-contrast board at 98 mm is not always
an answer; this says so.

When it does choose, the lens stays there. A hunt is a decision and not an excursion,
which is the opposite of `/api/focussweep`, and it is the only walk in this project that
behaves that way.

**Fix the exposure first.** Sharpness is a comparison, so everything except the focus has
to be held still for the duration. With `ae=auto` the exposure moves between readings and
the metric moves with it, and the hunt climbs the auto-exposure loop rather than the lens.
It warns when it sees `ae=auto`, but the fix is `exposure=` and `iso=`.

### Running a sequence as one operation

`POST /api/script` takes a tape of verbs, one per line, and runs the whole thing as one
request.

```sh
cat inspect.dcl
```

```
# inspect the part, lit and unlit
SET zoom=2 cx=0.5 cy=0.5 exposure=1/33 iso=200 awbgains=neutral
FOCUSHUNT
SET torch=25
WAIT 500
SNAP
SET torch=0
SNAP
```

```sh
deskcam script run inspect.dcl
```

```
7 steps: SET FOCUSHUNT SET WAIT SNAP SET SNAP
   0 SET        zoom 2x  at 0.5,0.5  af continuous  ae manual  30.02ms (1/33)  iso 163
   1 FOCUSHUNT  chose 4.464 d, sharpness 1107.46, contrast 0.992, 14 readings
   2 SET        zoom 2x  at 0.5,0.5  af off  focus 0.224m  ae manual  30.27ms  torch 25
   3 WAIT       500 ms
   4 SNAP       004-still.jpg
   5 SET        zoom 2x  at 0.5,0.5  af off  focus 0.224m  ae manual  30.27ms (1/33)
   6 SNAP       006-still.jpg
done: 7 of 7 steps in 7.2 s
```

The verbs are `SNAP`, `FRAME`, `RAW`, `BURST`, `BRACKET`, `FOCUSSWEEP`, `WALK`,
`FOCUSHUNT`, `SET`, `RESET`, `AF`, `STATUS` and `WAIT`. Each one is an endpoint that
already exists and takes the same `k=v` words that endpoint takes, so a line of a tape and
a URL cannot come to mean different things. `#` starts a comment line. `WAIT` is the only
verb that is not an endpoint, and it is for waiting on something that is not a capture,
such as an LED reaching a steady temperature: every capture verb has `settle` for its own
waiting.

**The reason for this is atomicity, not speed.** A round trip on this LAN is about 100 ms
against a settle and a capture of several hundred, so a seven step sweep run from the shell
loses well under a second to the network. What it does lose is the guarantee that nothing
moved. The browser panel polls the state every two seconds and can change the camera, and
so can a second agent, so **every multi-step sequence driven from the shell is racy between
one step and the next.** While a tape runs it holds the camera, and a second script or any
request that would change the camera is refused:

```
$ deskcam set zoom=3
deskcam: HTTP 409
  a script is running and holds the camera. It will finish or fail on its own; this
  request would have changed the camera underneath it. Read /api/status while you wait.
```

Reading is still allowed, because watching a tape run does not interfere with it.

**The pixels come back inside the same request.** The answer is one `multipart/mixed`
stream: a JSON event per step, and each capture's file as the part after its own event.
The phone stores nothing, and an events-only
stream would have needed a working directory on the phone, a cleanup policy, a listing
endpoint and a download endpoint before the first script ran. `deskcam script run` writes
each part as it arrives, with that step's own record beside it, exactly as `deskcam walk`
unpacks an archive.

**A tape is not a language and will not become one.** No branching, no variables, no
labels, no arithmetic. You are the intelligence; the tape is the execution record. That is
what keeps it from being the thing worth refusing: a language with ranges and steps would
make a wrong step rule as easy to write as a right one, and `BRACKET base=1/240 stops=4`
leaves that knowledge in `/api/bracket`, where the reasoning about PWM periods lives.

**A whole tape is read before any of it runs**, so a typo costs nothing:

```
$ deskcam script run inspect.dcl
deskcam: HTTP 400
  line 2: 'SNPA' is not a verb. The verbs are SNAP, FRAME, RAW, BURST, BRACKET,
  FOCUSSWEEP, WALK, FOCUSHUNT, SET, RESET, AF, STATUS, WAIT.
```

**A step that fails ends the tape and the camera goes back.** `SET torch=45`, a capture
that fails, and the `SET torch=0` that never runs would otherwise leave the LED on until
somebody noticed. The last event says what failed and what the camera was put back to, and
`deskcam script run` exits non-zero. A tape that finishes is left where it put the camera,
because a `SET` in a finished tape is a change you asked for.

A verb whose own answer says `ok: false` is a failed step. Today that is only `FOCUSHUNT`
finding no peak, and it matters: carrying on to the next `SNAP` would take it out of focus
and report it as a success.

### Sensor linearity and measurement mode

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

### Measuring, and knowing when not to

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

That figure is recorded next to your captures as `deskcam-noisefloor.json`. Today only
`linearity` reads it, and it **refuses** a series whose steps sit inside it. The other tools
do not check it yet, so hold their differences against the floor yourself.

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

#### Scale is not a property of this camera

`deskcam scale` measures pixels per millimetre from a regular reference in the frame, a
steel rule or graph paper. **It changes every time the stand moves**, so it is never quoted
as a camera specification. Measure it in the picture you care about, as in
[Calibrate Physical Scale & Measure Distances](#calibrate-physical-scale--measure-distances).

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

#### Making one image out of many

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

### What the tilt reading is

The record beside every capture holds the tilt of the camera, from the gravity sensor:

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

### Heat and battery

A phone bolted to a stand, holding a camera and a wake lock for hours with nobody looking
at it, gets hot. `/api/status` carries a `device` block:

```json
"device": {
  "thermal": "severe",
  "thermal_level": 3,
  "throttling": true,
  "stream_slowdown": 4,
  "battery_percent": 100,
  "battery_celsius": 36.3,
  "plugged_in": true,
  "charging": true,
  "battery_status": "charging",
  "power_source": "ac"
}
```

**`plugged_in` and `charging` are two different facts.** A phone told to stop at 80 percent,
which is a sensible way to run one that lives on a stand, has the cable in and is not
charging: `plugged_in` stays true, `charging` goes false and `battery_status` says
`not_charging`. The block says so in a note, because the alternative is somebody going to
look for a bad cable.

**`thermal` and `battery_celsius` are different quantities and it matters.** `thermal` is
the platform's own level, the same one it throttles by, and it is what a stream reacts to.
`battery_celsius` is a real temperature from the only thermometer an ordinary app may
read, and it is neither the sensor nor the processor.

This bench phone after an afternoon of bursts, walks and streams, from
`dumpsys thermalservice` beside what the app reports:

| | reading |
|---|---|
| Platform thermal status | **3, severe** |
| Battery | 29.5 °C |
| Skin | 34.1 and 35.0 °C |
| Display | 29.6 °C |
| TPU | **53.0 °C** |

Every thermometer an app can reach says the phone is comfortable. The platform is
throttling severely because of a part none of them measures. That is the whole reason
`thermal` is the number this reacts to and `battery_celsius` is only there for context.

**A stream gives way; a capture never does.** A stream is the continuous load, so its rate
is cut: half at `moderate`, a quarter at `severe`, an eighth at `critical`, a twentieth at
`emergency` and `shutdown`, and not at all at `none` or `light`. Each part of the stream
says so, so a client that sees its rate fall can tell heat from a network fault:

```
Content-Type: image/jpeg
X-DeskCam-Fps: 2.50
X-DeskCam-Thermal: severe
X-DeskCam-Shedding: throttling severely, and the platform says the experience is largely
  affected. The stream rate is a quarter of what was asked for. Captures are not slowed.
```

Six frames at a requested 10 fps took 2.46 s rather than 0.6 s on a severe phone, which is
the quarter rate the table promises. Nothing is slowed at `light`, which the platform
defines as throttling nobody can feel. The stream is never stopped, even at `shutdown`,
because it is the channel carrying the reason.

`deskcam show` ends with `HOT severe` once the platform is acting, and says nothing while
the phone is merely warm. **Take it seriously for measurement work**: a throttled phone has
a hot sensor, and a hot sensor is a noisier one.

The same bench after the camera was given the idling described in
[Limits, and the camera idling](#limits-and-the-camera-idling), an open panel
tab was closed, and the phone was set to stop charging at 80 percent:

| | after an afternoon of captures | a quiet hour later |
|---|---|---|
| Thermal status | severe | **none** |
| Battery | 38.1 °C | **27.2 °C** |

Nothing about the hardware changed between those two columns.

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

## Versions and Releases

DeskCam uses [semantic versioning](https://semver.org). `VERSION` at the top of the repository is the one number: `build.sh` puts it in the APK, the CLI prints it with `deskcam version`, and the app shows it beside its name. A release is tagged `vX.Y.Z`, and [CHANGELOG.md](CHANGELOG.md) lists what changed. Until 1.0.0 the HTTP API may change in a minor release, and every such change is in the changelog.

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
