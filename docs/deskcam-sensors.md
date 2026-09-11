# DeskCam as an instrument — sensors, audio and the clock

Status: proposal, 2026-09-11. Written against the hardware in front of us, which is a
Pixel 6a on GrapheneOS. Every capability table below was read off that device rather
than off a datasheet. Nothing here is built.

## 1. The reframe

"Give the agent access to all the sensors" is the wrong axis. Reading a sensor is a few
lines. **Putting a reading on the same clock as the frame is the instrument.**

> What was the accelerometer doing during the 30 ms this frame was exposed?

is a science tool. "What is the accelerometer now?" is a novelty. Everything in this
document follows from that one sentence, and the parts that do not serve it are marked
as what they are.

DeskCam already believes the useful half of this. Rule R4 reports measured values and not
requested ones. Decision D8 says a capture describes itself, and every capture already
carries the exposure, the ISO, the focus and the lens position of its own frame. This is
that idea taken to everything else the phone can sense.

## 2. What this device actually has

### 2.1 Camera metadata that already arrives and is thrown away

The highest value work here is not a new sensor. It is the per-frame metadata already in
every `TotalCaptureResult`, verified present on this device:

| Key | Type | What it buys |
|---|---|---|
| `sensor.noiseProfile` | double[8] | The (S, O) noise model per CFA channel, per frame |
| `sensor.rollingShutterSkew` | int64 | How long the sensor took to read the frame out |
| `sensor.dynamicBlackLevel` | float[4] | Per frame, per channel, against the static 64 |
| `sensor.dynamicWhiteLevel` | int32 | Per frame, against the static 1023 |
| `lens.intrinsicCalibration` | float[5] | Focal length, principal point, skew |
| `lens.distortion` | float[5] | Radial and tangential distortion coefficients |
| `lens.poseRotation`, `lens.poseTranslation` | float[4], float[3] | Where this lens sits |
| `logicalMultiCamera.activePhysicalId` | byte[] | **Which physical camera took the frame** |
| `sensor.effectiveExposureFactor` | float | What the exposure was really worth |

Two of these change work that already exists.

**The noise profile makes card 4 checkable.** That card measured an average of 6 frames as
2.25 times less noisy than one, against a prediction of 2.45, and recorded the result as
**unconfirmed** because one run with an unrecorded grouping is not evidence. The sensor
publishes its own noise model per frame. The prediction stops being arithmetic on an
assumption and becomes a comparison against what the sensor says about itself.

**Rolling shutter skew is the missing half of card 7.** That card steps exposure in whole
PWM periods because a lit panel is switched at some hundreds of hertz and an exposure that
is not a whole number of periods reads a different part of the duty cycle. Skew is the
readout time, so it says which rows saw which part of that cycle. Banding stops being an
artefact to be avoided and becomes a way to **measure a panel's PWM frequency from a single
frame**.

**And one of them is alarming.** Camera 0 on this device is a logical multi-camera backed
by physical cameras 2 and 3. If the HAL switches between them, two captures being compared
came from different lenses and **nothing in the current provenance would say so**. That is
a measurement fault of exactly the kind rule R4 exists to prevent, sitting in a field the
HAL already fills in.

### 2.2 The motion and environment sensors

Read from `dumpsys sensorservice` on this device:

| Sensor | Part | Rate | Buffer |
|---|---|---|---|
| Accelerometer | LSM6DSR | 1.62 to 415.97 Hz | 3000 events |
| Gyroscope | LSM6DSR | 1.62 to 415.97 Hz | 3000 events |
| Magnetometer | MMC56X3X | 1.25 to 100 Hz | 3000 events |
| Pressure | ICP10101 | 0.1 to 25 Hz | 3000 events |
| Ambient light | TMD3719 | on change | 100 events |
| Proximity | TMD3719 | on change | 100 events |

Uncalibrated variants exist for the magnetometer and the gyroscope, and **those are the
ones to prefer**. The bias comes back as a separate field, which is the difference between
a reading you can correct yourself and one that has already been corrected by something you
cannot inspect. That is rule R4 applied to a sensor rather than to a camera.

**The 3000 event FIFO is the enabling detail.** At 416 Hz that is seven seconds of hardware
buffering with the CPU asleep, which is what makes a sampled record cheap rather than a
thing that heats the phone. Card 59 established that the phone's power is worth caring
about; batching is how this stays compatible with that.

The fused sensors, gravity and linear acceleration and the three rotation vectors, are the
phone doing arithmetic you cannot check. Expose them, and **label them derived**, or the
split rule is broken quietly.

### 2.3 Audio

| | |
|---|---|
| Built-in microphone | `AUDIO_DEVICE_IN_BUILTIN_MIC`, positioned `@:bottom` |
| Rate | 48000 Hz native; 8000, 16000, 24000, 32000, 44100 also offered |
| Format | PCM float, 2 channels |

**A microphone is the fastest sampler on the device by three orders of magnitude.** The
gyroscope manages 416 Hz. The microphone manages 48000. Anything that makes a sound is
therefore timestamped far more precisely by listening than by watching: a relay closing, a
shutter, a stepper moving, a fan changing speed, a supply whining under load.

`AudioManager.getMicrophones()` returns a `MicrophoneInfo` per device carrying frequency
response, sensitivity, maximum and minimum SPL, and geometric position and orientation.
That is calibration data, and it is what separates "a recording" from "a measurement". A
level reported without it is a number in arbitrary units, and this project does not report
those.

**Audio costs a permission, and that is a decision and not a detail.** The manifest holds
no audio permission today. A camera on a desk and a camera on a desk that can also listen
are different objects, and the difference matters to whoever else is in the room. It should
be asked for explicitly, at the moment it is first used rather than at install, refusable
without breaking anything else, and the fact that a build can listen should be visible in
`/api/help` and in the notification. Nobody should discover it from a changelog.

