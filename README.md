# DeskCam: gives vision to AI agents

DeskCam turns a spare Android phone into eyes an agent can use. It asks for a picture of a real thing in one shell command, reads the file, and says what it sees. You drive it the same way yourself, from the command line or from a shell script. It is built for a Google Pixel 6a (or compatible Camera2 device) running GrapheneOS or stock Android.

**Most of the work is looking.** Read a handwritten note, see whether a first layer went down flat, check that a connector is seated, inspect a board, evaluate a display, look at a painting. When the question wants a number rather than a description, the same camera measures, and refuses rather than guessing when it cannot support one.

Each camera control is an HTTP GET or POST request. You can control the camera from `curl`, from a Python script, or from a coding agent with zero external client libraries.

```sh
deskcam snap zoom=6 cx=0.32 cy=0.68 torch=25 focusm=0.12
```

## The manual

The full manual is in [docs/manual/](docs/manual/README.md). It is written for the person at the bench and for the agent at the shell alike.

| Page | Read it for |
|---|---|
| [Setting up](docs/manual/setup.md) | Installing, pairing, the access key, a second machine, USB or Wi-Fi |
| [The console and the camera page](docs/manual/console.md) | Every control, gesture and key in the browser, the journal, and the level |
| [The CLI day to day](docs/manual/cli.md) | Framing, focus, exposure, light, the kinds of capture, where files go |
| [Working with an agent](docs/manual/agents.md) | The skill, pointing at things both ways, waiting for a person, tapes |
| [Capture techniques](docs/manual/techniques.md) | Notes and paintings, boards, screens, focus stacks, bursts, RAW |
| [Measuring](docs/manual/measuring.md) | The noise floor, scale, distance, the mat, linearity, and why tools refuse |
| [Troubleshooting](docs/manual/troubleshooting.md) | Symptom, meaning, fix |
| [How it works](docs/manual/how-it-works.md) | Why the crop is in software, dioptres, focus hunting, idling, heat |
| [Reference](docs/manual/reference.md) | Every command, endpoint, parameter, verb, header, exit code and sidecar key |

The agent's own manual is [skill/SKILL.md](skill/SKILL.md). Why things are built the way they are is in [docs/DECISIONS.md](docs/DECISIONS.md). For an interactive walk-through of the sensor crop, dioptres and display flicker, open [explainer/index.html](explainer/index.html).

---

## Trust and architecture at a glance

What you are trusting when you run this, and where to check each claim:

