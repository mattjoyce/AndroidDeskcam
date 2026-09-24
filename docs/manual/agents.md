# Working with an agent

DeskCam was built for a coding agent to use, and for a person at the bench to work beside
it. The agent drives the CLI. The person uses the console. The journal and the marks
connect the two.

## Give the agent the skill

For Claude Code, link the skill into place:

```sh
ln -sf "$PWD/skill" ~/.claude/skills/deskcam
```

`skill/SKILL.md` is the agent's manual. It covers every command, endpoint and parameter, and
a contract test keeps it that way. It is also written for judgement: when a request wants a
description and when it wants a number, how to read a sidecar, and when to refuse. Other
agents can read the same file; nothing in it is specific to one harness.

## Why an agent can drive it

Eight rules shape the whole interface. [docs/DECISIONS.md](../DECISIONS.md#the-primary-consumer-and-the-eight-rules-it-gives) gives the reasoning behind each:

1. Each operation is one shell command, with exit code 0 for success, 2 for a refusal and 1
   for an error.
2. A capture prints a file path, never image bytes. The agent opens the file with its own
   tools, which keeps images out of its context until it wants them.
3. Every endpoint takes the full control set as `k=v` words, so the agent never has to track
   state between calls.
4. Every response reports what the sensor measured beside what was asked for.
5. An unknown or malformed parameter is rejected with a 400, never ignored.
6. `/api/help`, which `deskcam api` prints, describes the whole surface.
7. Units are the ones on a datasheet: `1/120`, `8ms`, `250us`, `0.12`.
8. Setting a value is idempotent. `zoom=4` sent twice leaves the camera where sending it once
   did. The relative moves, `dx`, `dy`, `zoomby` and `deskcam pan`, are the exception.

A typical agent step:

```sh
img=$(deskcam snap zoom=4 cx=0.35 cy=0.42 torch=20 --why "read the part number on U4")
```

The agent then reads `$img`.

## What each side can see

| | The person sees | The agent sees |
|---|---|---|
| The live camera | Both pages | Nothing live. Only the files it captures |
| A capture | Its thumbnail and record in the console journal | The file and its sidecar |
| What the other did | The console journal, with every agent command, its `--why`, and every refusal | `deskcam log`, which includes the person's console actions |
| A mark | Drawn on both live views | `deskcam mark list`, or `deskcam log wait op=mark` |

**An agent cannot put an image in front of the person.** Both pages show the live camera,
never files. An agent gives the path, points you at the console, or places a mark.

The journal records everything the CLI and the console do. **The phone's own page is not
journalled**, so what a person does there is invisible to `deskcam log`. Work at the console
when an agent is involved.

## Pointing, both ways

A mark is a point or a box on the picture with a few words on it, "pin 1" or "this cap". It
changes nothing on the camera. Marks are kept on the sensor, so they stay on their parts
through zoom, pan and rotation.

### The person points, the agent reads it

At either live view, **Shift-drag** a box or **Shift-click** a point. That replaces every
mark with yours, labelled "look here". **Ctrl-Shift-drag** adds a box and keeps the rest.
**Mark this view** marks the whole screen.

An agent told "I'll point at it" waits for the mark itself:

```sh
deskcam log wait op=mark timeout=180
```

It returns one JSON journal entry when the mark arrives, and exits 2 if none came. The place
is in `query` as `mark=cx,cy` or `mark=cx,cy,w,h`, in the whole-sensor coordinates `cx` and
`cy` use, whatever the framing was when it was drawn. So the agent can look there with
`deskcam snap zoom=4 cx=CX cy=CY`.

**Wait for the mark, not for anything.** Lining a part up means zooming and panning, and each
of those is a `set` in the journal. `deskcam log wait` with no `op` wakes on the first one.

| `op` | The person |
|---|---|
| `mark` | pointed: a shift-drag, a shift-click, or Mark this view |
| `unmark` | cleared the marks, with Clear or Reset all |
| `focus` | double-tapped to focus. The place is the `focusbox` in `query` |
| `snap` | pressed Snap to take a still. Its path is in `files` |
| `set`, `reset`, `af` | aimed, reset, or pressed Autofocus |

`op=mark,snap` wakes on whichever comes first. `log wait` watches the console only, because
another agent's capture is not a signal from the person; `via=any` widens it.

The label is always "look here". A mark carries a place, not the person's words, so an agent
that needs more should ask.

A mark drawn on the phone's own page never reaches the journal. Then the agent asks the
person to say when they have pointed, and reads the marks directly:

```sh
deskcam mark list
```

Each mark has an `id`, a `kind` (`point` or `box`), `cx`, `cy`, and for a box `w` and `h`, its
`label`, `by`, `at`, and `in_crop`, which says whether the person can currently see it.

### The agent points, the person sees it

```sh
deskcam mark at 0.42,0.61 label="pin 1" by=claude              # a point
deskcam mark at 0.42,0.61,0.2,0.1 label="this cap" by=claude   # a box: centre, then size
```

`mark at` takes fractions of the picture the agent last looked at, like `focus at`, and turns
them into sensor coordinates using the framing at that moment. So capture, read, and mark
before anyone reframes. The mark appears on both live views and in the list beside them,
where a click zooms to it.

`label` is at most 80 characters. `by` is one short word, `agent` when left out, and a claim
rather than a proof. The person's own marks say `you`. An agent's mark is added beside the
others; it does not replace them.

`deskcam mark clear ID` removes one mark. `deskcam mark clear` on its own removes every mark,
the person's too, so an agent should do that only when asked.

### When the bench moves

A mark is stored against the sensor. Move the camera or the stand and every mark points at
the wrong part, and nothing says so. If the printed mat is in frame, record a calibration
when marks are placed and check a later capture against it:

```sh
deskcam calibration shot.jpg --write .
deskcam calibration later.jpg --against deskcam-calibration.json
```

[Measuring](measuring.md#has-the-bench-moved) explains the output. A reading over the
tolerance means place the marks again, not nudge them.

## Run a sequence as one operation

Nothing stops the person at the console, or a second agent, from changing the camera between
one command and the next. A tape runs several steps as one request and holds the camera
while it runs. Any request that would change the camera gets HTTP 409 until the tape ends.
Reading is still allowed.

```
# inspect the part, lit and unlit
SET zoom=2 cx=0.5 cy=0.5 exposure=1/33 iso=200 awbgains=neutral
FOCUSHUNT
SET torch=25
WAIT 500
SNAP
SET torch=0
SNAP
```

```sh
deskcam script run inspect.dcl
```

```
7 steps: SET FOCUSHUNT SET WAIT SNAP SET SNAP
   0 SET        zoom 2x  at 0.5,0.5  af continuous  ae manual  30.02ms (1/33)  iso 163
   1 FOCUSHUNT  chose 4.464 d, sharpness 1107.46, contrast 0.992, 14 readings
   2 SET        zoom 2x  at 0.5,0.5  af off  focus 0.224m  ae manual  30.27ms  torch 25
   3 WAIT       500 ms
   4 SNAP       004-still.jpg
   5 SET        zoom 2x  at 0.5,0.5  af off  focus 0.224m  ae manual  30.27ms (1/33)
   6 SNAP       006-still.jpg
done: 7 of 7 steps in 7.2 s
```

The verbs are `SNAP`, `FRAME`, `RAW`, `BURST`, `BRACKET`, `FOCUSSWEEP`, `WALK`, `FOCUSHUNT`,
`SET`, `RESET`, `AF`, `STATUS` and `WAIT`. Each is an endpoint that already exists and takes
the same `k=v` words, so a tape line and a URL cannot drift apart. `#` starts a comment.
`WAIT` is for things that are not captures, such as an LED warming up; a capture verb has
`settle` of its own.

The rules:

* **The whole tape is parsed before any of it runs.** A typo is a 400 that names the line,
  and the camera is untouched.
* **A failed step ends the tape and puts the camera back** where the tape found it, and
  `deskcam script run` exits non-zero. So `SET torch=45` followed by a failed capture does not
  leave the LED on. A tape that finishes leaves the camera where it put it.
* **A `FOCUSHUNT` that finds no peak is a failed step,** because the `SNAP` after it would be
  out of focus and reported as a success.
* **Each capture arrives inside the same response** and is written as it arrives, with its
  own sidecar. The phone stores nothing.
* **A tape has no branching, variables or arithmetic,** on purpose. The agent is the
  intelligence and the tape is the execution record. To decide something, read the result
  and send another tape.

The reason for a tape is atomicity, not speed. A round trip on the bench LAN is about 100 ms
against several hundred for a settle and a capture, so seven steps from the shell lose well
under a second. What they lose is the guarantee that nothing moved in between.

`docs/deskcam-dsl.md` has the design and the review that shaped it.

## Leaving the bench tidy

An agent should turn the torch off when it finishes, clear only its own marks, and leave
`measure` off. `deskcam reset` puts every setting back to its default.
