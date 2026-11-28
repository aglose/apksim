---
name: apksim-bug-to-pr
description: Take an Android bug from an apksim flight recording (.apkrec) to a pull request that shows it fixed and what else the change touched. Use when the user has a recording of a bug and a fix (or asks to "prove the fix", "show the PR fixes the recording", "what screens does this change touch"), and apksim is installed.
---

# From a recorded bug to pull request evidence (apksim)

You have: a recording of the bug (`bug.apkrec`), the build it was recorded on (`BASE.apk`), and a build with the fix
(`NEW.apk`, same signing key). `pr-evidence.py` ships with apksim beside `crawl.py` (a checkout: `scripts/`; Homebrew:
`$(brew --prefix)/Caskroom/apksim/*/apksim-*/scripts/`; the Linux image: on the `PATH`). Full guide:
https://apksim.web.app/latest/bug-to-pr/

## Before running anything, check

- **Local servers by name.** If the app talks to a server on this machine, the recording must have been made with
  `--network-host NAME=127.0.0.1:PORT` (the tap never reads loopback). `verify` warns "the recording holds no
  requests" otherwise: ask for a new recording, do not go on.
- **Signed-in apps.** You must never type the user's password, and the crawler types none unless given `--autofill`
  (the sign-in the user saved; never with a network recording). Only if the user says to use it, add
  `--autofill` to `baseline`: every launch then signs in. Otherwise ask the user to sign in
  once in a prepared slot (`apksim --data-slots map BASE.apk`), then add `--keep-data -- --data-slots map` to
  `baseline` (the `diff` step reuses it).
- **Depth.** `--depth N` explores screens fewer than N actions from the start. A screen exactly N away has no recorded
  dependencies; if the fix is on such a screen, use a larger `--depth`.
- Options the recording does not keep (`--network-trust CERT`, `--build`, `--permissions`) go after `--` on every step.

## The four commands

```bash
# 1. Map the base build (minutes; reuse the folder for later changes to the same base)
pr-evidence.py baseline BASE.apk --out base-map --depth 3

# 2. Replay the recording on both builds
pr-evidence.py verify bug.apkrec --base BASE.apk --new NEW.apk --out evidence/verify

# 3. Walk only the screens the change can reach, against the map
pr-evidence.py diff NEW.apk --baseline base-map --repo . --diff origin/main...HEAD --out evidence/diff

# 4. Markdown for the pull request
pr-evidence.py comment --out evidence/comment.md --verify evidence/verify --walk evidence/diff
```

Or all at once: `pr-evidence.py run bug.apkrec --base BASE.apk --new NEW.apk --work evidence --repo . --diff origin/main...HEAD`
(add `--baseline base-map` to reuse a map; in the Linux image, which has no git, `--changed PATH...` instead of `--diff`). Run them in the background if your shell call would time out: the map
takes minutes on a large app.

## Reading the verdict

`verify` prints three lines and exits 0 (PASS), 1 (FAIL) or 2 (a replay could not run).

- `base ...: matched the recording at all N points` must hold. If the base "first differs" or crashed, the recording
  does not show this build: stop and tell the user (wrong build, or launch options missing after `--`).
- `new ...: first differs at point K of N (LABEL, ...)` is where the fixed build parts from the recorded bug. Open
  `evidence/verify/new/points/KK-recorded.jpg` and `KK-replayed.jpg` and confirm the new picture is the intended
  behaviour: the replay proves a change at that step, not that it is the right change. A divergence much earlier than
  the bug's step means the fix changed something else too: say so.
- A recording that ends in a crash: the base must crash on the replay; PASS means the new build played the same steps
  without crashing.
- `new ...: matched` with FAIL: along the recorded steps nothing visible changed. The fix is not in this build, is
  behind a flag, or changes low-contrast pixels (a channel must differ by more than 64). For a refactor that should
  change nothing, run with `--expect same`.
- `requests: N recorded, M asked`: fewer asked on the new build is a request the fix removed; "without a recorded
  answer" names requests the recording lacks.

`diff` prints which file selected which screen, then per walked screen `changed: ID: text 'a' -> 'b' (... pixels in K
regions)`.

- `unplaced` files are changed sources no explored screen ran: report them as not covered, never as "no effect".
- "every screen is walked again" means the change touches app start, the manifest or a build file: expected, slower.
- Screens "left out" were never explored in the map; `--cautious` walks them too (slower, and their baseline
  captures can be flaky).
- A changed screen you did not expect is a finding: look at its pictures before calling it a regression or noise.

## What to put in the pull request

1. The content of `evidence/comment.md`: the verdict lines, the recorded/new/difference pictures, a table per changed
   screen, what was carried unchanged.
2. Its pictures are in `evidence/comment-images/` under stable names. GitHub shows only hosted images: if the
   repository has a place for them (CI artifacts with links, a bucket), upload the folder and run step 4 again with
   `--image-base URL`. Otherwise post the text and tell the user where the folder is; do not invent a host.
3. In your own words, one or two sentences: what the recording shows, where the new build differs, and any
   unexpected changed screen or unplaced file.

Do not post the comment, push or upload anywhere the user has not asked for. Never commit the recording or the
evidence folder: a recording holds the app's network calls and logs.
