---
title: Replay
---

<div class="page-hero" markdown>

# Replay, bisect &amp; journeys

<p class="sim-lead">Turn any <code>.apkrec</code> flight recording into a deterministic reproduction, an automated build bisect, a reusable UI journey test, or a verification run on a physical Android device.</p>

<div class="sim-pills">
<span class="sim-pill">Deterministic checkpoint restore</span>
<span class="sim-pill">Offline HAR playback</span>
<span class="sim-pill">Automated git-bisect for APKs</span>
<span class="sim-pill">Zero-code journey tests</span>
</div>

</div>

## Four workflows from one recording

<div class="sim-cards sim-cards--2" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Reproduce</div>
**Replay in apksim (`apksim replay`)**

Restores the checkpoint's activity stack, app data, device config, and wall clock, replays inputs at recorded offsets against the recorded HAR, and reports the first frame that diverges.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Pinpoint regressions</div>
**Bisect builds (`bisect-rec.py`)**

Binary-searches an ordered folder of APKs (`O(log N)` replays) to find the exact first build that diverges or crashes, producing a self-contained HTML report with side-by-side frames.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Turn bugs into tests</div>
**Record-to-journey (`--journey`)**

Converts taps into element-targeted steps (text, content description, or resource ID / Compose `testTag`) with automatic screen expectations, runnable across devices via `journey.py` with JUnit XML.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Cross-check hardware</div>
**Replay on a real device (`replay-adb.py`)**

Replays a `.apkrec` recording onto a connected Android phone or emulator over `adb` (backing up app data first) to distinguish app bugs from simulator differences.
</div>
</div>

## Commands

=== "Replay a bundle"

    ```bash
    # Replay in a window with recorded network answers
    apksim replay session.apkrec

    # Headless check (for CI or scripts)
    apksim replay --headless session.apkrec; echo $?

    # Replay against a newer build (same signing key)
    apksim replay --apk app-new.apk session.apkrec

    # Keep the app open after replay so you can inspect or continue interactively
    apksim replay --control auto session.apkrec
    ```

=== "Bisect a regression"

    ```bash
    # Binary-search a folder of APKs sorted oldest to newest
    scripts/bisect-rec.py session.apkrec builds/

    # Run inside the Linux Docker image
    scripts/bisect-rec.py session.apkrec --builds builds.txt --image ghcr.io/aglose/apksim:latest
    ```

=== "Convert to a journey test"

    ```bash
    # 1. Generate journey.json with node-based targets and expected texts
    apksim replay --journey --headless --out out session.apkrec

    # 2. Run on any build or device form factor and emit JUnit XML
    scripts/journey.py out/journey.json --apk app-new.apk --xml results.xml -- --device tablet
    ```

=== "Replay on a physical device"

    ```bash
    # Preview the plan first
    scripts/replay-adb.py session.apkrec -s SERIAL --dry-run

    # Execute on the device (backs up existing app data first)
    scripts/replay-adb.py session.apkrec -s SERIAL --yes
    ```

## Options &amp; exit codes

Pass replay options before the bundle path, and any standard `apksim` launch flags (`--headless`, `--control auto`, `--device`, `--network-trust`) after them:

| Option | What it does |
|---|---|
| `--apk FILE` | Replay on another APK signed by the same certificate |
| `--live-network` | Hit the real network instead of answering from the bundle's HAR |
| `--out DIR` | Write `report.json`, recorded/replayed frames, and diff images into `DIR` |
| `--compare all\|end\|none` | Compare frames before every gesture (`all`, default), only at the end (`end`), or never (`none`) |
| `--threshold N` | Max differing pixels (or fraction `< 1`) before marking a point diverged |
| `--journey` | Also write `journey.json` with element-targeted steps and assertions |

| Exit code | Meaning |
|---|---|
| `0` | Every comparison point matched |
| `3` | Screen diverged (see `report.json` and `points/NN-diff.png`) |
| `10` | App crashed during replay |
| `2` | Cannot replay (missing checkpoint, different signer, or missing activity) |

<p class="spec-note">For full details on divergence thresholds, fling handling, and journey step syntax, see <a href="https://github.com/aglose/apksim/blob/main/docs/replay.md">docs/replay.md on GitHub</a>.</p>
