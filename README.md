# DeskCam

Turns a spare Android phone into a bench camera you drive from the command line or from
a coding agent. Built for a Pixel 6a running GrapheneOS, mounted over a desk, used for
inspecting OLED panels and debugging circuits.

Every control is a plain HTTP GET, so the whole camera is reachable from `curl`, a
shell script, or an agent with no client library.

```
deskcam snap zoom=6 cx=0.32 cy=0.68 torch=25 focusm=0.12
```

## Why it works the way it does

The Pixel 6a rear sensor reports `android.scaler.croppingType = CENTER_ONLY`. The camera
HAL therefore discards the offset of any crop rectangle it is given and can only zoom
about the centre, so hardware pan is not available at all.

DeskCam instead always asks the sensor for its full 4032x3024 active array and crops the
region of interest in software. That gives arbitrary pan, and it keeps every zoomed pixel
a real sensor pixel rather than a HAL upscale. At 4x zoom you are looking at a native
1008x756 crop of the sensor, not an interpolated one.

Focus and exposure metering regions follow the ROI, so zooming onto a component makes the
camera focus and meter for that component rather than the whole bench.

The camera is `LEVEL_FULL` with `MANUAL_SENSOR` and `RAW`, which is what makes the display
work practical: exposure is settable from 53.7us to 10.2s, ISO 56 to 7111, focus down to
9.8cm, and the torch has 45 discrete brightness levels.

## Requirements and the one non-obvious gotcha

**Android 17 split local network access out of `INTERNET`.** An app can hold `INTERNET`,
reach the public internet perfectly, and still have every single local-network packet
dropped in both directions. The symptom is that the server looks healthy in `logcat`,
`ss` shows it listening, a TCP SYN from your desktop is never answered, and connecting
from the phone to its own address works fine.

The fix is `android.permission.ACCESS_LOCAL_NETWORK`, which this app declares and requests.
If you ever see the service running but unreachable, check that permission first.

## Build

No Gradle and no Android Studio. The app has zero external dependencies, so it builds
straight from the SDK build-tools.

```sh
./build.sh                 # -> build/deskcam.apk
```

Needs a JDK, plus `platforms/android-37.0` and `build-tools/37.0.0` in `$ANDROID_HOME`
(defaults to `~/Android/Sdk`). A signing key is generated on first build.

## Install and run

```sh
adb install -r -g build/deskcam.apk        # -g grants the permissions up front
tools/deskcam start                        # or just open the app and press Start
tools/deskcam wifi                         # point the CLI at the phone's Wi-Fi address
tools/deskcam show
```

`-g` matters. Without it you must grant camera and local network access by hand.

Put `tools/deskcam` on your `PATH`. The target is remembered in
`~/.config/deskcam/url` and can be overridden with `DESKCAM_URL`.

## CLI

```
deskcam snap [-o FILE] [k=v ...]     full-resolution still, cropped to the ROI
deskcam frame [-o FILE] [k=v ...]    fast preview-resolution frame
deskcam stream [-o FILE] [n=N]       MJPEG stream

deskcam show                         one-line summary
deskcam status                       full JSON state
deskcam set k=v [k=v ...]            apply any control parameters
deskcam reset                        restore defaults

deskcam zoom N                       1.0 is the full sensor
deskcam pan up|down|left|right [amt] nudge, in fractions of the current view
deskcam center
deskcam af                           one autofocus sweep
deskcam focus METRES|auto
deskcam exposure 1/120|8ms|250us|0.5s
deskcam iso N
deskcam auto                         back to auto exposure and focus
deskcam torch 0-45|off|max

deskcam cameras                      cameras and capabilities
deskcam api                          machine-readable API description
deskcam open                         open the web control panel

deskcam use URL | usb | wifi | which | start | stop
```

`snap` and `frame` print the path they wrote, which is what makes them easy to drive
from an agent:

```sh
img=$(deskcam snap zoom=4 cx=0.3 cy=0.7)
```

Any command also accepts bare `k=v` words, applied before the image is taken.

## HTTP API

Everything is a GET. Fetch `/api/help` for the same reference as JSON.

| Endpoint | Purpose |
|---|---|
| `/api/status` | settings, sensor limits, last measured exposure/ISO/focus |
| `/api/still` | full-resolution JPEG cropped to the ROI |
| `/api/frame` | single preview-resolution JPEG, much faster |
| `/api/stream` | MJPEG, `fps` and `n` optional |
| `/api/set` | apply parameters, return resulting settings |
| `/api/af` | one autofocus sweep |
| `/api/reset` | restore defaults |
| `/api/cameras` | list cameras |
| `/api/help` | this reference as JSON |
| `/api/nettest` | outbound connectivity probe, for diagnosing the LAN permission |
| `/` | browser control panel, click to centre and scroll to zoom |

