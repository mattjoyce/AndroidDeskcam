# The console and the camera page

Two browser pages show the camera. They share one camera page, so the live view, the
gestures, the controls and the marks behave identically in both (decision D20). What
differs is what surrounds that page and what gets written down.

| Page | Address | What it adds | Journalled |
|---|---|---|---|
| The console | `http://127.0.0.1:9000`, from `deskcam serve` | The journal of every operation, the capture record, Snap and the other operation buttons, and the codes that install and pair | Yes, as `via: console` |
| The phone's page | `http://PHONE:8080`, or `deskcam open` | Nothing. The camera page alone, in any browser on the network with nothing installed | No |

Use the console at the workstation. It is where an agent can see what you did, and where
you can see what an agent did. The phone's page is for a laptop or tablet across the room,
or for when no console is running. With an access key set, open it as
`http://PHONE:8080/?token=KEY`.

The console answers on `127.0.0.1` only. Its banner prints the machine's LAN address too,
but that address serves the phone the app and the pairing route and nothing else.

## The live view

| Gesture | What it does | Moves the camera |
|---|---|---|
| Double tap or double-click | Focuses on that spot and leaves the framing alone | Focus only |
| Drag a box | Crops to the box, then focuses on it | Yes |
| Hold, then drag | Pans | Yes |
| Scroll, or `+` and `-` | Zooms in and out. `=` also zooms in | Yes |
| Shift-drag | Replaces every mark with one box labelled "look here" | No |
| Shift-click | Replaces every mark with one point labelled "look here" | No |
| Ctrl-Shift-drag | Adds a box and keeps the marks already there | No |

A double tap sets a `focusbox` on the spot and runs one autofocus. A ring shows while it
hunts and then shows the result. The box stays until you drag a new crop or click
**Clear the focus box**. A tap and a mark both treat a drag shorter than 2% of the view as a
click.

The zoom keys work anywhere on the console page unless a text field has focus. Ctrl and Cmd
shortcuts keep their browser meaning, so Ctrl-`+` still zooms the page itself.

**The view pauses after five minutes untouched,** to stop a tab left open overnight from
holding the camera awake. Move the mouse to wake it. A tab in the background stops its
stream at once. **Restart stream** reconnects a view that has stalled.

## The controls

| Group | Controls |
|---|---|
| Under the view | **Save full-res still** opens a full-resolution still in a new tab, for your browser to save. **Autofocus** runs one sweep. **Reset all** puts every setting back to its default and removes every mark. **Restart stream** |
| Framing | A zoom slider from 1x to 12x, and a pad that nudges by a fifth of the view each way, with `·` to recentre |
| Focus | The mode: continuous, auto, macro, or manual. A distance slider in dioptres. **Focus on the crop** and **Clear the focus box** |
| Exposure | Auto or manual, the shutter as `1/60`, `8ms` or `250us`, the ISO, EV compensation, the white balance mode, and two presets, **1/60 anti-flicker** and **1/120** |
| Light | The torch, 0 to 45 |
| Marks | The list of marks, and **Mark this view**, **Fit all** and **Clear** |
| Status | The phone's full state, as it reports it |

Underneath is a log of what the page asked for and what came back. The copy button beside
it puts the log on the clipboard.

The zoom slider stops at 12x, where the crop still holds enough pixels to be worth looking
at. The CLI goes to the phone's limit, 63x on the Pixel 6a.

## Marks

A mark is a point or a box on the picture with a few words on it. It changes nothing on the
camera. It is how you show an agent a part, and how an agent shows you one.
[Working with an agent](agents.md#pointing-both-ways) covers the agent's side.

| Control | What it does |
|---|---|
| Shift-drag, shift-click | Replaces every mark with yours |
| Ctrl-Shift-drag | Adds yours beside the others |
| **Mark this view** | Replaces every mark with a box the size of what is on screen |
| **Fit all** | Frames the camera around every mark. This one moves the camera |
| **Clear** | Removes every mark, yours and the agent's |
| A row in the list | Zooms to that mark |

Marks are stored against the sensor, so a mark stays on its part through zoom, pan and
`rotate`. Their labels sit in two columns down the sides of the picture, each joined to its
mark by a line, so ten labelled parts on one board do not cover each other or the parts
(decision D21). A mark outside the current crop is not drawn; a chip in the corner of the
view counts them, and the list still has them.

The phone keeps at most 200 marks. They survive the service restarting, and are lost when
the app itself is stopped.

**A mark does not know if the bench moves.** Move the phone or the stand and every mark
points at the wrong place. [The mat](measuring.md#has-the-bench-moved) can tell you when that
has happened.

## The console's own parts

### Operation buttons

These run the CLI's own commands, through the CLI's own code, and land in the journal as
`via: console`.

| Button | Runs |
|---|---|
| **Snap** | `deskcam snap`, saving on the workstation with a sidecar. Type what it is for in the box beside it, and it becomes the capture's `--why` |
| **Focus hunt** | `deskcam focus hunt`. Fix the exposure first |
| **Measure** | `deskcam set measure=1` |
| **Normal** | `deskcam set measure=0` |
| **Pair** | Opens the install and pairing codes, with **New code**, **New key** and **Remove key** |

**Snap** and **Save full-res still** differ. Snap writes a capture on the workstation with
its sidecar and journals it. Save full-res still only opens the image in the browser.

### The journal

The left column is the journal: every capture and every change to the camera, by anybody,
from any directory. Two views:

* **by project** groups the entries by project, then by session. A session is a run of
  operations with no gap longer than thirty minutes. A capture shows its thumbnail.
* **command log** lists the commands as they were typed, newest first.

A refusal appears as fully as a capture, in the phone's own words.

### A capture's record

Pick a row and its record slides in from the right of the view: framing, exposure, focus,
light, orientation, scale, pipeline, sensor, and the raw JSON. The cross, the tab on the
edge of the view, or `i` slides it away. With no row picked, the same panel shows the live
state.

Click a thumbnail to open the picture full size. **Shoot this again** restores every setting
the capture recorded and takes the picture again, which is `deskcam recall` followed by a
snap. **Back to live** returns the side panel to the live state.

## Levelling the mount

Touch **Level** in the phone app's header. It needs no camera and no running service, so it
works straight after a reboot.

A bubble card shows the tilt and names in words the edge to lower. Each axis has its own
tone, which beeps faster as that axis comes in and holds steady once it is level, so you can
set the mount with both hands on the bracket. The range closes in from ten degrees to half a
degree as you converge.

It measures the camera's angle to gravity. That is the angle to your subject only when the
subject lies on a level surface. [How it works](how-it-works.md#what-the-tilt-reading-is)
explains the difference.
