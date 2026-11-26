---
title: Crawler
---

<div class="page-hero" markdown>

# Automated screen crawler

<p class="sim-lead">Walk an unmodified APK automatically through its accessibility tree with zero test code — mapping every reachable screen, capturing device/theme matrices, diffing two builds, and sweeping for crashes and UI faults.</p>

<div class="sim-pills">
<span class="sim-pill">Zero test code</span>
<span class="sim-pill">Interactive HTML screen map</span>
<span class="sim-pill">Incremental PR crawls</span>
<span class="sim-pill">Resilience sweeps</span>
</div>

</div>

## What the crawler does

<div class="sim-cards sim-cards--2" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Explore</div>
**Screen map &amp; matrix captures**

Explores breadth-first within a time/screen/depth budget, records the shortest replayable path to every screen, and captures each screen across devices, dark mode, locales, and font scales into `report.html` + `crawl.json`.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Compare</div>
**Build-to-build visual &amp; structural diffs**

Pass `--base old.apk` (or `--seed` + `--diff-seed`) to highlight new, removed, and visually changed screens with pixel diff maps and semantic control diffs.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Incremental CI</div>
**Per-screen dependency tracking**

Record which classes, source files, and resources each screen executes (`--record-deps`), then use `crawl-select.py` on PRs to re-walk only the screens touched by changed files.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Stress test</div>
**Resilience sweep (`--sweep all`)**

Tests every discovered screen against process death, activity recreation, rotation, offline mode, denied permissions, `2.0×` font scale, RTL (`ar-EG`), and dark mode — outputting JUnit XML with repro steps for crashes, lost state, endless spinners, and truncated text.
</div>
</div>

## Common workflows

=== "Explore & capture matrix"

    ```bash
    scripts/crawl.py app-debug.apk --out crawl/ \
      --max-screens 40 --max-seconds 300 \
      --matrix device=phone,tablet --matrix appearance=light,dark
    ```

=== "Compare two builds"

    ```bash
    scripts/crawl.py app-new.apk --base app-old.apk --out crawl-diff/
    ```

=== "Incremental PR crawl"

    ```bash
    # 1. On main: record a baseline screen map with per-screen dependencies
    scripts/crawl.py base.apk --out base-crawl/ --record-deps --standby

    # 2. On a PR: pick only screens affected by changed files and diff against the baseline
    scripts/crawl-select.py base-crawl/ --diff origin/main...HEAD --repo . --out-ids affected.ids
    scripts/crawl.py pr.apk --out pr-crawl/ --seed base-crawl/ --only-file affected.ids --diff-seed
    ```

=== "Resilience sweep"

    ```bash
    scripts/crawl.py app-debug.apk --out sweep/ \
      --sweep all --allow crawl-allow.txt
    ```

    Supported perturbations: `process-death`, `recreate`, `rotate`, `offline`, `permission-deny`, `fontscale[=2.0]`, `rtl[=ar-EG]`, `dark`, or `all`. Writes `sweep/junit.xml`.

## Key options

| Option | What it does |
|---|---|
| `--out DIR` | Output directory (`report.html`, `crawl.json`, `screens/`, `diff/`, `logs/`) |
| `--start URL` | Extra deep-link starting points (repeatable); merges all roots into one graph |
| `--max-screens N`, `--max-seconds S`, `--max-depth N` | Bound exploration size, wall time, and navigation depth |
| `--matrix AXIS=V,V` / `--matrix-file SPEC.json` | Capture every screen across `device`, `appearance`, `locale`, and `fontscale` |
| `--standby` | Reuse a shared warm-launch standby pool for much faster crawls |
| `--keep-data` | Start from a pre-signed-in data slot (cloned per worker; enables `--biometrics auto`) |
| `--freeze-network`, `--deterministic` | Freeze server answers into one HAR recording so live feeds replay identically |
| `--base APK`, `--seed RUN --diff-seed` | Compare against a base build or diff incrementally against a seed crawl |
| `--record-deps` | Record per-screen classes, source files, and resolved resources for `crawl-select.py` |
| `--sweep LIST`, `--allow FILE` | Run resilience checks on every screen and optionally allow-list known issues |
| `-j N` | Number of parallel headless instances (default `3`) |

## Built-in safety defaults

- **Destructive controls skipped:** buttons matching words like *delete, remove, sign out, buy, pay, checkout, subscribe, send, submit, publish, uninstall* are never tapped and appear as skipped in the graph (`--skip-text REGEX` customizes the list).
- **Unlabeled controls skipped** by default (`--tap-unlabeled` enables them), and text fields are never filled unless `--fill TEXT` is supplied.
- **Clean replays:** if tapping a control modifies app data or preferences, the worker resets its slot before exploring the next branch so earlier mutations never pollute later screens.

<p class="spec-note">For full details on screen identity hashing, flaky-screen retries, and <code>crawl-select.py</code> JSON output, see <a href="https://github.com/aglose/apksim/blob/main/docs/crawl.md">docs/crawl.md on GitHub</a>.</p>
