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

All SVG coordinates are millimetres. The print size is 210 × 297 mm, A4 portrait.
The main field starts at page position (30, 50) and is 150 × 200 mm. A01 is the
upper-left 25 mm cell. Human x/y coordinates are relative to this field, x right and
y down. The page geometry exposed as `window.DeskcamMat` uses page coordinates.

Coded marker black squares are 16 × 16 mm, surrounded by 3 mm of white. The centres:

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

The horizontal check runs from (55, 285) to (155, 285). The vertical check runs from
(18, 150) to (18, 250). Each spans 100 mm between endpoint tick centres and has 20 equal
5 mm intervals. There is no 1 mm rule mixed into the sheet.

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
settles line contrast, marker size, usable field size and object occlusion. After that,
make a fixed vector PDF of the selected design, preserve its geometry manifest, and
remove the prototype-only controls. No PDF or detector is delivered in this step.

A follow-up inspection exercised all six layout/reference combinations, switching and
reloading the URL, optional interior cell labels, object previews, stronger grid and
hover coordinates. A point at field (60, 87.5) reported C04. At a 390 px viewport the
page had no horizontal overflow. Print CSS hid sample objects, highlights and the
switcher. All six combinations ran without JavaScript page errors.
