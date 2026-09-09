---
name: deskcam
description: Look at real physical things with a phone bench camera over HTTP. Use when the user asks you to look at, photograph, inspect, or measure something on their desk - a circuit board, a component, an OLED or LCD panel, a connector, a cable, a device screen, a 3D print, anything physical. Also use when the user says "look at this", "can you see", "what does this look like", "check the board", "photograph it", or refers to the deskcam, bench camera, or phone camera. Gives full-resolution stills, software zoom and pan, manual focus and exposure, RAW/DNG, and burst capture.
---

# DeskCam

A phone on a stand acting as a bench camera. You drive it with one shell command and
read the resulting file.

The camera is a measuring instrument first. Where "looks good" and "is correct" disagree,
correct wins.

## The loop

```sh
img=$(deskcam snap zoom=4 cx=0.35 cy=0.62)
```

`snap` prints the path it wrote and nothing else. Read that path with the Read tool to
see the picture. Every capture also writes `NAME.json` beside the image, holding the
settings and the measured values.

Check the camera is there before a long sequence:

```sh
deskcam show      # one line: zoom, framing, focus, exposure, iso
```

If that fails, refer to **When it does not work** at the end.

## Aiming

Zoom and pan are a crop of the full 4032x3024 sensor, done in software. `zoom=4` gives a
true 1008x756 crop of real pixels. Nothing is enlarged.

| Want | Command |
|---|---|
| See everything | `deskcam reset` |
| Closer | `deskcam snap zoom=4` |
| A named spot | `deskcam snap zoom=6 cx=0.3 cy=0.7` |
| Nudge the view | `deskcam pan left`, `pan up 0.4` |
| Back to centre | `deskcam center` |

`cx` and `cy` are 0 to 1 across the whole frame. `0,0` is the top left corner and
`0.5,0.5` is the centre. **Take a wide shot first, read it, then pick coordinates from
what you saw.** Do not guess coordinates blind.

Focus and light metering follow the crop, so a zoom onto a part also focuses on that part.

If the picture is upside down or sideways, the phone is mounted that way. Correct it once
with `deskcam set rotate=180` and it stays.

## Close work on a board

The camera focuses to 98 mm. At that distance one pixel covers 30 micrometres, so it
reads silkscreen and finds a part. It cannot show a solder fillet. That is an optical
limit and no setting changes it.

```sh
deskcam set focusm=0.12 torch=30      # focus at 12 cm, LED at level 30 of 45
img=$(deskcam snap zoom=5)
```

`torch` is the rear LED, 0 to 45. It is the bench light. Use it whenever the part is in
shadow. Turn it off after with `torch=0`.

If focus is wrong, `deskcam af` runs one autofocus sweep, or set the distance yourself in
metres with `focusm`.

## Photographing a display

**A display needs a fixed exposure or you get dark bands across it.** The panel flickers
faster than the eye sees, and the camera reads the frame one row at a time, so different
rows catch different parts of the flicker.

Set the exposure to a whole multiple of the panel period:

```sh
deskcam set exposure=1/60 iso=200 awb=daylight awblock=on   # a 60 Hz panel
img=$(deskcam snap)
```

Try `1/60`, `1/30` or `1/120` and keep whichever has no bands. Lock the white balance as
well, or the colour drifts between shots and you will report colour differences that are
not real.

## Measuring, not photographing

Use `measure=1` when the pixel values themselves matter, for example when comparing two
renders or checking panel uniformity.

```sh
img=$(deskcam snap measure=1 iso=56 exposure=1/120)
```

This turns off the tone curve, noise reduction, edge enhancement, and lens shading
correction. It was measured on the device. In this mode, doubling the exposure doubles the
pixel value, at 2.02x. The default pipeline gives only 1.30x and lifts the shadows about
ten times. The default makes a photograph look good, which is the opposite of what a
comparison needs.

The image will look dark and flat. That is correct.

`deskcam status` reports a `pipeline` block saying what the camera actually applied, not
what was asked for.

## Better data

| Need | Command | Why |
|---|---|---|
| Linear sensor data | `deskcam raw -o x.dng` | 10-bit, unprocessed, for real measurement. 24 MB. |
| Less noise | `deskcam burst 16` | Average the frames. Noise falls by about the square root of the count. |
| Repeat an old shot | `deskcam recall old.json` | Restores exact settings, so a comparison is valid. |

The DNG holds the **whole sensor**. Zoom and pan do not apply to it, because a workstation
must demosaic before it crops. The framing is reported in the `X-DeskCam-ROI` header.

For a burst, set the exposure a little dark. Highlights that clip cannot be recovered, and
the average brings the shadows back.

## Everything is one parameter set

Any parameter works on any command, and is applied before the picture is taken. So one
line is a complete instruction and you never need to remember the current state:

```sh
deskcam snap zoom=6 cx=0.3 cy=0.7 focusm=0.15 torch=25 exposure=1/120 iso=100
```

`camera`, `zoom`, `zoomby`, `cx`, `cy`, `dx`, `dy`, `af`, `focus`, `focusm`, `ae`,
`exposure`, `iso`, `ev`, `aelock`, `awb`, `awblock`, `torch`, `measure`, `jpegq`,
`rotate`, `w`, `h`, `reset`, `settle`.

Exposure accepts what a datasheet says: `1/120`, `8ms`, `250us`, `0.5s`.

A wrong parameter name is an error, not a silent no-op. If a command fails, read the
message; it names the bad parameter.

`deskcam api` prints the full machine-readable reference if you need something not here.

## Judgement

- **Read the image you took.** Do not report on a picture you have not looked at.
- Take a wide shot before a close one. Pick coordinates from what you actually saw.
- `deskcam frame` is much faster than `snap` and is enough while you are still aiming.
  Use `snap` once the framing is right.
- Do not push zoom past about 8. You are cropping to very few pixels. Ask the user to move
  the camera closer instead.
- Say when you cannot see something well enough. A blurred or too-distant picture is not
  evidence. Ask for the camera to be moved, or for a macro lens.
- Turn the torch off when you finish.
- The camera is rear-facing, 12.2 MP, id 0. Do not switch to camera 1; it is the front
  camera, lower resolution and fixed focus, so it cannot focus on a board at all.

## When it does not work

```sh
deskcam show
```

| Symptom | Meaning | Fix |
|---|---|---|
| `state: disconnected` | Another app has the camera | It reopens by itself within about 15 s. Wait, then retry. |
| Connection refused | The service is not running | `deskcam start`, or ask the user to open DeskCam on the phone |
| Wrong or no address | The phone moved to a new address | `deskcam wifi`, or `deskcam usb` for a cable, or `deskcam serve` and scan the QR code |
| A capture times out | The exposure is very long | Raise it with `timeout=20000`, or shorten the exposure |

After a reboot the phone does not start the camera by itself. Android forbids it for a
camera app. Run `deskcam start`, or ask the user to tap the app once.
