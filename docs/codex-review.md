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
