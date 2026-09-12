# Phone panel review

Worktree: `~/Projects/codex/AndroidDeskcam`, branch `codex-review`.

## Merge boundary

The branch starts at `255c00a`. Commit `4cce7db` snapshots the original checkout's
uncommitted panel, marks, geometry, documentation and generated parameter changes.
It is a preservation baseline, not a Codex fix. The original checkout and index were
left untouched, and its local `HANDOFF.md` was not copied or committed.

If that work lands independently on master, cherry-pick the commits after `4cce7db`
rather than reapplying the snapshot. Otherwise merge the whole branch and reconcile
any subsequent edits in the original checkout normally.

## Intended scope

- Carry the page-link token to all phone-panel requests, including images.
- Preserve the final control input while a request is pending.
- Bound browser requests and prevent overlapping polls.
- Keep late status responses from replacing newer control state.
- Move the embedded page out of its Java string without adding production dependencies.

The existing trusted-network policy, server bind address and camera API stay as they
are. No phone installation or live camera operation is part of this review.

## Validation and progress

The snapshot builds successfully with `./backend/build.sh`: 462 Java checks pass.
The browser regressions will exercise the shipped page script with controlled network
responses and time. Device/browser visual validation remains a separate check.

### Token propagation

A token on the page URL was never inherited by its relative API URLs. The panel now
adds the encoded token at the transport boundary, including the initial stream,
restarts, pan and still-image links. Request logs retain credential-free URLs.

`node --test backend/test/webui/panel.test.mjs` reproduced the missing token on
`/api/status`, then passed both authenticated and open-panel checks after the fix.
The harness executes the Java-interpreted page script with a controlled DOM/network.

### Bounded requests and polling

All JSON requests now share a 15-second deadline, including body reads. A command
failure is visible, and timeout text explains that the server may already have applied
the operation. There is no automatic command retry. Status and marks each allow one
outstanding poll, releasing that guard on success, failure or timeout.

Five page regressions pass, including stalled headers, a stalled JSON body, recovery
with a later command, and duplicate polls while an earlier request is pending.

### Ordered control delivery

Controls and pan now share one queue. Adjacent unsent absolute values for the same
control are replaced by the latest value; autofocus, reset, relative moves and other
intervening actions retain their order. Up to 64 distinct commands may wait. Failure
cancels unsent controls with a visible explanation, rather than running them against
an uncertain camera state. No camera command is automatically retried.

Eight page regressions pass. The new checks reproduced the missing final slider value
and simultaneous pan/control requests, then verified coalescing, action ordering and
cancellation after an ambiguous timeout.

### Fresh state on the panel

A revision counter now invalidates status and marks polls that overlap a local command.
Polls do not start while controls are pending. Intermediate command replies still update
known framing, but leave controls alone until the pending input has been sent.

Ten page regressions pass, including a delayed pre-command status response, a later
external change that must still arrive, and a slider with unsent final input.

### Page asset extraction

The panel is now `backend/app/assets/panel.html`. Before comment cleanup, its bytes were
compared with the Java-interpreted page and were identical. The build packages it with
`aapt2 -A`; the service loads it once and the HTTP server serves the resulting string.
The API-help implementation stays in `WebUi.java`. No production dependency was added.

The Node harness now reads the asset directly. The obsolete Python Java-escape check
was removed; the existing hidden-overlay check now reads the asset. Builds run the
Node regressions when Node is installed and print an explicit skip otherwise.

Validation: the APK builds with 462 Java checks and ten panel regressions passing;
`assets/panel.html` in the built APK matches the source byte for byte. The full
workstation gate also passes: ruff, formatting, mypy, bandit, 79 Python tests, Go vet,
Go build and Go tests. The original checkout's virtualenv supplied Python tooling.

## Final validation and browser smoke check

The deterministic suite has 13 passing tests. It also checks HTTP 400/401/409/500
failures, queue recovery, stale marks and completion of coalesced callers.

A separate Chromium check runs the actual HTML handlers against an authenticated
localhost camera double. It passed startup, status/marks/image authentication, rapid
slider input, autofocus, stream restart and the still popup with zero page errors.
The image response is a finite PNG: this checks image URLs, not MJPEG decoding or
camera hardware. It makes no request to a real phone.

To repeat it without adding a project dependency (tested with Playwright 1.63.0):

```sh
npm install --prefix /tmp/deskcam-codex-browser --no-audit --no-fund playwright@1.63.0
/tmp/deskcam-codex-browser/node_modules/.bin/playwright install chromium
PLAYWRIGHT_MODULE=/tmp/deskcam-codex-browser/node_modules/playwright/index.mjs \
  node backend/test/webui/browser-smoke.mjs
```

## Remaining device checks

No APK was installed and no camera service was contacted. Before deploying, check the
panel on a real phone for sustained MJPEG streaming, touch gestures, idle/wake behaviour
and slow autofocus. A browser timeout does not cancel a camera operation already
received by the server, which is why pending controls are cancelled after a failure
and no command is automatically retried.

The worktree build generated its own gitignored home signing key. Its APK is a test
artifact and will not update phones installed using the original checkout's different
key. Build with the intended signing key when ready to install; do not uninstall a
phone app just to test this branch.

The trusted-network defaults and all-interface binding remain deliberate existing
behaviour, not changes made by this review. The page keeps the token from its opening
link; after changing the key, reopen it with the new link.

## Commit guide

- `4cce7db`: pre-existing working-tree snapshot, not review fixes.
- `b37437c`: scope and merge boundary.
- `6b9297d`: token propagation and initial regressions.
- `f0529e8`: deadlines and bounded polling.
- `76d172f`: ordered commands and final-input preservation.
- `4df0691`: stale-response protection.
- `7c7d7f9`: HTML asset extraction and build integration.
- The final validation commit adds the Chromium check, broader failure regressions
  and these handoff instructions.

When reconciling later work, phone-panel edits now belong in
`backend/app/assets/panel.html`, not the former `PAGE` string in `WebUi.java`.
