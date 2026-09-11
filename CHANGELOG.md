# Changelog

What changed in each release of DeskCam. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [semantic versioning](https://semver.org): `VERSION` at the top of the repository is the one number, the APK and the CLI both carry it, and each release is tagged `vX.Y.Z`. Until 1.0.0 the HTTP API may change in a minor release, and every such change is listed here.

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

[0.1.0]: https://github.com/mattjoyce/AndroidDeskcam/releases/tag/v0.1.0
