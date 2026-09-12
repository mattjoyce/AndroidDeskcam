# A4 bench mat: design study, not a released calibration target

Question: what should a home-printed A4 sheet contribute when objects are placed on it
and viewed by DeskCam? The user already uses a 5 mm grid and sometimes puts Tetris
shapes in the corners to help orientation.

Open the self-contained mockup:

```sh
xdg-open explainer/prototype-bench-mat.html
```

It lives beside the existing explainer because it is a separate physical design study,
not a new APK panel route. No server, bundler or runtime dependency is needed.

## Starting recommendation

Use the hybrid: 5 mm light lines, stronger 25 mm lines, A–F / 01–08 cell addresses,
and identifiable landmarks outside the area normally covered by objects. This keeps
size, location and orientation as separate visual jobs. The hybrid also has optional small addresses inside each major cell, so a close crop
can retain a nearby human-readable location. These labels can be obscured and are not
a substitute for a coded landmark. The heavy lines help a human
count; they are not the sole scale reference for software.

The alternatives answer different needs:

- `?variant=hybrid`: everyday placement, scale context and human-to-agent addresses.
- `?variant=quiet`: perimeter ticks and a nearly empty centre for examining outlines.
- `?variant=angles`: a centred alignment station, with 10-degree ticks and 30-degree
  labels. Radius labels are in millimetres. Zero degrees is up, increasing clockwise.

The angle dial is an alternative because an object can hide its origin, and angles
read from a tilted camera image differ from angles in the paper plane. An angle tool
should first map the view to that plane. A full fan of spokes would add background
edges without helping most component-inspection tasks.

All versions have a reference selector. Add `&references=tetris` to a variant URL to
show the user's corner-shape idea. The other mode is `references=coded` (default).
The switcher and reference choice are retained in the URL. Example objects, stronger
fine grid and hover coordinates are inspection controls; sample objects and highlights
are never printed.

## Tetris shapes and coded markers

Four different corner shapes break the grid's rotational symmetry. Here the shape
identities are L at top-left, T at top-right, Z at bottom-right and O at bottom-left.
L and T have distinctive orientations; Z has half-turn symmetry and O quarter-turn
symmetry. A recogniser should use the known arrangement and refuse ambiguous partial
views, rather than assuming every individual shape tells the rotation.

The shapes use four 5 mm square cells each. No Tetris recogniser is implemented. A
future implementation would need shape templates or contour recognition, handling of
blur, perspective, occlusion and rejection of unrelated shapes.

