# DeskCam DSL — Action Script Design

Status: **built, 2026-09-10**, as `/api/script` and `deskcam script run`. This
document captures the idea, the lineage, and the design we arrived at. Section 9
is the review that was done against the code before building; section 10 records
what the building changed. Where the two disagree, section 10 is what exists, and
`/api/help` is what is true.

## 1. Where this comes from

The Kodak DC290 (Matt's first digital camera, 1999) ran Digita OS, a camera
operating system by FlashPoint Technology. Digita had a text scripting
language — DigitaScript — that let you write `.CSM` files on a computer, load
them onto the camera's CompactFlash card, and run them from the camera menu.

DigitaScript was designed for a human writing scripts in a text editor to
automate a camera that couldn't make decisions for itself. It had `if/end`,
`goto`/markers, declared typed variables, ~70 built-in commands, and
in-camera file I/O. Every command returned an error status (0 = success).

DeskCam's primary consumer is not a human. It is an AI agent (Claude Code).
The agent is the intelligence. It decides what to do next based on what it
saw. The DSL does not need to be intelligent — it needs to be a reliable
execution tape that the agent writes, submits, and reads back.

## 2. What DeskCam already has

DeskCam's eight rules (R1-R8) are a cleaner, more rigorous version of what
Digita was reaching for:

| DeskCam rule | Digita equivalent | DeskCam improvement |
|---|---|---|
| R1: exit codes | error status per command | agent tests exit code, not a returned variable |
| R3: full k=v on every endpoint | SetCameraState("fmtd", 2) | human-readable names, not 4-char codes |
| R4: measured values, not requested | (did not have) | measured block from TotalCaptureResult |
| R5: unknown param is error | error checking | 400 on every endpoint, nothing falls back |
| R6: API describes itself | GetCapabilitiesCount/Type | one call, everything, at /api/help |
| D8: capture describes itself | GetFileTag/SetUserFileTag | per-frame CaptureCallback, provenance header |

What DeskCam does not yet have is the workflow layer — composing multi-step
sequences. The agent currently does this by writing ad-hoc shell loops. A
script endpoint makes it first-class.

## 3. Design

### 3.1 The shape

A script is a single-root, non-branching, sequential series of actions. One
verb per line. No `if`, no `goto`, no labels, no loops, no variables, no
inline computation. The agent is the branching. The script is the tape.

```
# inspect the connector area
SET zoom=4 cx=0.3 cy=0.7 torch=30
WAIT 500
AF
WAIT 1000
SNAP
SET torch=0
SNAP
```

### 3.2 The verbs

Each verb maps to an existing HTTP endpoint handler. The script runner calls
the same internal handler, so there is one code path, not two.

| Verb | Maps to | Output in stream |
|---|---|---|
| `SNAP` | /api/still | path, provenance |
| `FRAME` | /api/frame | path, provenance |
| `RAW` | /api/raw | path, provenance |
| `BURST n=16` | /api/burst | frame count, paths, provenance |
| `BRACKET base=1/240 stops=4` | /api/bracket | frame count, walk.json, paths |
| `FOCUSSWEEP from=3 to=6 steps=7` | /api/focussweep | frame count, walk.json, paths |
| `SET k=v ...` | /api/set | status block, measured values |
| `RESET` | /api/reset | status block |
| `AF` | /api/af | status block |
| `STATUS` | /api/status | full JSON state |
| `WAIT nn` | (new) | ms waited |

That is 11 verbs. All parameters use the same k=v syntax the phone already
parses. `SNAP zoom=4 cx=0.3` uses the same tokens as
`/api/still?zoom=4&cx=0.3`.

### 3.3 Comments

Lines starting with `#` are comments. The interpreter ignores them. The agent
writes them for its own reasoning. Costs nothing. Digita had this.

### 3.4 WAIT

`WAIT nn` waits nn milliseconds. This is the only new primitive the phone
needs. It is the explicit timing control the agent needs between hardware
operations — the same role Digita's `Wait()` and `WaitForShutter()` played.

The existing `settle`, `timeout`, and `fresh` parameters still apply per
capture verb. WAIT is for inter-step timing the agent controls explicitly.

### 3.5 Error handling

Fail-fast. If a step fails (HTTP 400, capture timeout, etc.), the stream sends
the error event and closes. The agent reads the error, corrects the script,
and resubmits.

This matches R1 (exit code tells the agent what happened) and Digita's error
status per command. No silent continuation.

### 3.6 No branching, no variables — why

- The agent tracks state externally. It does not need the script to hold
  variables.
- The agent writes another script when it needs to branch. This is cheaper
  than embedding control flow in the tape.
- No `goto`/labels. This was Digita's weakest feature, a product of the
  BASIC-era line-oriented interpreter. The agent emits cleaner sequences
  without it.
- No inline computation. The agent does math.
- No UI commands. The agent does not need Display or SetOption.
- No file I/O. That is the workstation's job per the split rule.

## 4. The transport

### 4.1 One request, one stream

```
POST /api/script
Content-Type: text/plain

# inspect the connector area
SET zoom=4 cx=0.3 cy=0.7 torch=30
WAIT 500
AF
WAIT 1000
SNAP
SET torch=0
SNAP
```

Response:

```
Content-Type: text/event-stream

data: {"step":0,"verb":"SET","ok":true,"measured":{...}}
data: {"step":1,"verb":"WAIT","ok":true,"ms":500}
data: {"step":2,"verb":"AF","ok":true,"measured":{...}}
data: {"step":3,"verb":"WAIT","ok":true,"ms":1000}
data: {"step":4,"verb":"SNAP","ok":true,"path":"deskcam-001.jpg","provenance":{...}}
data: {"step":5,"verb":"SET","ok":true}
data: {"step":6,"verb":"SNAP","ok":true,"path":"deskcam-002.jpg","provenance":{...}}
data: {"done":true,"steps":7}
```

The agent submits the tape and reads the stream until it closes. Each
capture event gives the path and the provenance inline — the same data that is
in the sidecar and the X-DeskCam-Provenance header today, just in the stream.

### 4.2 Implementation cost

SSE is `Content-Type: text/event-stream`, write `data: {json}\n\n`, flush,
repeat, close. In the existing hand-written HttpServer.java this is ~20 lines.
No dependency. The existing verb handlers already produce JSON — the script
runner calls them internally and writes each result as an SSE event instead
of an HTTP response body.

The script parser is a line-by-line reader that splits verb from k=v tokens
and routes to the existing handler. ~50 lines of Java.

### 4.3 Disconnect behavior

On disconnect, abort the script. The stream closes. The CLI exits non-zero.
The agent knows it did not finish and can resubmit.

On a LAN this is sufficient. If resilience is needed later, the first event
can carry a job_id and a `GET /api/script/{id}/log` recovery endpoint can be
added. Do not build that until it bites.

### 4.4 Where images go

The phone stores captures in a working directory on the phone. The SSE event
gives the path. The client downloads the ones it wants afterward. This is
the Digita model — scripts wrote to the CompactFlash, you pulled files off
later. It is also the DeskCam model — the phone is a sensor, the workstation
decides what to do with the pixels.

### 4.5 CLI

```
deskcam script run inspect.dcl
```

POSTs the file, reads the stream, prints one line per event. Captures print
the path (R2). SET prints the summary. The agent reads stdout. If the
connection drops, the stream closes early and the CLI exits non-zero.

## 5. What this does not replace

The existing one-shot endpoints stay. The agent uses them for single
operations. The script endpoint is for sequences — focus stacks, brackets,
guided capture, calibration runs, anything that needs multiple steps with
timing control.

The roadmap items that become scripts instead of hardcoded endpoints:

| Roadmap item | As a script |
|---|---|
| Focus stack | FOCUSSWEEP → (workstation stacks) |
| Exposure bracket merge | BRACKET → (workstation merges on measured exposure) |
| Guided capture | SET wide → SNAP → read → SET close → SNAP |
| Calibration run | SET measure=1 → SNAP x2 → (workstation compares) |
| Display rectification | SNAP → (workstation finds corners, homography) |

The phone runs the tape. The workstation does the floating-point work. The
split rule holds.

## 6. Lessons from Digita, applied

### Take

1. Every command returns a status. Digita's most important pattern. The
   stream carries it per step.
2. Capability introspection. /api/help already does this. The script runner
   can validate verbs against the known set before execution.
3. Wait-for-hardware-ready. WAIT gives the agent explicit timing control.
   The existing settle/timeout/fresh parameters do this per-capture.
4. Comments. The agent annotates its own reasoning.
5. Script-as-text-file. The agent can read, modify, and resubmit.
6. Metadata at capture time. Provenance inline in the stream, same as
   sidecar/EXIF today.

### Leave

1. Goto/marker control flow. The agent branches.
2. Declared typed variables. The agent tracks state.
3. The line-oriented interpreter model. Parse, then execute.
4. 4-character parameter tags. Full names, as today.
5. In-camera file I/O. Workstation's job.
6. UI commands (Display, SetOption, GetString). No human LCD to drive.
7. Single-script-at-a-time constraint. The stream model allows the agent to
   submit another script after the current one closes.

## 7. Open questions

- WAIT units: ms (matching the rest of the system) or accept `2s` syntax?
  Recommend: ms only, matching convention. The agent can convert.
- Should STATUS be a verb, or is it implicit? The agent can call /api/status
  via the existing endpoint. Including it as a verb lets the agent sample
  state mid-tape without breaking the script. Recommend: include it.
- Should the script carry a header (name, description)? Digita had name/mode/
  menu/label. For an agent, a comment line is enough. Recommend: no header.
- Concurrent scripts: the phone runs one camera. A second script while one
  is running should be rejected with a clear error. Recommend: one at a time.

## 8. References

- Digita Script Guide v1.5, FlashPoint Technology, Nov 1999
  http://lisas.de/digita/Script%20Guide.PDF
- Digita Script Reference v1.5, FlashPoint Technology, Dec 1999
  http://lisas.de/digita/Script%20Reference.PDF
  (full copy at /mnt/b450-hermes/Inbound/Digita Script Reference.pdf)
- "The Digita OS: An Extensible Imaging Platform", Dr Dobb's Dec 2000
  https://jacobfilipp.com/DrDobbs/articles/DDJ/2000/0012/0012i/0012i.htm
- "DigitaScript: A Scripting Language for Digital Cameras", Dr Dobb's Jan 2001
  https://jacobfilipp.com/DrDobbs/articles/DDJ/2001/0101/0101j/0101j.htm
- DigitaOS, Wikipedia
  https://en.wikipedia.org/wiki/DigitaOS
- DeskCam spec
  docs/SPEC.md, in this repository

## 9. Review, 2026-09-10

Read against the code as it stands after cards 5, 7, 53 and 55. Five findings,
worst first. Card 57 carries them as work.

### 9.1 Section 4.4 contradicts a committed boundary

"The phone stores captures in a working directory on the phone" is the one part
of this design that is not a detail. SPEC section 4.1 lists what the backend
does not do and ends with "storage of old captures". The phone writes exactly
one file today, a temp file in the cache directory for EXIF injection, consumed
immediately. There is no storage to build on, and adding it brings permissions,
a cleanup policy, disk-full behaviour, a listing endpoint and a download
endpoint.

**Answer with `multipart/mixed` instead of SSE.** JSON events and image parts in
one ordered stream. The server already writes multipart for MJPEG, so it is the
same machinery it has. Live progress survives, the pixels come back inside the
same request, and the phone still stores nothing. `deskcam script run` writes
each part to disk as it arrives, exactly as `deskcam walk` unpacks a tar today.

### 9.2 The case for this is atomicity, not latency

Section 2 says the script layer "makes it first-class", which is weak, and the
implied speed argument is weaker: a round trip on this LAN is about 100 ms
against a settle-plus-capture of several hundred, so the seven step focus sweep
run by hand on 2026-09-10 lost well under a second to the network.

The real argument is correctness. Nothing today stops the console, which polls
`/api/state` every two seconds and can POST `/api/cam`, or a second agent, from
changing the camera between a `SET` and a `SNAP`. Every multi-step sequence
driven from the shell is racy. The walk endpoints avoid it by doing the whole
sequence inside one request, and a script generalises that.

That implies a design point this document does not have: **a script holds the
camera for its duration**, and a second script or a conflicting `/api/set` is
refused with a clear error while one runs.

### 9.3 "One code path, not two" is the best decision here, and it is not free

Section 4.2 estimates ~50 lines. The verb handlers do not produce values:
`route()` writes straight to the socket through `sendJson`, `sendBytes` and
`Tar.writeTo`. Calling them from a script runner means refactoring each one to
return a result that the caller renders. That refactor is the real cost of this
design, and it is worth doing for its own sake, but it should be costed.

### 9.4 Fail-fast leaves the camera wherever it stopped

```
SET torch=45
SNAP          # fails
SET torch=0   # never runs
```

The torch stays on. `CameraEngine.walk` already solves this: it restores the
starting state even when a step fails, because a walk is an excursion and not a
change. A tape should almost certainly do the same, and say in its last event
what it put back.

### 9.5 WAIT deserves more scepticism

Every capture verb already takes `settle`, and `/api/af` takes `wait`. If WAIT
survives it should be justified by what `settle` cannot express: waiting on
something that is not a capture, such as an LED reaching thermal steady state
after `torch=45`. Otherwise it is a second way to spell a parameter that exists.

## 10. What the building changed, 2026-09-10

Section 9's five findings all held, and all five are in the code. Beyond them, five
things came out differently from section 3 and section 4.

### 10.1 Thirteen verbs, not eleven

`WALK` and `FOCUSHUNT` were added, because cards 55 and 56 landed between this
document being written and being built. `WALK vary=torch values=0,20,45` on one
line of a tape is the composition the card narrative predicted, and neither piece
had to become a language for it.

### 10.2 A verb whose answer says `ok: false` is a failed step

Not in the design, because when it was written no endpoint could answer 200 with
`ok: false`. `/api/focushunt` can: it walked the lens and found no peak to choose.
Treating that as a successful step would let the next `SNAP` be taken out of focus
and reported as a success, which is the class of failure this whole card is about.

### 10.3 The refactor of 9.3 was the bulk of the work, and it is provable

The handlers wrote to the socket. They now return an `Answer` and the caller
renders it: as an HTTP response for a URL, as an event and its parts for a tape.
The claim that every existing endpoint still answers exactly as it did was checked
rather than asserted: twenty endpoints were probed against the running phone before
the refactor and again after it, comparing status lines, header names and the shape
of every JSON body. The only difference was `/api/help` gaining `POST /api/script`
and the `script` block. Three fields differed on the first attempt and matched once
the camera was in the same state for both runs, which is what they describe.

### 10.4 The closing delimiter belongs in the `finally`

A tape that stops at a failed step leaves the runner by a `return`. The multipart
terminator was written after the `try`, so on exactly the path that carries the
most important message the stream ended without it, and the reader on the other
side reported "unexpected EOF" and discarded the final event, which was the one
saying what had failed and what the camera had been put back to. Found by running
a tape that refuses, not by reading it.

### 10.5 Open questions from section 7, answered

- **WAIT units:** milliseconds only, 0 to 60000, as a bare number. `WAIT 500`.
- **STATUS as a verb:** yes. Sampling the state mid-tape without breaking the hold
  is worth a verb, and the hold is exactly what makes it necessary.
- **A header for the script:** no. A comment line is enough.
- **Concurrent scripts:** refused with 409, which is also what a conflicting
  camera change gets. Reads are not refused.

One limit, worth stating rather than discovering: a tape holds at most 200 steps,
because its length is how long everything else is refused.
