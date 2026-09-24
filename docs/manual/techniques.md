# Capture techniques

Recipes by subject. Most of the time the job is to look at something and say what is there,
and the camera's normal pipeline is the right tool for that. The measurement settings belong
to [Measuring](measuring.md).

## Notes, pages, paintings, prints, plants

- **Leave the pipeline alone.** `measure=1` switches off the tone curve, noise reduction and
  sharpening, which are what make a picture legible. Never use it to read a note or look at a
  painting.
- **Fill the frame by moving the camera, not by zooming.** Zoom is a crop, so a note that
  fills the frame at zoom 1 carries far more detail than the same note cropped to at zoom 4.
- **Use `snap` to read small writing.** `frame` is preview-sized and loses faint pencil.
- **The torch glares.** Ink, varnish, wet paint, a photo in a sleeve and a glossy page all
  throw the LED straight back. If a bright patch washes out part of the picture, turn the
  torch off and use the room, or put a lamp off to one side.
- **Raking light shows relief.** Brush strokes, paper grain, an embossed seal or a scratch
  show up under light from one side at a shallow angle, and vanish under flat light.
- **Square up anything with lines.** Text and edges stay square when the camera looks
  straight down. `deskcam status` reports the tilt, and [the level](console.md#levelling-the-mount)
  sets it.
- **Lock the white balance before comparing colour.** Two captures on auto white balance can
  differ in colour for no reason but the camera changing its mind.
  `deskcam set awb=daylight awblock=on`.

## Circuit boards and small parts

```sh
deskcam set focusm=0.12 torch=30          # focus at 12 cm, LED at 30 of 45
deskcam snap zoom=5 cx=0.32 cy=0.68 -o u4.jpg
```

The camera focuses down to 98 mm. There one pixel covers about 30 micrometres, which is
enough to read silkscreen and find a part, and not enough to see a solder fillet. That figure
is arithmetic from the sensor size and the stated minimum focus distance, not a measurement.
It is an optical limit, and a clip-on macro lens is the fix.

**To focus on one part of a wide picture, name it instead of zooming to it:**

```sh
deskcam set exposure=1/33 iso=200 focusbox=0.35,0.35,0.15,0.15
deskcam focus hunt
deskcam snap                              # the whole frame, sharp on the box
```

The hunt refuses, and puts the focus back, when the box holds nothing to focus on, such as a
glossy black surface.

If the phone hangs upside down from an arm, `deskcam set rotate=180` once and it stays.

## Screens and displays

LED and OLED panels flicker faster than the eye can see, and the camera reads the frame one
row at a time, so different rows catch different parts of the flicker and you get dark bands.
**Set the exposure to a whole number of flicker periods:**

```sh
deskcam set exposure=1/60 iso=200 awb=daylight awblock=on
deskcam snap -o display.jpg
```

For a 60 Hz panel one period is 1/60 s; for 240 Hz it is 1/240 s. Try `1/60`, `1/30` and
`1/120` and keep whichever shows no bands. Lock the white balance too, or the colour drifts
between shots. The camera page has a **1/60 anti-flicker** button for this.

A lit screen in a dark bezel is too much range for one exposure. Take a bracket instead,
stepping in whole periods:

```sh
deskcam bracket base=1/240 stops=4 iso=56 measure=1
deskcam analyse hdr DIR
```

Each frame records `exposure_ns`, `base_periods` and `period_error`, because the sensor does
not deliver exactly what was asked. `analyse hdr` merges on those measured exposures, and
refuses a bracket taken without `measure=1`, because `value / exposure` is only radiance when
the response is linear.

## Everything sharp at once: focus stacking

Close up, depth of field is a fraction of a millimetre. Take a still at each of several lens
positions and blend them:

```sh
deskcam focussweep -o stack/ from=3 to=6 steps=7
deskcam analyse stack stack/
```

The steps are equal in dioptres, which is equal in depth of field.
[How it works](how-it-works.md#why-focus-is-stepped-in-dioptres) says why. Without `-o` the
directory is `deskcam-sweep-DATE-TIME` in your shots directory. The near end of the lens is
about 10.2 dioptres on the Pixel 6a, so `from=3 to=6` covers only part of the range.

`analyse stack` corrects focus breathing between frames, and refuses when one frame is
sharpest over most of the picture: that subject already fits in one depth of field, and
blending could only blur it.

To find the one sharpest position rather than a stack, use `deskcam focus hunt`.

## Less noise: averaging a burst

```sh
deskcam burst 16 exposure=1/60 iso=200
deskcam analyse average DIR               # one 16-bit image
deskcam analyse burst-noise DIR           # how much the averaging bought
```

Averaging N frames lowers the noise by about the square root of N. `average` writes 16 bits,
because averaging 16 frames buys two bits of precision that 8 bits would throw away.
`burst-noise` measures the improvement rather than assuming it.

Set the exposure a little dark for a burst. A clipped highlight cannot be recovered, and the
average brings the shadows back. Check the frame count before you average: a burst can come
back short, and the CLI says so on stderr.

## Seeing what one setting does: a walk

```sh
deskcam walk vary=torch values=0,10,20,45
```

One still at each value, everything else held still. You choose the values. Focus and
exposure have step rules of their own, in `focussweep` and `bracket`.

## RAW

```sh
deskcam raw -o sensor.dng exposure=1/120 iso=56
```

The DNG carries the whole 12-megapixel Bayer array, the black and white levels (64 and 1023
on the Pixel 6a), the colour matrices and the illuminant. `rawpy`, `dcraw` and `darktable`
read it. Zoom and pan do not apply to it; the crop you asked for is in the `X-DeskCam-ROI`
header. `deskcam cameras` lists `raw` among a camera's capabilities when it can do this.

## Watching something change

```sh
deskcam stream n=60 fps=5 -o watch.mjpeg
```

A stream is only a view. It takes `fps`, `n`, `w`, `h` and `jpegq`, and refuses anything that
would change the camera. On a hot phone it slows down and says so on every part; see
[Troubleshooting](troubleshooting.md#the-phone-is-hot).
