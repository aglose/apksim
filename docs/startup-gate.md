# Startup gate

Launch time is apksim's first goal (a warm launch's first frame in ~0.22 s for the test app), and nothing else in CI
would notice it creeping back up. The `startup-gate` job in `.github/workflows/ci.yml` times launches of every pull
request and every push to main on an Apple Silicon runner and fails when startup gets slower than its budget. A
pull request that changes nothing a packaged Mac launch is built from (only docs, the test app, `docker/`,
`native/linux/`, CI's check scripts or Python tools: the `changes` job decides) skips it, which counts as passed.

## What it measures

`scripts/startup-gate.py` launches one APK with two apksim installs and times each launch from the launcher's start
(`APKSIM_T0`) to the app's first frame, as `scripts/bench.py` does:

- **warm first frame**: the launch handed to a standby (the normal case on a Mac; `handoff` must be in its marks, or
  the launch is counted as failed rather than as a slow warm one),
- **cold first frame**: `APKSIM_STANDBY=0`, a new JVM with the AOT cache.

Both headless and with `--no-network`. The two installs are:

- **the anchor**: a published release, named in `scripts/startup-gate.json` (`anchor`), downloaded from GitHub
  Releases and checked against its `SHA256SUMS`;
- **this commit**, built (`scripts/setup.sh --no-train`) and packaged (`scripts/package-release.sh`) as a release
  would be.

Both are trained the same way, on the same APK (`apksim --train --headless APK`, each with its own `APKSIM_HOME`), and
both launch the anchor's own test app (`apksim-testapp-VERSION.apk` from the same release), so the APK never changes under the
numbers: only the runtime does.

## Why against an anchor, and why it is reliable

A hosted runner's speed varies by tens of percent from one job to the next (other tenants, a different host), so an
absolute budget in milliseconds would either fail at random or allow large regressions. Inside one job the two installs
see the same machine, so the gate judges only how they compare:

- Rounds of four launches (anchor and this commit, warm and cold) in a shuffled order, so a slow stretch of the
  machine hits both sides alike. Before each launch every standby of both installs must be ready: a warm launch is
  followed by the next standby booting, seconds of CPU that would otherwise slow whichever launch came next. Then the
  machine must be at least 80% idle over 0.3 s (up to 5 s of waiting); each launch's record keeps the figure
  (`cpu-idle-before` in the JSON).
- Two launches of each kind per side first, not counted (the class cache for the APK, the first standby).
- Medians, not means: one stalled launch moves a median by one place.
- A metric is **over budget** when this commit's median exceeds `anchor median × (1 + max_slower) + slack_ms` **and** a
  one-sided Mann-Whitney test gives p < `alpha` (0.05): slower, and not by chance.
- Rounds come in batches of `runs`, and each batch is judged on its own launches, never pooled with earlier ones: a
  metric fails when **two** batches are over budget. A batch over its limit (significant or not) is followed by
  another, at most three in all, so a slow stretch of the machine during one batch does not fail the job, and a fresh
  batch within budget clears it. A median still over its limit after three batches without a second significant one is
  reported as inconclusive and does not fail the job. (On a machine overloaded about 30 times over, load average 100,
  a 50 ms regression, +28-43% warm, stayed inconclusive this way. Pooling the batches or pairing each round's two
  launches did not separate it either: the machine's speed changed by 2x between batches. A hosted runner running
  only this job is far from that.)
- A gated metric no launch printed (a mark that needs `-Dapksim.timing=verbose`, a typo) ends the run with exit 2
  rather than passing unchecked.
- A standby that dies while booting is started again and reported; it is a crash to look into, not a startup time.

Why a fixed anchor and not the pull request's merge base: comparing with the merge base catches a single large
regression but lets startup creep up by a few percent per pull request for ever. Against a fixed anchor the budget is
for the total, so creep adds up until it fails, and moving the anchor is a decision someone makes in a pull request.

On GitHub's `macos-15` runner (3 cores, 2026-11-06, PR #53, this commit identical to the anchor, 12 rounds): warm
774 against 714 ms (-7.8%), cold 3124 against 3300 ms (+5.6%), passed. Launches there are ~3.5 times slower than on the
development Mac and spread more: ±16% warm, ±11% cold (half the interquartile range), from launch to launch rather
than drifting (pairing each round's two launches left it at ±18% and ±10%). Hence the idle wait, and batches of 20
rounds judged at p < 0.05: two batches must agree, so false failures stay rare at that level. Simulated by resampling
that run's 24 launches of each kind (both sides) (500 runs of the gate's whole decision per cell), the share of runs that fail:

| change | warm | cold |
|---|---:|---:|
| none | 0% | 0% |
| +5% | 1.4% | 0.2% |
| +10% (the budget) | 7.6% | 11% |
| +15% | 43% | 60% |
| +20% | 76% | 90% |
| +30% | 99% | 99% |

(At p < 0.01 with 16 rounds, +20% warm failed only 34% of the time.) So a single pull request has to cost about a
fifth of the launch time to be caught every time; smaller slowdowns add up against the fixed anchor until they are.

Measured on the development Mac (2026-11-05), 10 rounds, this commit identical to the anchor: warm 216 against 222 ms (+3%),
cold 1272 against 1278 ms (+0.4%), each ± 3-4% (half the interquartile range). With a 50 ms `Thread.sleep` added to
`Main` it failed on the first batch and again on the second (warm 236 against 296 ms, +25%, p < 0.001), with the
machine under heavy load from other builds (load average 5-37) part of the time. Under the heaviest load the spread
grew to ±115 ms and the first batch was not significant, which is what the extra batches are for.

## Budgets and moving the anchor

`scripts/startup-gate.json`:

```jsonc
{
  "anchor": "v0.11.0",        // the release measured against
  "runs": 20,                 // rounds per batch
  "alpha": 0.05,              // per batch; two batches must be over budget
  "metrics": {"warm first frame": {"mode": "warm", "mark": "first-frame", "max_slower": 0.10, "slack_ms": 15}, ...}
}
```

`mark` is any `[apksim] NAME N ms` line a launch prints (`-Dapksim.timing=verbose` adds finer ones), so a stage such
as `activity-resumed` can be gated too. `"gate": false` reports a metric without failing on it.

- **Startup got faster** (the report says so): once that is in a release, set `anchor` to it in a pull request. The
  budget then holds the gain.
- **A slower startup is wanted** (a feature that costs time at launch): raise `max_slower` or `slack_ms` in the same
  pull request and say why in its description. A release with it can then become the anchor, and the budget goes back.
- **A new anchor fails to download or launch**: the job needs the release's `apksim-VERSION-macos-arm64.tar.gz`,
  `apksim-testapp-VERSION.apk` and `SHA256SUMS` (release.yml publishes all three).

## A self-hosted Mac

GitHub's `macos-15` runners are sometimes all taken, and the job then waits for one, for an hour or more. The
repository variable `STARTUP_GATE_RUNNER` sends the job to a self-hosted Apple Silicon Mac instead:

```bash
gh variable set STARTUP_GATE_RUNNER --body '["self-hosted","macOS","ARM64"]'   # use the Mac
gh variable delete STARTUP_GATE_RUNNER                                         # back to macos-15
```

GitHub has no fallback: with the variable set and the Mac asleep, off or its runner stopped, the job waits for it
(up to a day). Delete the variable before closing the Mac, or start a waiting job again after deleting it.

The repository is public, so a pull request's code is anyone's code, its workflow files included. `ci.yml` sends the
job to the Mac only for pushes, manual runs and pull requests from this repository's own branches, but that is only
routing: a fork's pull request runs the fork's `ci.yml`, which could name the runner's labels in any job. A personal
account's repository has no runner groups to restrict a runner to a workflow, so the Mac enforces it itself, outside
anything a pull request can change: `scripts/runner-job-started.sh`, installed as the runner's job-started hook, fails
every job before its first step unless it is `ci.yml`'s `startup-gate` of this repository for a push, a manual run, or
a pull request whose head branch is in this repository (read from GitHub's event payload, which a fork cannot change).
The runner runs the hook as a step of the job, and a failed step only fails the job: a later step with `if: always()`
or `failure()` would still run. So the hook does not just exit non-zero: it kills the job's `Runner.Worker`, and the
runner reports the job failed before any of its steps have run. Keep "Require approval for all external
contributors" on (Settings → Actions → General) as well, and read a fork's workflow changes before approving a run.

Setting the Mac up, once:

- A standard (not admin) macOS user of its own for the runner, holding none of the owner's files, keys or notes: a job
  runs as that user. Its session must stay logged in (fast user switching) for the runner's LaunchAgent to run.
- As that user: Settings → Actions → Runners → New self-hosted runner → macOS, ARM64, and follow its commands
  (`config.sh` with the registration token it shows; the default labels are the ones above), but do not run `run.sh`
  or start the service yet: a runner that is up takes any job queued for its labels, and without the hook nothing
  refuses one.
- The job-started hook: copy `scripts/runner-job-started.sh` outside the runner's folder
  (`~/actions-runner-hooks/job-started.sh`; a job must not be able to rewrite it) and add
  `ACTIONS_RUNNER_HOOK_JOB_STARTED=/Users/USER/actions-runner-hooks/job-started.sh` to the runner's `.env`.
- Only then `./svc.sh install` and `./svc.sh start`. A job's log starts with "self-hosted runner hook: ... allowed";
  a refused one ends right there, failed. After changing `.env` later, restart it (`./svc.sh stop`, `./svc.sh start`).
- Check the hook once on the real runner before setting the variable: push a scratch branch with a workflow that
  targets the Mac from a job the hook must refuse, with a step that would run after a failure, e.g.
  `.github/workflows/hook-check.yml`:
  `on: push` / `jobs: probe: runs-on: [self-hosted, macOS, ARM64]` / `steps: - if: always()` /
  `run: touch /tmp/hook-check-ran`. The job must end failed after "refusing this job ... ending the job", with no
  `/tmp/hook-check-ran` on the Mac, and the runner must show Idle again (Settings → Actions → Runners). Then delete
  the branch and run `ci.yml` by hand on main (`gh workflow run ci.yml`, with the variable set for that run) to see
  `startup-gate` allowed.
- The job needs the Xcode command-line tools (system-wide), `python3` and `gh` on the runner's
  `PATH` (`config.sh` records the `PATH` it was run with in `.path`: include `/opt/homebrew/bin`).
- On power, the Mac must not sleep: System Settings → Battery → Options → "Prevent automatic sleeping on power adapter
  when the display is off". The job holds it awake itself while it runs (`caffeinate`), but a sleeping Mac never
  picks a job up.

What changes on the Mac: Spotlight is left alone (on `macos-15` the job turns indexing off), and its standbys are
stopped at the end even when the run is cancelled. It uses its own installs and `APKSIM_HOME`s under the runner's
temporary folder, so it neither sees nor stops anyone's own instances or standbys. Launches there are ~3.5 times
faster than on `macos-15`, so the job takes a fraction of the time; other heavy work on the Mac (emulators, builds)
makes batches noisier, which the gate turns into extra batches or an inconclusive result rather than a false failure.

## Running it locally

```bash
gh release download v0.11.0 -D /tmp/anchor -p 'apksim-0.11.0-macos-arm64.tar.gz' -p 'apksim-testapp-0.11.0.apk'
tar -xzf /tmp/anchor/apksim-0.11.0-macos-arm64.tar.gz -C /tmp/anchor
APKSIM_STANDBY=0 APKSIM_HOME=/tmp/anchor/state /tmp/anchor/apksim-0.11.0-macos-arm64/apksim --train --headless \
  /tmp/anchor/apksim-testapp-0.11.0.apk
python3 scripts/startup-gate.py --base /tmp/anchor/apksim-0.11.0-macos-arm64 --base-home /tmp/anchor/state \
  --head . --apk /tmp/anchor/apksim-testapp-0.11.0.apk
```

`--head .` measures this checkout as it is (after `scripts/build.sh` and `scripts/train.sh --headless APK`, the
benchmark APK, as CI trains); a source checkout and a
packaged release can differ by a few percent (the release's jlink'ed JDK), so for numbers that compare with CI's,
package it first as the job does. Close other heavy work first: the gate tolerates noise by measuring longer, not by
ignoring it. `--json FILE` keeps every launch's marks, batch by batch; `--runs N` overrides the rounds.
`startup-gate.py --self-test` checks the decision rules on synthetic numbers (CI runs it first).
