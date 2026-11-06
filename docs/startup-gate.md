# Startup gate

Launch time is apksim's first goal (a warm launch's first frame in ~0.22 s for the test app), and nothing else in CI
would notice it creeping back up. The `startup-gate` job in `.github/workflows/ci.yml` times launches of every pull
request and every push to main on an Apple Silicon runner and fails when startup gets slower than its budget.

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
  followed by the next standby booting, seconds of CPU that would otherwise slow whichever launch came next.
- Two launches of each kind per side first, not counted (the class cache for the APK, the first standby).
- Medians, not means: one stalled launch moves a median by one place.
- A metric is **over budget** when this commit's median exceeds `anchor median × (1 + max_slower) + slack_ms` **and** a
  one-sided Mann-Whitney test gives p < `alpha` (0.01): slower, and not by chance.
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
  "runs": 12,                 // rounds per batch
  "alpha": 0.01,
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
