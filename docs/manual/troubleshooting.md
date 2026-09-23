# Troubleshooting

Start with `deskcam show`. It either prints one line of state, which proves the phone is
reachable, or fails with a reason. The CLI always prints the phone's own words for a refusal.

## Reaching the phone

| Symptom | Meaning | Fix |
|---|---|---|
| Connection refused | The service is not running | `deskcam start`, or tap **Start** in the app |
| Connection refused, just after an install | Installing stops the app, and the service does not come back by itself | `deskcam start` |
| Every request times out, but the phone says it is listening | Android 17 is dropping local network traffic | Grant the app's local network permission, or use `deskcam usb`. See [Setting up](setup.md#when-the-phone-listens-but-cannot-be-reached) |
| Wrong address, or none | The phone moved to a new address | `deskcam wifi` with the phone on USB, `deskcam usb` for the cable, or pair again |
| The app shows its VPN address | Tailscale or another VPN is up | Tap the network chip beside the address and choose Wi-Fi |
| Nothing works after a reboot | Android does not let a camera app start itself | Tap the app once, or `deskcam start` |
| `unauthorised: supply ?token=...` | The phone has a key and this request did not carry it | `deskcam token set -` with the key from the machine that paired. See [A second machine](setup.md#a-second-machine) |
| The phone's page shows no picture and says unauthorised | The page needs the key in its address | Open `http://PHONE:8080/?token=KEY`, or use the console |
| Another machine stopped working after you paired | The phone holds one key, and pairing replaced it | Carry the key over with `deskcam token set` instead of pairing twice |
| `deskcam which` is not the phone you expected | `DESKCAM_URL` is set, or another target was saved | `deskcam use URL`. The environment variable wins over the saved target |
| Two phones on adb and the wrong one answers | adb picked one | Set `DESKCAM_SERIAL` |

## The camera

| Symptom | Meaning | Fix |
|---|---|---|
| `state: disconnected` | Another app has the camera | It reopens by itself within about 15 seconds |
| `last_error` says another app is using camera 0, but captures work | A stale message from a restart | Ignore it once a `snap` succeeds |
| `HTTP 409` and "a script is running" | A tape holds the camera | Wait. It finishes or fails on its own, and reading is still allowed |
| A capture times out | The exposure is long | Raise `timeout=`, or shorten the exposure |
| `HTTP 400` naming a parameter | A typo, or a value the phone cannot read | Read the message; it names the parameter |
| The first capture after a pause is slow | The camera was idle and had to wake | Expected. It is slower, never worse. See [How it works](how-it-works.md#the-camera-sleeps-when-nobody-is-asking) |
| The picture is upside down or sideways | The phone is mounted that way | `deskcam set rotate=180` once |
| The front camera is selected | `camera=1` | `deskcam set camera=0`. The front camera is fixed-focus and cannot focus on a bench |

## Pictures

| Symptom | Meaning | Fix |
|---|---|---|
| Dark bands across a screen | The exposure is not a whole number of flicker periods | `exposure=1/60`, or `1/30` or `1/120`. See [Screens and displays](techniques.md#screens-and-displays) |
| Colour changes between shots | Auto white balance changed its mind | `awb=daylight awblock=on` |
| A bright patch washes out a glossy subject | The torch reflects straight back | Torch off, and light from one side |
| Everything looks dark and flat | `measure` is on | `deskcam set measure=0`, or **Normal** at the console |
| Soft, and `af` says `focused` | Autofocus found nothing to lock onto and said so anyway | Fix the exposure and `deskcam focus hunt`, which refuses when there is no peak |
| `focus hunt` refuses with `flat` | Nothing in the region has detail, such as a glossy black part | Move the `focusbox` onto something with edges, or light it |
| `focus hunt` refuses with `peak_at_edge` | The sharpest position is outside the range searched | Widen `from` or `to` as the message suggests |
| `focus hunt` picks a different place each time | Auto exposure is on | Set `exposure=` and `iso=` first |
| Faint writing is unreadable | `frame` is preview-sized, or the subject is too small in the frame | `snap`, and move the camera closer rather than zooming |
| A burst came back short | The phone ran out of time or memory | The CLI warns on stderr. Check the count before averaging |

## The phone is hot

`deskcam show` ends with `HOT severe` or worse once the platform throttles. Captures are never
slowed, but the sensor is hot and noisier, and streams slow down and say so on every part.

* Close any camera page left open in a background tab. A stream keeps the camera awake.
* Set the phone to stop charging at 80%.
* Give it a few minutes before comparing a measurement against an earlier one.

[Heat and battery](how-it-works.md#heat-and-battery) has the measurements.

## Marks and agents

| Symptom | Meaning | Fix |
|---|---|---|
| Marks sit on the wrong parts | The camera or stand moved since they were placed | Place them again. `deskcam calibration --against` measures the move when the mat is in frame |
| `deskcam log wait` returns on a zoom, not the mark | It was waiting for any operation | `deskcam log wait op=mark` |
| `deskcam log wait` never returns, though the person pointed | They pointed on the phone's own page, which is not journalled | `deskcam mark list`, or have them use the console |
| The agent cannot see what the person did at all | The person is on the phone's page | Use the console at `http://127.0.0.1:9000` |
| The live view says "paused" | Five minutes without a touch | Move the mouse. **Restart stream** if it stays black |
| The console's LAN address answers 403 | By design. Only the phone's install and pairing routes answer off the machine | Open `http://127.0.0.1:9000` on the workstation |

## Measuring

| Symptom | Meaning | Fix |
|---|---|---|
| A tool prints `REFUSED` and exits 2 | Its confidence was below its limit | Read the reason. It is the answer, not an error. See [Refusal is the feature](measuring.md#refusal-is-the-feature) |
| `scale` is about five times too large | It locked onto a different pattern, such as graph paper instead of the rule | `--region` around the reference you meant, and check `--pitch-mm` |
| A sidecar says the scale `applies: false` | The framing changed since the scale was measured | Measure the scale again in this framing |
| `calibration` refuses: found 3 markers, need 4 | Too little of the mat is in frame, or the light is uneven | Show more of the mat, or even out the light |
| `calibration` refuses and mentions OpenCV | The detector is not installed | `pip install -e '.[mat]'`, or supply `--corners` |
| `linearity` refuses: steps inside the noise floor | The exposures are too close together to tell apart | Wider exposure steps |
| `hdr` refuses a bracket | It was taken without `measure=1` | Take it again in measurement mode |
| A measurement tool is not found | The Python tools are not beside the binary | Set `DESKCAM_ANALYSIS` to the repository's `frontend` directory |
| A tool says `No module named numpy` or `cv2` | The CLI runs the first `python3` on your path, and the extras are installed somewhere else | Activate the environment you installed them into, or install them into that `python3` |

## Installing

| Symptom | Meaning | Fix |
|---|---|---|
| The APK will not install over the old one | It is signed with a different key: a release over a home build, or a lost keystore | Uninstall the app first. That clears its settings |
| The phone's browser will not download the APK over plain HTTP | Some browsers, including GrapheneOS's Vanadium, refuse | `deskcam serve --apk release`, or install with adb |
| The phone asks before pairing | It always does. A pairing link can change the key | Check the code came from your own console, then tap **Pair** |
