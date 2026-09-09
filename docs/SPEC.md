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
                ├─→ frontend/deskcam ──┐ |
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

### 4.2 Components

| File | Responsibility |
|---|---|
| `CameraEngine.java` | The Camera2 device, the session, the images, and the status |
| `CamSettings.java` | The control state, the ROI maths, the parameters, and the JSON |
| `HttpServer.java` | HTTP/1.1 on a `ServerSocket`, the routes, and the MJPEG parts |
| `WebUi.java` | The browser panel and the `/api/help` document |
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
| Lens shading map | 33 x 25 | A check on the flat field |
| Torch | 45 steps | Controlled light for the bench |
| High speed video | 1080p120 and 1080p240 | Display timing measurement |
| Rolling shutter skew | Reported for each frame | The PWM frequency from one still image |

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
| `/api/status` | JSON | The settings, the limits, the geometry, and the `measured` block |
| `/api/still` | `image/jpeg` | Full resolution. Cropped to the ROI. |
| `/api/frame` | `image/jpeg` | Preview resolution. Much quicker. |
| `/api/stream` | `multipart/x-mixed-replace` | MJPEG. Use `fps` and `n`. |
| `/api/set` | JSON | Apply the parameters. Give the result. |
| `/api/reset` | JSON | Set all values to the default. |
| `/api/af` | JSON | Do one autofocus sweep. |
| `/api/cameras` | JSON | List the cameras and the capabilities. |
| `/api/help` | JSON | The self description. Refer to R6. |
| `/api/nettest` | JSON | An outbound test. It finds the fault in section 4.4. |
| `/` | `text/html` | The browser panel |

Rule R3 applies to each endpoint. The server applies the control parameters before it
makes the image.

These are the control parameters:

`camera`, `zoom`, `zoomby`, `cx`, `cy`, `dx`, `dy`, `af`, `focus`, `focusm`, `ae`,
`exposure`, `iso`, `ev`, `aelock`, `awb`, `awblock`, `torch`, `jpegq`, `rotate`, `w`, `h`,
`previewsize`, `stillsize`, `reset`, `settle`, `timeout`.

**The coordinate model.** `zoom` is a scale. The value 1.0 is the full sensor. `cx` and
`cy` give the centre of the ROI from 0 to 1. `dx` and `dy` are relative. They use
fractions of the **current** ROI width. Thus one step moves the same visible distance at
each zoom value. The server keeps the ROI inside the frame.

**Errors.** The server rejects an unknown parameter. The server rejects a parameter that
it cannot read. The result is HTTP 400 with `{"ok": false, "error": "..."}`. The server
changes nothing. Refer to R5.

**Access control.** There is an optional shared token. Send it as `?token=` or as
`Authorization: Bearer`. The token is off by default. There is no TLS. Refer to section 7.

### 4.6 How the backend makes images

There are two paths. They have different costs and different purposes.

**The still path.** The engine sends a `TEMPLATE_STILL_CAPTURE` request to a full
resolution JPEG `ImageReader`. The server can send the JPEG of the camera **without a
change**. This needs three conditions. The zoom is 1. There is no rotation. There is no
resize. This is the quickest path. It is also the best quality. In other conditions `BitmapRegionDecoder` reads only
the necessary tile. Thus a large zoom costs less than a small zoom.

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

The CLI is `frontend/deskcam`. It finds the target in this order: the `--url` option, then
`$DESKCAM_URL`, then `~/.config/deskcam/url`, then localhost.

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

### 5.4 Desktop processing (planned)

This part does not exist. The roadmap in section 8 goes here. This is the reason for the
position of the split. The plan is Python with OpenCV, rawpy, and NumPy.

## 6. Decisions

**D1. The software makes the ROI. The hardware does not.** `CENTER_ONLY` makes this
necessary. It is also better, because it keeps true sensor pixels. Refer to section 4.3.

**D2. The backend has no dependencies.** This makes the build without Gradle possible. The
build is then repeatable in seconds from a shell. A written HTTP server is about 400 lines.
It gives direct control of the MJPEG parts.

**D3. All operations are GET.** This does not agree with REST. It is correct here. The
primary consumer writes URLs in a shell. A change of state as a GET is easy to script and
easy to repeat.

**D4. Each status response gives the measured values.** Refer to R4. This costs nothing.
The engine already holds the `TotalCaptureResult`.

**D5. An unknown parameter is an error.** If the server ignored it, the agent would get an
unchanged image. The agent would then think that the setting applied.

**D6. A pan step uses ROI widths. It does not use frame fractions.** Thus `pan left` has
the same result at 1x and at 8x. A person and an agent both expect this.

**D7. The engine converts a preview frame only on demand.** A bench camera that runs all
day must cost nothing when nobody looks at it.

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

**1. RAW and DNG capture (backend).** All measurement work needs linear data. The sensor
is 10 bit. The black level is 64. The white level is 1023. The full DNG calibration data
is present. `DngCreator` is in the framework.

**2. Measurement mode (backend).** One switch stops the pipeline from changing the image.
Set noise reduction to off. Set edge enhancement to off. Set the tone map to a linear
`CONTRAST_CURVE`. Set OIS to off. Lock the white balance. Section 4.3 confirms that the
device permits each control. The default pipeline makes a photograph look good. This is
the opposite of a comparison between two renders.

**3. Burst capture (backend) and average (frontend).** Take one idea from HDR+. Capture
many frames below the correct exposure. Then merge them. The highlights do not clip. The
noise falls with the square root of the frame count. The mount is fixed, so alignment
costs nothing.

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
vignetting looks the same as panel non-uniformity.

**8. Display rectification (frontend).** Find the panel corners. Correct the perspective
with a homography. Give an image of a constant size. This lets you compare two design
iterations pixel by pixel. Without it, a small movement of the phone changes the image.

**Rejected work.** ISO brackets are not useful. The max analog sensitivity is 444. Above
that value the gain is digital, so apply it to RAW data later. White point brackets are
not useful. RAW makes the white balance free and lossless later.

## 9. Known limits

**Macro is an optical limit. Software cannot correct it.** At the 98 mm minimum focus
distance the main camera gives 0.047x magnification. The field of view is 120.7 mm wide.
The resolution is 33.4 pixels for each millimetre. This is 30 micrometres for each pixel.
This is sufficient to read silkscreen and to find a part. It is not sufficient to see a
solder fillet. A clip-on macro lens is the correction. The physical ultrawide camera
(`id 3`) does not open directly, so it gives no other method.

**The phone gets a new DHCP address after a reboot.** The command `deskcam wifi` finds the
new address. A fixed address on the router is better.
