# The DeskCam manual

DeskCam turns a spare Android phone on a stand into a bench camera that you and a coding agent
share. The phone serves the camera over HTTP. The `deskcam` CLI drives it from the shell. The
console in your browser shows the live view beside a journal of everything anyone did.

This manual is for both readers. A person at the bench can read it front to back. An agent
can jump to a page and find the command, the exit code and what the output means. The agent
also has its own manual, [`skill/SKILL.md`](../../skill/SKILL.md), which covers the same
surface with more on judgement: when to describe, when to measure, when to refuse.

## Pages

| Page | Read it for |
|---|---|
| [Setting up](setup.md) | Installing, pairing, the access key, a second machine, USB or Wi-Fi |
| [The console and the camera page](console.md) | Every control, gesture and key in the browser, the journal, and the level |
| [The CLI day to day](cli.md) | Framing, focus, exposure, light, the kinds of capture, where files go |
| [Working with an agent](agents.md) | The skill, pointing at things both ways, waiting for a person, tapes |
| [Capture techniques](techniques.md) | Notes and paintings, boards, screens, focus stacks, bursts, RAW |
| [Measuring](measuring.md) | The noise floor, scale, distance, the mat, linearity, and why tools refuse |
| [Troubleshooting](troubleshooting.md) | Symptom, meaning, fix |
| [How it works](how-it-works.md) | Why the crop is in software, dioptres, focus hunting, idling, heat |
| [Reference](reference.md) | Every command, endpoint, parameter, verb, header, exit code and sidecar key |

## I want to

| Task | Where |
|---|---|
| Take my first picture | [Setting up: the first capture](setup.md#the-first-capture) |
| Stop other people on the network using the camera | [Require an access key](setup.md#require-an-access-key) |
| Use the camera from a second computer | [A second machine](setup.md#a-second-machine) |
| Point at a part so the agent looks at it | [Pointing, both ways](agents.md#pointing-both-ways) |
| Have the agent point at a part for me | [The agent points](agents.md#the-agent-points-the-person-sees-it) |
| See what the agent has been doing | [The journal](console.md#the-journal) |
| Focus on one part without changing the framing | [Focus](cli.md#focus) |
| Photograph a screen without bands | [Screens and displays](techniques.md#screens-and-displays) |
| Get a whole board sharp | [Focus stacking](techniques.md#everything-sharp-at-once-focus-stacking) |
| Measure a distance in millimetres | [Scale from a rule](measuring.md#scale-from-a-rule) |
| Know whether the bench has moved | [Has the bench moved?](measuring.md#has-the-bench-moved) |
| Level the phone | [Levelling the mount](console.md#levelling-the-mount) |
| Run several steps with nothing changing in between | [Run a sequence as one operation](agents.md#run-a-sequence-as-one-operation) |
| Repeat a shot from last week | [Putting the camera back](cli.md#putting-the-camera-back) |
| Find out why something failed | [Troubleshooting](troubleshooting.md) |

## Elsewhere

* [docs/DECISIONS.md](../DECISIONS.md) records why things are the way they are, as numbered
  decisions. The manual cites them as D7, D20 and so on.
* [The explainer](../../explainer/index.html) is an interactive page on the sensor crop,
  dioptres, display flicker and tapes. Open it in a browser.
* [CHANGELOG.md](../../CHANGELOG.md) lists what changed in each release.

## Keeping it true

The reference is held equal to the code by `frontend/test_contract.py`: the CLI text, the
endpoint list, the parameters, the thermal words and the response headers. A number in this
manual comes from a tool's output, with the date and the command that produced it, and one
measured once is called indicative. If you find a claim here that the phone contradicts, the
phone is right; please fix the page.
