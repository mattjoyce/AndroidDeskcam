# How it works

Why the camera behaves the way it does. None of this is needed to use it, but most of it
explains a behaviour that would otherwise look like a bug. The reasoning behind each design
decision is in [docs/DECISIONS.md](../DECISIONS.md), and there is an interactive version of
several of these in [the explainer](../../explainer/index.html).

## Why the crop is done in software

The Pixel 6a reports `android.scaler.croppingType = CENTER_ONLY`: its hardware zoom always
crops around the centre, so it cannot pan. DeskCam asks the sensor for the whole 4032 by 3024
image every time and crops it in software with `BitmapRegionDecoder`. That gives three
things:

1. **Panning anywhere.** The crop can be centred on any `cx`, `cy` across the sensor.
2. **Real pixels.** Nothing is interpolated or enlarged. At zoom 4 you get a 1008 by 756 crop
   of photosites.
3. **Metering on what you are looking at.** The exposure metering and autofocus regions follow
   the crop, so zooming onto a part exposes and focuses for that part. `focusbox` moves the
   focus region to a rectangle of its own and leaves the metering on the crop (decision D17).

`BitmapRegionDecoder` reads only the tile it needs, so a large zoom costs less than a small
one.

**There are two kinds of still.** At zoom 1.0001 or below, with no rotation and no resize,
`/api/still` sends the camera's own JPEG untouched, which is the fastest path and the best
quality. Anything else costs a decode and a new encode. Each capture records which path it
took in `settings.capture_path`, as `camera_jpeg` or `decoded_and_reencoded`, because two
captures on opposite sides of that line are different kinds of image, and comparing them
measures the pipeline rather than the subject.

## Limits

The zoom stops at about 63x on this sensor, but above about 6x the crop holds few pixels, and
moving the phone closer does better. `focusm` goes down to 0.098 m.

At that 98 mm minimum focus distance the camera gives about 33 pixels a millimetre, roughly
30 micrometres a pixel. That is arithmetic from the sensor size and the stated minimum focus
distance, not a measurement, and the camera reports its focus calibration as `APPROXIMATE`.
It is enough to read silkscreen and not enough to see a solder fillet.

## The camera sleeps when nobody is asking

After 20 seconds with no stream and no request for a frame, the phone stops reading its
sensor. The next request that needs a frame starts it again and waits for the exposure to
settle before answering. `/api/status` has a `preview` block saying whether it is idle, for
how long, and what the last wake cost.

Before this (decision D16), the sensor, the image processor and the camera service ran at
29 frames a second for the life of the service, whether or not anyone looked. A bench phone
left running overnight was found at the platform's `severe` thermal level for that reason.

Measured on a Pixel 6a, three runs each:

| | |
|---|---|
| Frames while idle | **0 in 10 s**, against 29 a second awake |
| Wake, exposure fixed | 305 to 348 ms |
| Wake, exposure automatic | 377 to 803 ms |
| A still taken against a sleeping camera | 764 to 822 ms, about 320 ms of it the wake |

In every automatic run the first frame after a wake had the same exposure as one taken two
seconds later, and the ISO agreed to within 5 of 200. Idling costs a slower first capture,
not a worse one.

A stream keeps the camera awake. That is why both camera pages stop their stream when their
tab is hidden, and pause the view after five minutes untouched.

## Why focus is stepped in dioptres

A dioptre is one over the distance in metres. Depth of field is roughly constant per dioptre,
whatever the distance, while in millimetres it varies enormously. Near the 98 mm closest
focus, 1 mm is about 0.10 dioptres. At half a metre, 1 mm is 0.004 dioptres.

A sweep stepped evenly in millimetres would take many overlapping frames up close and skip
past the subject further out. Stepped evenly in dioptres, each frame covers about the same
depth.

## Focus by number

`/api/status` carries a `sharpness` block: the variance of the Laplacian over the crop, or
over the `focusbox`, of one preview frame. It lets an agent close a focus loop by moving the
lens and reading a number, without pulling pictures across the network.

```sh
deskcam set focus=4.25 && deskcam show sharpness=1
zoom 1x  at 0.5,0.5  af off  ae manual  29.97ms (1/33)  iso 100  MEASURE  sharp 33.6
```

A sweep of a rule 235 mm from the lens, exposure and ISO held fixed, one reading a step:

| dioptres | 1.0 | 3.0 | 3.5 | 4.0 | **4.25** | 4.5 | 5.0 | 5.5 | 6.0 | 9.0 |
|---|---|---|---|---|---|---|---|---|---|---|
| sharpness | 3.5 | 11.4 | 20.3 | 30.8 | **33.6** | 31.0 | 18.0 | 9.4 | 5.8 | 2.9 |

One maximum, falling away on both sides, at the distance the phone's own autofocus picks.

Three things to know about the number:

* **It is a comparison, never a measurement.** It moves with the subject, with how much of the
  frame the region holds, and with noise, which at high ISO looks like fine detail. Compare
  only readings taken with everything but the focus held still.
* **It describes the last preview frame converted,** which may be old. Its age comes with it,
  and `deskcam show` prints the age once it passes half a second. Without `sharpness=1`
  nothing new is converted, because a status poll that demanded a frame would have an open
  page converting frames continuously (decision D7).
