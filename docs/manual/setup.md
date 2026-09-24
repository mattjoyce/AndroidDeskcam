# Setting up

Getting the app onto the phone, the CLI onto the workstation, and the two talking. Then the
things that change later: a key, a second machine, a cable, a new network address.

You need an Android 13 or newer phone with a Camera2 `LEVEL_FULL` camera. DeskCam is built
and measured on a Pixel 6a. The workstation is Linux or macOS.

## Install

There are three routes, and they end in the same place.

| Route | You need | The app comes from |
|---|---|---|
| 1. Release | a phone and a computer | the latest GitHub release, by QR code |
| 2. Build it, install by QR | Go, JDK 17, `zip`, the Android SDK | your own build, by QR code from your workstation |
| 3. Build it, install with adb | route 2's tools, USB debugging | your own build, over USB |

### Route 1: the release

1. Download the CLI for your workstation from the
   [latest release](https://github.com/mattjoyce/AndroidDeskcam/releases/latest) and put it
   on your path. The files are `deskcam-linux-amd64`, `deskcam-linux-arm64`,
   `deskcam-darwin-amd64` and `deskcam-darwin-arm64`, and `SHA256SUMS` lists each one's
   checksum.
   ```sh
   mkdir -p ~/.local/bin
   curl -L -o ~/.local/bin/deskcam https://github.com/mattjoyce/AndroidDeskcam/releases/latest/download/deskcam-linux-amd64
   chmod +x ~/.local/bin/deskcam
   ```
2. Start the console, open `http://127.0.0.1:9000` in a browser on the workstation, and
   click **Pair**:
   ```sh
   deskcam serve
   ```
   The dialog shows two codes. With no build of your own, the install code points at the
   latest release.
3. Scan the install code with the phone's camera. The APK downloads from github.com over
   HTTPS. Let the browser install unknown apps when Android asks.
4. Open DeskCam, accept its permission prompts, and tap **Start**.
5. Scan the pairing code. The phone shows what the link will do and waits; tap **Pair**. The
   workstation's `deskcam` now points at the phone.

### Route 2: build it, install by QR

1. Install Go 1.22 or newer, JDK 17 or newer, `zip`, and the Android SDK
   [command-line tools](https://developer.android.com/studio#command-line-tools-only). With
   `sdkmanager`, install the platform and build-tools versions named at the top of
   `backend/build.sh`. The script looks in `~/Android/Sdk` unless you set `ANDROID_HOME`,
   `PLATFORM` and `BT_VER`.
2. Build both halves:
   ```sh
   git clone https://github.com/mattjoyce/AndroidDeskcam.git && cd AndroidDeskcam
   ./backend/build.sh                                   # the APK, in about 5 seconds, no Gradle
   cd frontend/go && go build -o deskcam . && cd ../..
   mkdir -p ~/.local/bin && ln -sf "$PWD/frontend/go/deskcam" ~/.local/bin/deskcam
   ```
   Neither step needs a phone. `build.sh` runs the backend unit tests on the workstation
   JVM before it packages the APK. The first `go build` downloads the one Go dependency.
3. Run `deskcam serve`. It finds `backend/build/deskcam.apk` and hands it out itself, so the
   install code points at your workstation. Then follow route 1 from step 3.

The app then travels over plain HTTP on your LAN. Some browsers refuse plain HTTP
downloads; GrapheneOS's Vanadium did during development. If yours does, use route 3, or
`deskcam serve --apk release` to hand out the release instead.

### Route 3: build it, install with adb

1. Build as in route 2, steps 1 and 2.
2. Turn on USB debugging, plug the phone in, and install. `-g` grants the camera, local
   network and notification permissions at install time, so no dialog appears:
   ```sh
   adb install -r -g backend/build/deskcam.apk
   deskcam start
   ```
   An install stops the app, and the service does not come back by itself, so
   `deskcam start` after every install.
3. Connect with `deskcam usb`, which forwards the phone's port over the cable, or
   `deskcam wifi`, which reads the phone's Wi-Fi address through adb. Or pair as in route 1,
   step 5.

### Signing and verifying

Releases are signed with the DeskCam release key and list a SHA-256 checksum for every file.
The certificate's SHA-256 fingerprint is in the [README](../../README.md#trust-and-architecture-at-a-glance).
Check an APK before installing it with `apksigner verify --print-certs deskcam.apk`, or on
GrapheneOS with AppVerifier.

A home build signs with `backend/deskcam.keystore`, which `build.sh` generates on first run
(gitignored, password `deskcam`). Keep it. `git clean -x` deletes it, and an APK signed with
a different key will not install over the old one until you uninstall the app, which clears
its settings. For the same reason a release and a home build do not install over each other.

## The first capture

```sh
deskcam show         # one line of live state, which proves the connection
deskcam snap
```

`snap` prints the path it wrote and nothing else:

```
/home/you/bench/deskcam-20260911-061500.jpg
```

Without `-o` the file goes into `DESKCAM_SHOTS`, or the current directory when that is unset.
Beside it is `deskcam-20260911-061500.json`, the sidecar: what was asked for, what the sensor
measured for that frame, what the pipeline applied, and which way the phone was pointing.
[The CLI day to day](cli.md) goes on from here.

## The phone app

The app is a status screen for the camera service. The camera itself is driven over HTTP.

| On screen | What it does |
|---|---|
| **Start** / **Stop** | Starts or stops the camera service. Blue only when pressing it would start the camera |
| The address, and the network chip beside it (**Wi-Fi ▾**) | The address the phone serves on. With a VPN such as Tailscale up, tap the chip to choose which network's address it shows and reports |
| **Level** | A bubble level for the mount. See [Levelling the mount](console.md#levelling-the-mount) |
| **Setup** | The port (8080), the access key (blank means open), and whether to try starting after a reboot |
| The counters | Clients, streams, captures and errors since the service started, and the camera's state |
| **REQUESTS** | The last requests the phone answered |

**After a reboot, tap the app once.** Android does not let a camera app start itself, and
the **Try to start after a reboot** box usually loses that argument. `deskcam start` does
the same over adb.

## Choosing how to reach the phone

The CLI remembers one target, and every command uses it.

| Command | Use it when |
|---|---|
| `deskcam which` | You want to know where the CLI is pointing |
| `deskcam use http://192.168.86.120:8080` | You know the address |
| `deskcam wifi` | The phone is on USB and you want its Wi-Fi address |
| `deskcam usb` | The network is the problem. Tunnels over the cable, to `127.0.0.1` |
| `deskcam start`, `deskcam stop` | Starting or stopping the service over adb |

With two phones on adb, name one with `DESKCAM_SERIAL`. `DESKCAM_URL` overrides the saved
target for one shell.

## Require an access key

**There is no HTTPS.** A key decides who may use the camera. It encrypts nothing, and it
crosses the network in the clear with every request. By default the camera is open: anyone
on the network, and any app on the phone, can control it and take captures. That default is
for a trusted bench LAN. For encryption, or reach from outside the LAN, put the phone on a
WireGuard or Tailscale network and keep plain HTTP behind it.

To set a key:

1. `deskcam serve`, then open `http://127.0.0.1:9000` and click **Pair**.
2. Click **New key**. The console makes the key and stores it in `~/.config/deskcam/token`,
   mode 0600. `deskcam token new` does the same from the shell.
3. Scan the pairing code with the phone. It carries the key, so the phone stores it exactly.
   Tap **Pair**.

From then on every request needs `?token=KEY` or an `Authorization: Bearer KEY` header. The
CLI adds it for you, and so does the console's camera page. The phone's own page needs the
key in its address, `http://PHONE:8080/?token=KEY`. `deskcam open` does not add it.

`deskcam token show` prints the key. **Remove key** in the Pair dialog, or
`deskcam token clear`, removes it here; pair again to remove it from the phone, which opens
the camera to the network again.

**Accept only a code your own console shows.** A pairing link can set or clear the key and
makes the phone report its address, so the phone says what a link will do and waits for
**Pair**. It refuses outright a link whose callback is not a console's pairing route on a
private address.

## A second machine

The phone holds one key. Pairing from a second console, or `deskcam token new` there,
replaces it, and the first machine is locked out. So carry the key over instead:

```sh
deskcam token show                    # on the machine that paired
deskcam token set -                   # on the new one; reads the key from standard input
deskcam use http://PHONE:8080         # and point it at the phone
```

`deskcam use http://PHONE:8080 KEY` does both in one line, at the cost of putting the key in
your shell history. `set -` keeps it out of `ps` and the history. `DESKCAM_TOKEN` works too.

## What the console exposes

`deskcam serve` listens on port 9000 on every interface, because the phone has to reach it
to fetch the app and finish pairing. Only those two routes, `/p/` and `/deskcam.apk`, answer
from off the machine. The page, the codes, the journal, the operations and the key answer on
`127.0.0.1` alone, and a change from the page must be a POST carrying a header that a
cross-origin form cannot set.

## When the phone listens but cannot be reached

On Android 17, `INTERNET` and `ACCESS_LOCAL_NETWORK` are separate permissions. An app with
only the first can reach the internet while the platform drops every packet to a private
address. The server looks healthy in `logcat`, `ss` shows it listening on 8080, and every
connection from the workstation times out.

DeskCam asks for `ACCESS_LOCAL_NETWORK` the first time it opens. If you declined, grant it
in the app's settings; `adb install -g` grants it at install time. `deskcam usb` sidesteps
the problem, because it reaches the phone on its own loopback.

More symptoms and fixes are in [Troubleshooting](troubleshooting.md).