## 3. The three shapes

Each one already has a counterpart in the code, which is the argument for it.

### 3.1 `/api/sensors` — what exists

The counterpart of `/api/cameras`. Every sensor, its vendor and part number, its range and
resolution, its rate limits, its FIFO depth, its power, and whether it is a hardware
reading or a fusion of others. Rule R6 applied to sensors: an agent learns the surface from
one request and does not have to know what a Pixel 6a is.

### 3.2 On the capture, not on "now"

The important one. Every capture's provenance grows an `environment` block holding the
samples that fell **inside that frame's own exposure window**, chosen by
`SENSOR_TIMESTAMP` and the exposure duration, with the count and the spread of each.

This is decision D8 extended, and it makes a whole class of fault self-diagnosing. A
blurred long exposure carries the vibration that blurred it. A tilted measurement carries
its own tilt. Two captures taken an hour apart carry the light level, the temperature and
the pressure that differed between them, so an argument about whether the subject changed
has evidence rather than opinion.

### 3.3 `/api/record` — sampling on a clock

The counterpart of `/api/burst`: name the sensors, the rate and the duration, get
timestamped rows back in one response. It uses the hardware FIFO, so a 400 Hz record of a
few seconds costs almost nothing.

For audio the same endpoint returns WAV, with the microphone's calibration in the record
beside it.

### 3.4 One clock, named

Every timestamp in the same base, the base named in the answer, and the offset to wall
clock recorded once per request rather than assumed. `SENSOR_TIMESTAMP` on a capture result
and the timestamp on an Android sensor event are both boot-based on a modern device, and
"both are boot-based" is a claim that must be checked on the device and reported, not
assumed from the documentation.

Without this section none of the rest is an instrument.

## 4. The phone is also a source

Worth stating on its own, because it is the part most likely to be missed.

The phone can **emit** as well as sense: the torch, the screen, and the speaker. An
instrument that can emit and measure at the same time is an active one, and pairing a
source with a sensor on one clock is the difference between an observation and an
experiment.

The torch is already a bench light. The screen is a controllable light source of known
geometry. The speaker plus the microphone is an active acoustic instrument. None of these
needs new hardware and none of them was designed for.

## 5. Novelty, and why it is a design goal

The agent should be able to find uses nobody planned. That is in obvious tension with a
project whose habit is to refuse rather than guess, so the resolution has to be explicit:

> **Refusal is about claims, not about curiosity.**

DeskCam refuses to report a number it cannot support. It must not refuse to let someone
look. The discipline belongs in **describing** a reading honestly, its units, its rate, its
jitter, its calibration state and its clock, and not in deciding in advance what a reading
is for. A barometer on a bench camera has no obvious use. That is an argument against
interpreting it, and not an argument against exposing it.

So the sensors are offered raw, timestamped, in stated units, with their calibration state
named, and the composition is left to the caller. Card 57 already built the composition:
a tape is a sequence nothing else can interrupt, which is exactly what an experiment needs.

Some things that fall out for free, none of which needs a feature of its own:

- The switching frequency of a supply, from its coil whine, while the torch drives its load
- A relay or a shutter timestamped to tens of microseconds and tied to the frame it belongs to
- The resonant frequency of a printed part, by tapping it
- A fan's speed from its tone, and the same fan's speed from a video, compared
- A panel's PWM frequency from the banding in one frame plus the rolling shutter skew
- Why a long exposure came out blurred, from the accelerometer during that exposure
- Whether the bench is level, and whether it stayed level between two sessions

The last one is the point in miniature. Nobody would build a feature for it. It falls out
of recording the conditions of every capture, and it turns an argument into a check.

## 6. What would make this bad

- **A single endpoint returning every current value.** That is a dump and not an
  instrument, and it is the same conflation as one rectangle serving as the crop, the
  focus region and the metering region, which card 60 had to undo.
- **A reading without its rate and its jitter.** The same fault as a sharpness value
  without its age, which card 9 went to some trouble to avoid.
- **Fused values presented as measurements.** A rotation vector is a model's opinion.
- **Calibrated-only sensors.** If the uncalibrated variant exists, the bias belongs to
  the caller.
- **Audio arriving quietly.** See section 2.3. This one is not a technical fault.
- **Anything that undoes card 59.** The camera now stops reading the sensor when nobody is
  asking. A sensor subsystem that polls continuously would put the heat straight back.

## 7. Open questions

- Is `SENSOR_TIMESTAMP` on this HAL the same base as `SensorEvent.timestamp`? Documented
  as boot-based for both, and worth **measuring** on the device before anything relies on
  it. A cross-check exists: flash the torch, watch the light sensor, compare.
- How much of the exposure window does a 416 Hz sensor actually cover at 1/1000 s? At some
  exposure the honest answer is "no sample fell inside this frame", and the block should
  say that rather than reach for the nearest one.
- Does the physical camera id change in practice on this device, or only in principle?
  Worth a card on its own either way, because the answer is either a fault or a fact.
- Should `/api/record` and a tape interleave, so a script can capture while recording?
  That is the first thing an experiment will ask for, and it is not free.
- Is a two-microphone cross-correlation worth anything at this geometry, or is the
  baseline too short to matter?

## 8. Order of work

1. **The camera metadata of 2.1.** Smallest, and the data is already arriving in every
   frame. It settles two open questions in existing cards and one possible fault.
2. **The clock check of section 7.** Nothing else is worth building until it is answered.
3. **`/api/sensors` and the capture `environment` block.**
4. **`/api/record`.**
5. **Audio**, deliberately last, because of the permission and not the difficulty.