* **It costs about 9 ms** on a Pixel 6a, and the cost is reported with the value. The sample
  is thinned by skipping rows, never columns or the kernel's neighbours, because a kernel over
  skipped pixels measures a blurrier image and would put the peak in the wrong place.

## Hunting the focus

That loop from the shell is fourteen round trips. `/api/focushunt` runs it on the phone,
where each step costs nothing extra. It is the one loop that has to live on the phone, because
each step depends on the frame the last one produced.

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

A coarse pass over the range, then a fine pass around its best reading. Nine coarse readings
cannot step over a peak several dioptres wide, and five fine ones land within about a sixth of
a dioptre of it. The whole ten-dioptre range takes about four seconds.

**The position repeats; the peak value does not.** Twelve hunts of one subject, started from
both ends of the lens travel: eleven chose 3.827 d, and one chose the next fine step, 4.464 d,
whose sharpness was within a few percent. The peak values across the same twelve ran from
34.9 to 64.5. Use the `diopters` a hunt returns, and never compare `sharpness` between hunts.

**It can refuse,** which `/api/af` cannot. A flat curve means nothing in the region came into
focus anywhere in the range:

```
$ deskcam focus hunt exposure=1/4000 iso=56
   0.000 d       0.0
   ... every reading the same ...
deskcam: no focus chosen (flat): the sharpness moved by 0% across 0.00 to 10.20 dioptres,
and a peak moves it by far more.
```

A peak on an end of the range means the real peak is outside it:

```
$ deskcam focus hunt from=0 to=3
   ... climbing all the way to the last reading ...
       3 d      20.3  ######################################## <-
deskcam: no focus chosen (peak_at_edge): the sharpest reading, 3.00 dioptres, is the near
end of the range that was searched ... Widen the range and hunt again, e.g. to=6.00
```

Both answer `ok: false` with the reason and the curve, put the focus back, and exit non-zero.
When a hunt does choose, the lens stays there, which is the opposite of a focus sweep.

**Fix the exposure first.** With auto exposure, the exposure moves between readings, the
number moves with it, and the hunt climbs the exposure loop instead of the lens. It warns when
it sees `ae=auto`; the fix is `exposure=` and `iso=`.

## What the tilt reading is

Each sidecar records the camera's tilt, from the gravity sensor:

```
tilt 1.57 deg, nearly straight down (1.57 degrees from gravity), 32 samples
gravity {x: 0.22, y: 0.16, z: 9.81}   ambient 85 lux
```

**This is the angle between the lens axis and gravity.** What skews a flat subject is the angle
between the camera and the subject's plane. The two are equal only when the subject lies on a
level surface. On a tilted jig they differ by the jig's tilt, so a small reading means square
framing only when you know the bench is level.

The angle is averaged over 32 samples, because one accelerometer sample carries the noise of
the sensor and the bench. The words that go with it carry the number, so a reading near a band
boundary reads as what it is.

The sensors give an angle, never a distance or a position. Measuring a size still needs a
scale reference or the mat in the frame. `deskcam status` and `/api/orientation` report the
tilt live, and **Level** in the phone app sets it.

## Heat and battery

A phone bolted to a stand, holding a camera and a wake lock for hours, gets hot.
`/api/status` carries a `device` block:

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

**`plugged_in` and `charging` are different facts.** A phone told to stop charging at 80%,
which suits one that lives on a stand, has the cable in and is not charging: `plugged_in`
stays true, `charging` goes false, and `battery_status` says `not_charging`. The block says so
in a note, so nobody goes looking for a bad cable.

**`thermal` and `battery_celsius` are different quantities.** `thermal` is the platform's own
level, the one it throttles by, and the one a stream reacts to. `battery_celsius` is a real
temperature from the only thermometer an ordinary app may read, and it measures neither the
sensor nor the processor.

This bench phone after an afternoon of bursts, walks and streams, from
`dumpsys thermalservice` beside what the app reports:

| | reading |
|---|---|
| Platform thermal status | **3, severe** |
| Battery | 29.5 °C |
| Skin | 34.1 and 35.0 °C |
| Display | 29.6 °C |
| TPU | **53.0 °C** |

Every thermometer an app can reach says the phone is comfortable, while the platform throttles
severely because of a part none of them measures. That is why `thermal` is the number that
matters.

**A stream gives way; a capture never does.** The stream is the continuous load, so its rate is
cut: half at `moderate`, a quarter at `severe`, an eighth at `critical`, a twentieth at
`emergency` and `shutdown`, and not at all at `none` or `light`. Each part of the stream says so,
so a client whose rate falls can tell heat from a network fault:

```
Content-Type: image/jpeg
X-DeskCam-Fps: 2.50
X-DeskCam-Thermal: severe
X-DeskCam-Shedding: throttling severely, and the platform says the experience is largely
  affected. The stream rate is a quarter of what was asked for. Captures are not slowed.
```

Six frames at a requested 10 fps took 2.46 s rather than 0.6 s on a severe phone, which is the
quarter rate. The stream is never stopped, even at `shutdown`, because it carries the reason.

`deskcam show` ends with `HOT severe` once the platform is acting, and says nothing while the
phone is merely warm.

The same bench after the camera learned to idle, an open page's tab was closed, and the phone
was set to stop charging at 80%:

| | after an afternoon of captures | a quiet hour later |
|---|---|---|
| Thermal status | severe | **none** |
| Battery | 38.1 °C | **27.2 °C** |

Nothing about the hardware changed between those two columns.
