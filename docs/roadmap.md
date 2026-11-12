# Roadmap: 14 features over three releases (started 2026-11-10)

The building blocks of the last week (flight recorder, network replay, snapshots, per-method probes, fleets, kept
sessions, the accessibility tree, `settle`) exist but are mostly used alone. These features combine them. All of them
are worked on at once, in nine lanes; each pull request carries a label saying which release it belongs to.

[plan.md](plan.md) stays the record of earlier phases. This file is the plan for these features only.

## Releases

| Label | Items | Theme |
|---|---|---|
| `roadmap-r1` | 1, 3, 5, 8 (first batch), 9, 11 | Foundations: no item depends on another |
| `roadmap-r2` | 2, 4, 6, 7, 10, 12, 8 (second batch) | What builds on release 1 |
| `roadmap-r3` | 13, 14, 8 (third batch) | Reach: agents, linked devices |

How the labels work with the release skill: `roadmap-r1` pull requests are opened ready for review; `roadmap-r2` and
`roadmap-r3` ones are opened as **drafts**, and the release skill skips drafts. To cut the second release, mark the
`roadmap-r2` drafts ready (`gh pr ready N`), then run the release; the same for the third. A pull request that stacks
on another names its parent's branch as its base, and merges after it: pull requests are squash-merged, so once the
parent is on `main` the stacked branch is moved there with `git rebase --onto origin/main <parent's old tip>` and its
base set to `main`.

## Items

**Track A: record once, reuse everywhere**
1. **Replay inside apksim** (r1). `apksim replay X.apkrec`: the checkpoint's data and stack restored, the recorded
   input fed at its offsets, the network answered from the recording, divergence from the recorded frames reported.
2. **Recording to test** (r2, needs 1). A recording becomes a journey that targets nodes, not coordinates, with a
   runner that writes JUnit XML.
3. **Trace viewer** (r1). One static page that opens a `.apkrec` or a failure bundle as a timeline of frames, input,
   network, logcat and SQLite writes.
4. **Bisect a recording across builds** (r2, needs 1). Replay one recording against an ordered set of builds and
   report the first that diverges.

**Track B: CI speed and trust**
5. **Pre-warmed test processes** (r1, issue 205). `instrument.py --isolate` hands each test to a JVM that already has
   the framework and the APKs' classes loaded.
6. **Flake hunter** (r2, after 5). Selected tests run many times under shuffled order, CPU pressure and clock modes;
   order-dependent failures are reduced to the smallest ordering that fails.
7. **Coverage without rebuilding** (r2). The translator's probes at method and line level, merged across processes,
   exported as JaCoCo XML and LCOV; a mapping file for R8 builds.
8. **AndroidX conformance sweep** (r1 to r3, in batches). AndroidX libraries' own device tests run here and on the
   emulator; each difference is traced, fixed and given a repro in the test app.

**Track C: product managers, designers, QA**
9. **PR preview with side-by-side** (r1). A compare mode in `serve` (one input stream to two builds, a difference
   overlay) and a GitHub Action that comments preview links on a pull request.
10. **Report button and network inspector in the tab** (r2, after 9). One click packs a recording, the device's
    settings and a note; a panel lists live requests and can rewrite a response. Both off unless the server allows.

**Track D: automated exploration**
11. **Crawler and screen map** (r1). The app explored through the accessibility tree into a graph of screens, each
    captured across a device, appearance, locale and font-scale matrix; with a base build, a report of what changed.
12. **Resilience sweep** (r2, needs 11). On each crawled screen: process death, rotation, offline, permission denial,
    large fonts, right-to-left; findings as JUnit failures.

**Track E: reach**
13. **MCP server for agents** (r3). `apksim mcp`: launch, nodes, taps, screenshots, logs, device controls, each
    action settled and answered with a compact tree; a session saved as a recording that item 1 replays.
14. **Scripted BLE peripherals and linked instances** (r3). Fake peripherals with advertisements and GATT services
    that the app scans and connects to; scenes that drive several instances in step.

## Lanes and models

One session per lane, each in its own worktree and branch. Items in one lane are done in order by the same session,
so its context is reused and two sessions never edit the same area. Models are chosen by what the work touches: the
runtime's internals (the Host loop, the recorder, standbys, shadows) get the stronger model; scripts, pages and
`serve/` get the cheaper one. Mechanical steps inside any lane (PR text, labels, merges of `main`) go to the cheapest.