* **Zero Gradle, Zero Android Studio, Zero AGP**: The phone app compiles directly with Android SDK build tools (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`) in under 5 seconds. No Gradle daemon, no downloading unknown Maven plugins or transitive dependencies.
* **One Go Dependency**: The workstation CLI compiles to a single static binary. Its only module outside the standard library is `rsc.io/qr` v0.2.0, for the pairing QR code, pinned in `go.sum`.
* **Zero Cloud Calls or Telemetry**: The Android app talks only to your LAN or to an `adb forward` USB loopback, and the CLI talks only to the phone. The one internet address anywhere is the release download that `deskcam serve` puts in its install code, and the phone fetches it only when you scan that code.
* **One HTTP Seam, Contract-Tested**: The HTTP interface is the only seam between the phone and the workstation. A Python test suite (`frontend/test_contract.py`) holds the parameter tables, endpoint lists, CLI reference, and enum values in these documents equal to the code they describe. It reads the source as text; `frontend/surface.py` is the check against a running phone.

**There is no HTTPS, and there is no authentication unless you set a token.** The phone serves plain HTTP on port 8080 to anyone on the same network and to every app on the phone, and that open state is the default (`Access.java`). This is a bench tool for a trusted LAN. If the network is shared, set a token (see [Require an access key](docs/manual/setup.md#require-an-access-key)). If you need encryption, or reach from outside the LAN, put the phone on a WireGuard or Tailscale network and keep the server on plain HTTP behind it. A self-signed certificate would only add `-k` to every request.

Releases are signed with the DeskCam release key and list a SHA-256 checksum for every file. The release certificate's SHA-256 fingerprint is:

```
638810f09d75a8f2cdd9c85c9139bae01f67f7f8b6095c6d06de5c2b1beca6a9
```

That is the form `apksigner` prints. AppVerifier and `keytool` show the same digest as `63:88:10:F0:9D:75:A8:F2:CD:D9:C8:5C:91:39:BA:E0:1F:67:F7:F8:B6:09:5C:6D:06:DE:5C:2B:1B:EC:A6:A9`.

Check an APK before you install it with `apksigner verify --print-certs deskcam.apk`, or on GrapheneOS with AppVerifier. There is no reproducible build yet, so a release is the maintainer's statement that it was built from the tagged source; building it yourself is the only independent check.

A home build signs with a key that `backend/build.sh` generates on first run: `backend/deskcam.keystore`, gitignored, password `deskcam`. Keep it: `git clean -x` deletes it, and an APK signed with a different key will not install over the old one until you uninstall the app, which clears its settings. For the same reason a release and a home build do not install over each other.

| Component | Runs On | Technology | Purpose |
|---|---|---|---|
| `backend/` | Android Phone | Pure Java (SDK API 33–37) | Foreground camera service, `HttpServer`, raw sensor readout, software ROI crop |
| `frontend/go/` | Workstation | Static Go binary | `deskcam` CLI, browser workbench console, USB/Wi-Fi pairing |
| `frontend/analysis/` | Workstation | Python 3.11 (`numpy`, `pillow`) | Optional measurement tools (linearity, scale, HDR, focus stacking) |
| `explainer/` | Browser | Static HTML / CSS / JS | Interactive visual guide to optics, PWM synchronization, and API mechanics |
| `skill/` | Workstation | Claude Code Skill | Agent integration definition and tool calling instructions |
| `docs/` | Workstation | Markdown | Architecture decisions, the decisions and their reasoning, and DSL documentation |

---

## Quick start

You need an Android 13 or newer phone with a Camera2 `LEVEL_FULL` camera (DeskCam is built on a Pixel 6a) and a Linux or macOS workstation. This is the release route. [Setting up](docs/manual/setup.md) also covers building it yourself and installing with adb.

1. Download the CLI for your workstation from the [latest release](https://github.com/mattjoyce/AndroidDeskcam/releases/latest) and put it on your path. The files are `deskcam-linux-amd64`, `deskcam-linux-arm64`, `deskcam-darwin-amd64` and `deskcam-darwin-arm64`, and `SHA256SUMS` lists their checksums.
   ```sh
   mkdir -p ~/.local/bin
   curl -L -o ~/.local/bin/deskcam https://github.com/mattjoyce/AndroidDeskcam/releases/latest/download/deskcam-linux-amd64
   chmod +x ~/.local/bin/deskcam
   ```
2. Run `deskcam serve`, open `http://127.0.0.1:9000` in a browser, and click **Pair**.
3. Scan the install code with the phone's camera, and allow the browser to install the app.
4. Open DeskCam on the phone, accept its permission prompts, and tap **Start**.
5. Scan the pairing code, and tap **Pair** on the phone.

Then:

```sh
deskcam show         # one line of live state: proves the connection
deskcam snap         # prints the path of a full-resolution still, with a .json sidecar beside it
```

To give Claude Code the camera, link the skill: `ln -sf "$PWD/skill" ~/.claude/skills/deskcam`.

---

## Versions and releases

DeskCam uses [semantic versioning](https://semver.org). `VERSION` at the top of the repository is the one number: `build.sh` puts it in the APK, the CLI prints it with `deskcam version`, and the app shows it beside its name. A release is tagged `vX.Y.Z`, and [CHANGELOG.md](CHANGELOG.md) lists what changed. Until 1.0.0 the HTTP API may change in a minor release, and every such change is in the changelog.

---

## Development and tests

### Workstation test suite

Runs ruff linting, ruff formatting check, mypy type verification, bandit security analysis, pytest unit tests (including parameter contract integrity checks), and Go unit tests:

```sh
./frontend/check.sh
```

### Backend unit tests

The backend includes pure Java unit tests (`backend/test/dev/deskcam/Tests.java`) covering coordinate rotation geometry, exposure string parsing, tar archive packing, sharpness calculations, and thermal ladders. These execute on the host JVM during every build before packaging the APK:

```sh
./backend/build.sh
```

### Camera page tests

The APK and console both serve `backend/app/assets/panel.html`. The console embeds it via
the local Go module in `backend/app`, so an ordinary Go build includes the same source
without copying it. Edit its HTML, CSS and JavaScript directly;
`backend/build.sh` packages that file as an asset. `WebUi.java` keeps the API help and asset
loader. There is no frontend bundler or production JavaScript dependency.

With Node.js 18 or newer, the build also runs deterministic tests of the panel script:

```sh
node --test backend/test/webui/*.test.mjs
```

These exercise authentication, command ordering, timeouts and delayed responses with a
controlled DOM and network. They do not replace a browser or phone check. Builds without
Node.js print an explicit skip; run these tests before merging panel changes.

The shared panel and console can be checked together without a phone using
`node backend/test/webui/shared-console-smoke.mjs` (Go, Node 22 and Firefox required).
It builds a temporary console and checks authenticated controls and pointing gestures
against a simulated phone, plus direct access to the panel.

### Surface parity check against a live phone

To verify that an internal refactoring introduces zero behavioral drift against a live camera:

```sh
# Record all endpoint schemas and status codes before a change
frontend/surface.py record /tmp/before

# Rebuild and install
./backend/build.sh && adb install -r -g backend/build/deskcam.apk

# Record after and compare
frontend/surface.py record /tmp/after
frontend/surface.py compare /tmp/before /tmp/after
```

---

## License

DeskCam is open-source software released under the [MIT License](LICENSE).