Parameters, valid on any endpoint:

| Parameter | Meaning |
|---|---|
| `camera` | camera id, `0` is rear |
| `zoom`, `zoomby` | software zoom, `1.0` is the full sensor |
| `cx`, `cy` | absolute ROI centre, 0..1 |
| `dx`, `dy` | relative pan, in fractions of the current ROI width |
| `af` | `off`, `auto`, `macro`, `continuous`, `video`, `edof` |
| `focus`, `focusm` | manual focus in diopters, or in metres |
| `ae` | `on` or `off` |
| `exposure` | `1/120`, `8ms`, `250us`, `0.5s`, or nanoseconds. Implies `ae=off` |
| `iso` | sensitivity. Implies `ae=off` |
| `ev`, `aelock` | compensation and lock, while `ae=on` |
| `awb`, `awblock` | white balance mode and lock |
| `torch` | `0`..`45`, or `off`/`on`/`max` |
| `jpegq` | quality, default 92 |
| `rotate` | `0`, `90`, `180`, `270`, applied to the pixels |
| `w`, `h` | resize after cropping, one of them preserves aspect |
| `previewsize`, `stillsize` | capture sizes, rebuilds the session |
| `reset=1` | clear settings before applying the rest of the request |
| `settle` | ms to wait after applying settings before capturing |

## Recipes

Photographing an OLED or LCD without PWM banding. Fix the exposure to a whole multiple
of the panel refresh period, otherwise the rolling shutter samples different parts of the
duty cycle down the frame:

```sh
deskcam set exposure=1/60 iso=200 awb=daylight awblock=on   # 60Hz panel
deskcam snap
```

Locking white balance matters as much as exposure when you are comparing two renders,
since auto white balance will otherwise drift between shots and invent colour differences.

Close inspection of a board, with the LED as a ring light:

```sh
deskcam set focusm=0.12 torch=30 zoom=6
deskcam snap -o u3-pin1.jpg
```

Mounted upside down, which is common on an overhead arm:

```sh
deskcam set rotate=180
```

Sweeping a region and keeping the frames:

```sh
for x in 0.25 0.5 0.75; do deskcam snap -o "scan-$x.jpg" zoom=4 cx=$x cy=0.5; done
```

## Notes and limits

Zoom is capped where cropping stops being useful, around 63x on this sensor. Past roughly
6x you are looking at very few pixels, so prefer moving the phone closer and using
`focusm` down to 0.098m.

`/api/still` returns the untouched camera JPEG when zoom is 1 with no rotate or resize,
which is both the fastest and the highest quality path. Any crop, rotate or resize costs
a decode and re-encode. `BitmapRegionDecoder` decodes only the requested tile, so a deep
zoom is cheaper than a shallow one.

Preview frames are only converted from YUV while something is actually asking for them,
so an idle service costs nothing.

USB is a reliable fallback and does not need the local network permission at all, since
it tunnels over loopback:

```sh
deskcam usb        # adb forward, target becomes 127.0.0.1:8080
```

Start on boot does not work on Android 17, and this is a platform restriction rather
than a bug. Both available routes are blocked by design, which was verified by rebooting
the device:

    ForegroundServiceStartNotAllowedException: FGS type camera not allowed to start from BOOT_COMPLETED
    Background activity launch blocked! ... (BAL_BLOCK)

`BootReceiver` still tries, and `CamService` catches the refusal so the service fails
quietly instead of crash-looping the way it did before this was handled. After a reboot,
bring it back with one of:

```sh
deskcam start          # over adb, no need to touch the phone
```

or tap the app once. Granting the app "Display over other apps" (`SYSTEM_ALERT_WINDOW`)
would exempt it from the background activity launch rule and make autostart work, but
that is a broad permission and this app does not request it.

There is no HTTPS. The service is intended for a trusted LAN, and a self-signed
certificate would mean every `curl` needs `-k` for no real gain. Set a token in the app
to require `?token=...` or an `Authorization: Bearer` header. If you want the camera
encrypted or reachable from outside the LAN, put the phone on a WireGuard or Tailscale
network and keep the server on plain HTTP behind it.