The coded option uses actual ArUco DICT_4X4_50 IDs 0–7. Existing detectors provide
identities and corners. A known board layout supports combining visible markers,
including partially visible boards; OpenCV documents this in its
[board tutorial](https://docs.opencv.org/4.x/db/da9/tutorial_aruco_board_detection.html).
Marker size here is a proposal, not a measured detection guarantee. Keep several
well-separated landmarks visible; a close crop containing only repeating grid cells
cannot independently establish its absolute position on the sheet.

## Physical geometry

All SVG coordinates are millimetres. Both orientations are A4, and each is its own sheet
family rather than a rotation of the other.

| Sheet | Page, mm | Working field, mm | Field origin on page | Marker ids |
|---|---|---|---|---|
| Landscape (`DCM-02-L`) | 297 × 210 | 225 × 125 | (36, 43) | 8–15 |
| Portrait (`DCM-01`) | 210 × 297 | 150 × 200 | (30, 50) | 0–7 |

A01 is the upper-left 25 mm cell, and a cell address carries its sheet, so `L-C04` and
`P-C04` are different places on different paper. Human x/y coordinates are relative to the
working field, x right and y down. The geometry exposed as `window.DeskcamMat` uses page
coordinates, and `explainer/mats/geometry.json` is that object for every printed sheet.

Coded marker black squares are 10 × 10 mm, surrounded by 3 mm of white. One number sets
the marker size, and the quiet zone and the label offsets follow it. 10 mm is the floor for
the binding case, a live preview with the sheet filling half the frame, where the original
16 mm was about twice what was needed. Each family owning an id block means a single
decoded marker names the sheet and its orientation, and therefore the marker size and grid
pitch it should have. That also catches a wrong-scale print. Measure a marker and compare
it with the 10 mm its id block declares.

Landscape marker centres, in page millimetres:

| ID | x, mm | y, mm |
|---|---:|---:|
| 8 | 18 | 24 |
| 9 | 105 | 24 |
| 10 | 279 | 24 |
| 11 | 192 | 24 |
| 12 | 279 | 185 |
| 13 | 192 | 185 |
| 14 | 18 | 185 |
| 15 | 105 | 185 |

Portrait marker centres, in page millimetres:

| ID | x, mm | y, mm |
|---|---:|---:|
| 0 | 18 | 26 |
| 1 | 105 | 26 |
| 2 | 192 | 26 |
| 3 | 192 | 125 |
| 4 | 192 | 268 |
| 5 | 105 | 268 |
| 6 | 18 | 268 |
| 7 | 18 | 125 |

Tetris mode uses only positions 0, 2, 4 and 6, with shapes centred there. Its largest
shape dimension is 15 mm. The state object distinguishes the displayed references;
these shapes must never be passed to an ArUco detector as if they were coded markers.

Each sheet carries two 100 mm check bars, spanning 100 mm between endpoint tick centres
with 20 equal 5 mm intervals. The horizontal one is centred across the page and sits 12 mm
off the bottom edge; the vertical one is at x = 18 on both sheets. So on portrait the
horizontal bar runs from (55, 285) to (155, 285) and the vertical from (18, 150) to
(18, 250), and on landscape they run from (98.5, 198) to (198.5, 198) and from (18, 65) to
(18, 165). There is no 1 mm rule mixed into the sheet.

Normal fine grid: 0.14 mm stroke, #b9b9b9. Stronger fine grid: 0.22 mm, #939393.
Major grid: 0.28 mm, #777777. Reference ticks: 0.28 mm, #111111. These are nominal
artwork values to evaluate on the actual printer, not accuracy specifications.

## Measurement and printing limits

The current `frontend/analysis/scale.py` uses periodic profiles and already documents
confusion between different reference pitches. Select a reference strip explicitly
and specify 5 mm pitch when trying this mat with that tool. Prefer a full-resolution
capture: a small preview crop might not contain enough pixels for its strip search.
No claim is made that the existing tool automatically recognises the sheet.

A future mat recogniser could use marker corners and known sheet geometry to estimate
a paper-plane mapping, then report field coordinates. Lens distortion needs separate
handling. See [OpenCV's homography explanation](https://docs.opencv.org/4.x/d9/dab/tutorial_homography.html).
A dedicated [ChArUco calibration sheet](https://docs.opencv.org/4.x/da/d13/tutorial_aruco_calibration.html)
is a separate tool; putting a dense calibration board under the object is not the
same design problem as making a useful working surface.

Paper-plane measurements do not automatically measure elevated object surfaces. As a
simple pinhole example, a top surface 20 mm above a sheet viewed from 300 mm above it
has magnification 300/280, about 7.1% greater. A printed background does not remove
that height effect. Focus should also target the object rather than a crisp marker.

Print at 100%, A4 portrait, no browser headers/footers, and no fit-to-page scaling.
Verify both 100 mm checks with a physical ruler, and keep the sheet flat. The artwork
keeps ink approximately 10 mm inside the page boundary; individual printers still
have their own printable areas. [Adobe describes actual-size scaling here](https://helpx.adobe.com/acrobat/desktop/print-documents/set-up-and-print-pdfs/page-size.html).
Grey ink is not a calibrated reflectance or colour target.

## Work completed and decision still open

The mockup was opened in Chromium in all three layouts and both reference modes,
including a narrow viewport. Print-media inspection confirmed A4 dimensions and that
the surrounding controls and example objects are hidden. There were no JavaScript
page errors. OpenCV decoded IDs 0–7 from rendered screenshots of all three coded
layouts. This only checks the digital artwork, not a photographed home print.

Patterns were generated with OpenCV 5.0.0 in a temporary environment, using
`cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)` and
`cv2.aruco.generateImageMarker(dictionary, id, 6)`. The resulting six-by-six bit rows,
including the black border, are embedded in the HTML and drawn as vector paths.
OpenCV is not a dependency of the mockup or APK.

Pending: the user chooses a layout/reference style, then a real print and camera trial
settles line contrast, marker size, usable field size and object occlusion. The
prototype-only controls stay in the page, because it is still the design tool, and no
detector is delivered.

The fixed vector PDFs and the geometry manifest now exist, described below. They were made
before the layout decision rather than after it, so all three designs are printable and
the choice can be made with paper on the bench instead of with a screen.

A follow-up inspection exercised all six layout/reference combinations, switching and
reloading the URL, optional interior cell labels, object previews, stronger grid and
hover coordinates. A point at field (60, 87.5) reported C04. At a 390 px viewport the
page had no horizontal overflow. Print CSS hid sample objects, highlights and the
switcher. All six combinations ran without JavaScript page errors.

## Printable sheets

`explainer/mats/` holds the six sheets as vector PDF, three designs in both orientations,
with `geometry.json` carrying each one's `window.DeskcamMat` so a detector can read what
the artwork declared instead of measuring it off a render. Print at 100%, no fit-to-page,
no browser headers or footers, then check both 100 mm bars with a ruler.

Regenerate them after any change to the artwork:

```sh
node scripts/print-mats.mjs                      # the six coded sheets
node scripts/print-mats.mjs --references tetris  # the corner-shape alternative
```

That drives a real Firefox over WebDriver BiDi through `backend/test/webui/bidi.mjs` and
prints the page the way the browser's own print dialogue would, with margins at zero and
shrink-to-fit off, so a millimetre in the artwork stays a millimetre on the paper. It adds
no dependency, and nothing in the script names A4: the page decides the paper, and the
script refuses to save a sheet whose page or reference mode is not the one asked for.

**What the PDFs measure**, by rasterising each at 600 dpi and decoding it back with
OpenCV 5's `DICT_4X4_50` detector:

- All eight markers decode on all six sheets, with the ids that sheet declares. Portrait
  returns 0 to 7 and landscape 8 to 15, so one marker names the sheet.
- Marker edges measure 9.95 to 9.99 mm against the 10 mm declared, so the artwork's scale
  survives the print path to about 0.1%.
- Each file is one page, and the page box is 0.30 mm over A4 on its short axis. That is
  Firefox rounding the box to whole points, 596 against A4's 595.28. It is the container,
  not the ink, and the ink is the line above.
- The quiet variant raises far more marker-sized candidate quads than the others, 1260 and
  1369 against 4 to 6, all correctly rejected. Its perimeter ticks are a periodic texture
  competing with the markers, the same effect the 25 mm grid showed in real ink, louder
  here because a 600 dpi render has no paper or lens in the way.

This checks the vector artwork and the print path, not a photographed home print. The
60% scaled sheet has still never been under the camera.

**Open, and worth settling before a reprint.** The two families report their revision
inconsistently: landscape says `DCM-02-L` and portrait says `DCM-01`, which is also the
name on the stale 16 mm sheet already on the bench. A decoded portrait marker therefore
cannot tell a fresh print from the old one, which is the thing the id blocks and prefixed
cell addresses were added to fix.
