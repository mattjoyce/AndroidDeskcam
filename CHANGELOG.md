# Changelog

What changed in each release of DeskCam. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [semantic versioning](https://semver.org): `VERSION` at the top of the repository is the one number, the APK and the CLI both carry it, and each release is tagged `vX.Y.Z`. Until 1.0.0 the HTTP API may change in a minor release, and every such change is listed here.

## [Unreleased]

### Added

- `+` and `-` zoom the shared camera view in and out, including when the console has focus outside the camera panel. `=` also zooms in. Typing fields and Ctrl/Cmd browser shortcuts keep their normal behavior.

- The A4 bench mat sheets as print-ready vector PDFs, in `explainer/mats/`: three designs, hybrid, quiet and angles, in both orientations, with a `geometry.json` carrying each sheet's declared geometry for a future detector. `scripts/print-mats.mjs` regenerates them by driving a real Firefox over WebDriver BiDi, which adds no dependency, and prints with margins at zero and shrink-to-fit off so a millimetre in the artwork is a millimetre on the paper. Checked by rasterising each PDF at 600 dpi and decoding it back: all eight markers on all six sheets, with the ids that sheet declares, and marker edges measuring 9.95 to 9.99 mm against the 10 mm declared. The mat is a design study and is not part of the APK.
- A journal of every operation, kept by the CLI in `~/.local/state/deskcam/journal` or `DESKCAM_JOURNAL`. Every command that takes a picture or changes the camera is written down with who asked, what came back, and a copy of the thumbnail and the sidecar, wherever the files went. A refusal is journalled as fully as a capture, in the phone's own words. Reading the camera is not journalled. The newest 2,000 entries are kept, the access key is scrubbed, and a journal that cannot be written never fails a capture.
- An `asker` block in every sidecar: the directory, the project, the command as typed, the session and the reason. `--why "TEXT"` gives the reason, and `DESKCAM_PROJECT` and `DESKCAM_SESSION` name the project and the session when the directory and the environment cannot. A project is the nearest `.git` above the directory and is never guessed from a name.
- The console as the agent's seat: **Snap**, **Focus hunt**, **Measure**, **Normal** and **Shoot this again** run the CLI's own commands through the CLI's own code and are journalled as `via: console`.
- A log of recent commands in the console, as they were typed, newest first.
- `deskcam focus at FX,FY`: autofocus on a place in the picture you can see, as fractions from the left and the top, leaving the framing alone. The shared camera panel also supports double-tap focus.
- `deskcam mark at FX,FY[,FW,FH] [label=TEXT]`: point at a place in the picture without changing the camera. The shared camera panel also supports Shift-drag and Shift-click marks, labelled "look here".
- `deskcam log [N] [via=console|cli] [--json]` reads the journal, and `deskcam log wait [timeout=120]` returns when a person next does something at the console, with what they did as JSON, or exits 2. This is how a person signals an agent: the journal holds what everybody did, so the agent that reads it sees the mark that was drawn, in the sensor's coordinates, and any still that was taken for it.
- `op=NAME,...` on `deskcam log` and `deskcam log wait`, which narrows either to the operations named. Since the console aims (D20), a person lining a part up writes a `set` for every zoom and pan, and `log wait` returned on the first one. `deskcam log wait op=mark` returns on their mark.
- `deskcam mark list` prints every mark on the phone, and `deskcam mark clear [ID|all]` removes them. A mark drawn on the phone's own page never reaches the journal, so the list is the only way an agent sees it.
- The skill covers pointing in both directions, and names every CLI command, every endpoint and every parameter. A contract test holds it to that.
- The record of a capture slides in from the right of the console when a row is picked, and away again with the cross, the tab on the edge of the view, or the `i` key.

### Changed

- The console journals what its camera page does in the CLI's words, where it used the endpoint's. A mark is `mark` and a Clear is `unmark`, where both were `marks`, and a double tap is `focus`, where it was `af`. So a mark reads the same whichever way it was made, and `op=mark` does not wake for a Clear. Entries written before this keep their old names.
- Shift-drag and Shift-click replace annotations with a new “look here” mark. Ctrl-Shift keeps existing annotations and adds another. Reset all clears annotations after resetting the camera.

- The console embeds the APK's camera panel from one source file, with the same framing, focus, lighting and marking gestures. Camera requests pass through the console's authenticated proxy and changes enter its journal. Workstation capture operations and history remain beside the panel (D20). Shift-click on the phone now points; use Reset all to reset framing.

- The console's roll is the journal, grouped by project and then by session, where it was a listing of the directory `deskcam serve` was started in. On 2026-09-19, 99 of the 100 captures on the workstation were in 11 scratch directories agents had chosen, and the roll showed none of them. `/api/roll` now answers `groups` where it answered `captures`, and `/img/`, `/thumb/` and `/sidecar/` take a journal entry and a number where they took a file name. This changes the console's own routes; the phone's API is untouched.
- A live view nobody touches pauses after five minutes, where it paused after thirty seconds. The pause exists for a page left open overnight, and thirty seconds paused it on somebody watching with both hands on the work. Both pages.
- The type in the console is larger.

### Removed

- The console's framing controls: Full sensor, In, Out, Rotate, drag to frame, click to centre and the wheel, with the `/api/cam` route that forwarded any parameter to the phone. The console now embeds the shared bench tool instead of maintaining those controls separately (D20).

### Fixed

- **Shoot this again** takes the picture. It restored the settings and stopped, and it built them in the page's own JavaScript from six of the settings the CLI's `recall` knows. Both now run one function, and a recall checked against the phone sent fourteen.
- The `−` on an open panel in the console, which was drawn as the text `\2212`.

## [0.2.0] - 2026-09-12

### Added

- A level for the mount, on the phone: touch Level in the app's header. A bubble card whose range closes in from ten degrees to half a degree as you converge, the edge to lower named in words, and one tone per axis that beeps faster as that axis comes in and holds steady once it is there, so the mount can be set with both hands on the bracket and your eyes on it. It needs no camera and no running service, which is the state a reboot leaves the phone in.

### Changed

- `/api/status` reports three facts about the lens shading map where it reported one: `sensor.shading_map_supported` is now the judgement, and `sensor.shading_map_key_advertised` and `sensor.shading_map_seen` are the two measurements behind it. This is an API change.

### Fixed

- A mark on the bench page follows a reframe at once. `drawMarks` ran on the two second marks poll and on a window resize, and nothing redrew the overlay when the crop itself changed, so after a zoom or a pan every mark sat on the wrong part of the picture until the next tick. One was measured 333 px from the part it names, straight after a zoom.
- The live stream shows a reframe. `/api/stream` cropped every frame with the camera settings it read when the connection opened, so a zoom or a pan sent while somebody was watching never appeared on that connection at all. Measured at 6 and 12 fps: 256 and 516 frames after the change with nothing moving by more than the noise floor, while a connection opened immediately afterwards showed the new framing at once. The bench page appeared to follow slowly because its own watchdog restarts the stream every few seconds. `/api/stream` still refuses every parameter that would change the camera; it now shows the camera as it is rather than as it was. `frontend/streamlag.py` is the measurement, kept so it can be repeated.
- The lens shading map, which this phone delivers and the app said it did not. The Pixel 6a leaves the map out of its advertised capture result keys and then puts a full 25 by 33 RGGB map in every frame taken with the mode on, so `/api/status` reported `shading_map_supported: false` while `/api/shadingmap` was returning 3300 real gains. A map that is missing also gave one message for three different situations, and that message blamed the camera: it told you to go and measure a flat field by hand when all that had happened was that the mode was off.

## [0.1.0] - 2026-09-12

The first public release.

### Added

- Three ways to install: a published release, a home build installed by QR code from `deskcam serve`, and a home build installed with adb.
- `deskcam serve` shows an install code beside the pairing code. It hands out this clone's build when there is one and otherwise points at the latest release; `--apk FILE` or `--apk release` chooses.
- `deskcam version`, and the app shows its version beside its name.
- The app lets you choose which network's address it shows and reports. With a VPN such as Tailscale up, it used to report the VPN address.
- One Start/Stop button beside the status, blue only when pressing it would start the camera.
- Releases signed with a release key, with a SHA-256 checksum for every file and prebuilt CLI binaries for Linux and macOS.

### Security

- A pairing link asks before it changes the access key or starts the camera, and a link whose callback is not a console's pairing route on a private address is refused.
- The app no longer takes a token, port or autostart setting from other apps.
- No response carries `Access-Control-Allow-Origin`, so a web page in a browser on the network can no longer read the camera.

[0.2.0]: https://github.com/mattjoyce/AndroidDeskcam/releases/tag/v0.2.0
[0.1.0]: https://github.com/mattjoyce/AndroidDeskcam/releases/tag/v0.1.0
