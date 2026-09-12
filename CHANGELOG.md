# Changelog

What changed in each release of DeskCam. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [semantic versioning](https://semver.org): `VERSION` at the top of the repository is the one number, the APK and the CLI both carry it, and each release is tagged `vX.Y.Z`. Until 1.0.0 the HTTP API may change in a minor release, and every such change is listed here.

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
