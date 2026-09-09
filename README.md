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

## Installation

```sh
adb install -r -g backend/build/deskcam.apk   # -g gives the permissions immediately
./frontend/deskcam start                      # or open the app and touch Start
./frontend/deskcam wifi                       # find the Wi-Fi address of the phone
./frontend/deskcam show
```

The `-g` option is important. Without it you must give the camera permission and the local
network permission by hand.

Put `frontend/deskcam` on your `PATH`. The CLI stores the target in
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

Each operation is a GET. Get the same reference as JSON from `/api/help`.

| Endpoint | Purpose |
|---|---|
| `/api/status` | The settings, the sensor limits, and the measured exposure, ISO, and focus |
| `/api/still` | A full resolution JPEG, cropped to the ROI |
| `/api/raw` | A full sensor RAW frame as a DNG. Refer to the note below. |
| `/api/frame` | One preview JPEG. Much quicker. |
| `/api/stream` | MJPEG. `fps` and `n` are optional. |
| `/api/set` | Apply the parameters. Give the result. |
| `/api/af` | Do one autofocus sweep |
| `/api/reset` | Set all values to the default |
| `/api/cameras` | List the cameras |
| `/api/help` | This reference as JSON |
| `/api/nettest` | An outbound test for the permission fault above |
| `/` | The browser panel. Touch to centre. Turn the wheel to zoom. |

These parameters are correct on each endpoint:

| Parameter | Meaning |
|---|---|
| `camera` | The camera id. The rear camera is `0`. |
| `zoom`, `zoomby` | The software zoom. The value `1.0` is the full sensor. |
| `cx`, `cy` | The centre of the ROI, from 0 to 1 |
| `dx`, `dy` | A relative move, in fractions of the current ROI width |
| `af` | `off`, `auto`, `macro`, `continuous`, `video`, or `edof` |
| `focus`, `focusm` | The manual focus in dioptres, or in metres |
| `ae` | `on` or `off` |
| `exposure` | `1/120`, `8ms`, `250us`, `0.5s`, or nanoseconds. This sets `ae=off`. |
| `iso` | The sensitivity. This sets `ae=off`. |
| `ev`, `aelock` | The compensation and the lock, when `ae=on` |
| `awb`, `awblock` | The white balance mode and the lock |
| `torch` | `0` to `45`, or `off`, `on`, or `max` |
| `jpegq` | The quality. The default is 92. |
| `rotate` | `0`, `90`, `180`, or `270`. This turns the pixels. |
| `w`, `h` | Change the size after the crop. One value keeps the aspect ratio. |
| `previewsize`, `stillsize` | The capture sizes. These make a new session. |
| `reset=1` | Set all values to the default before the rest of this request |
| `settle` | The wait in milliseconds after a change, before the capture |

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

Macro is an optical limit. At the 98 mm minimum focus distance the camera gives 33.4
pixels for each millimetre. This is 30 micrometres for each pixel. This is sufficient to
read silkscreen and to find a part. It is not sufficient to see a solder fillet. A clip-on
macro lens is the correction.

`/api/still` sends the JPEG of the camera without a change when the zoom is 1, and there
is no rotation, and there is no resize. This is the quickest path and the best quality.
Any crop, rotation, or resize costs a decode and a new encode. `BitmapRegionDecoder` reads
only the necessary tile. Thus a large zoom costs less than a small zoom.

The app converts a preview frame only when a client asks for one. An idle service costs
almost nothing.

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