| Lane | Items | Model | Mostly touches |
|---|---|---|---|
| replay | 1 → 4 → 2 | Opus | `rec/`, `FlightRecorder`, `Host`, new scripts |
| viewer | 3 | Sonnet | a new static page |
| test processes | 5 → 6 | Opus | `Instrument`, `Standby`, `scripts/instrument.py` |
| coverage | 7 | Sonnet | `dex/Probes`, a new script |
| conformance | 8 | Opus | `TestActivities`, shadows, the test app's suite |
| serve | 9 → 10 | Sonnet | `serve/`, `serve.html`, a new Action |
| crawler | 11 → 12 | Sonnet | new scripts, `snapshot.py`'s matrix |
| mcp | 13 | Sonnet | a new script |
| ble | 14 | Opus | `Bluetooth`, new shadows, `scripts/fleet.py` |

The session that wrote this plan coordinates: it reviews what the lanes report, resolves overlaps and keeps this file
current. Subagents for PR text, labels and thread replies use Haiku.

## Rules every lane follows

- AGENTS.md first; a worktree gets a `work/` of its own ("Hard-won rules").
- Nine sessions share one Mac (12 cores, 24 GB): `APKSIM_STANDBY=0` unless warm launches are what is being tested,
  `--control auto`, at most `-j 3`, instances quit when done, Docker images tagged per lane. A one-off timing taken
  while other lanes run is noise; only interleaved A/B runs count.
- Each feature ships with a check that fails without it (a script and one line in `scripts/ci-smoke.sh`, or tests in
  the test app's suite) and with measured numbers in a doc of its own (`docs/<feature>.md`).
- To keep merges clean, a lane adds only a line or two to AGENTS.md; the release's docs pass writes the rest.
- Where two lanes meet: the coverage lane leaves `instrument.py`'s process handling to the test-processes lane; the
  viewer lane leaves routes in `serve/` to the serve lane; the conformance lane's fixes are small pull requests of
  their own.
- Lanes open pull requests and do not merge them.

## Status

As of 2026-11-12, release 0.31.0. This table says where each item stands. What changes by the hour (check
results, draft state, the base branch) is on each pull request's page. "In review" means open and not merged.

| Item | Lane | Label | Pull request | State |
|---|---|---|---|---|
| 1 Replay | replay | r1 | #254 | in 0.27.0 |
| 2 Recording to test | replay | r2 | #262, #270, #285 | in 0.27.0; a fix in 0.28.0; more fixes (#285) in 0.29.0 |
| 3 Trace viewer | viewer | r1 | #251, #271 | in 0.27.0; base64 request bodies fixed in 0.28.0 |
| 4 Bisect | replay | r2 | #258 | in 0.27.0 |
| 5 Pre-warmed test processes | test processes | r1 | #257 | in 0.27.0 |
| 6 Flake hunter | test processes | r2 | #261, #284 | in 0.28.0; a fix in 0.29.0 |
| 7 Coverage | coverage | r2 | #255 | in 0.28.0 |
| 8 Conformance sweep | conformance | r1-r3 | #265, #266, #267, #272, #273, #283, #286, #296, #302 | first batch in 0.27.0; the main-thread latch fix (#272) and batch 2 (#273) in 0.28.0; endlessly repeating animators (#286) and main-thread waits under the real-time clock (#283) in 0.29.0; batch 3 (#296) in 0.30.0; UiAutomation scroll event sources (#302) in 0.31.0; AndroidX's own suites not built |
| 9 PR preview, compare | serve | r1 | #252 | in 0.27.0 |
| 10 Report button, inspector | serve | r2 | #259 | in 0.28.0 |
| 11 Crawler | crawler | r1 | #260 | in 0.27.0 |
| 12 Resilience sweep | crawler | r2 | #263 | in 0.28.0 |
| 13 MCP server | mcp | r3 | #250, #293 | in 0.29.0; fixes in 0.30.0 |
| 14 BLE, linked instances | ble | r3 | #253, #256, #292 | in 0.29.0; scene fixes in 0.30.0 |
