# Many instances and test processes at once

apksim is one ordinary process per simulator, so a machine runs as many as its cores and memory allow: test suites
side by side, one suite split over several processes, or a fleet of instances driven over their control ports. This
page is how to do each, what was measured on a large app (2026-11-04), and the limits found. The app is the one in
[ci-testbed.md](ci-testbed.md): 4,134 device tests in 11 suites, Compose, R8, SQLite and WebRTC.

## How

**Suites at once.** Start one `instrument.py` (or one container) per suite, a few at a time, the longest first:

```bash
# two suites at a time on a 4-CPU runner; each its own container of the image
docker run --rm -v "$PWD:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:VERSION \
  shared-androidTest.apk shared-androidTest.apk --xml out/shared.xml & shared=$!
docker run --rm -v "$PWD:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:VERSION \
  app-androidTest.apk app-androidTest.apk --shards 2 --xml out/app.xml & app=$!
# wait for each by PID: a bare `wait` returns 0 whatever the suites returned
status=0; wait $shared || status=1; wait $app || status=1; exit $status
```

**One suite over several processes.** `instrument.py --shards N` runs N processes at once, each given
AndroidJUnitRunner's `-e numShards N -e shardIndex I` (`--instrument` takes them too, as a device's runner does): a
test's shard comes from its name's hash, every test runs once, and each shard recovers from a crash as one process
does. Results make one JUnit XML, with each test's time. A shard's tests share its process, so a suite that passes in
one process passes sharded unless its tests depend on running after one another.

**Shards balanced by measured time.** Hash sharding gives shards equal numbers of tests, not equal times.
`--shards N --timings XML` (an earlier run's `--xml`) splits the suite longest test first, each to the shard with the
least time so far, and names each shard's tests with `-e class`; `--shard I` runs just one shard, for N CI jobs on
separate machines. See [affected-tests.md](affected-tests.md#balanced-shards), which also covers running only the
tests a change reaches (`--record-deps`, `--affected-by`).

**Prefer one process per suite to one per test.** `--isolate` (a process per test, as the Orchestrator with
`clearPackageData`) pays a JVM start, a cold JIT and an app start for every test. apksim already gives every test a
fresh Application and data folder; only statics carry over. The app's 10 end-to-end tests: 16 s isolated, 4 at a
time, against 8 s in one process on the Mac; ~560 CPU-seconds against ~30 in the Linux image.

**A fleet of instances.** `scripts/fleet.py N APK -- CMD...` starts N headless instances, waits for each, sends every
one the same control commands at once (`tap`, `type`, `nodes`, ...; `until MS TEXT` waits for text on screen,
`expect TEXT` checks the last reply, `{i}` is the instance's number), and reports each instance's result and the
memory they took together. Each instance keeps its own app data. An instance answers `ping` before its first frame,
so start a script with an `until`.

**A class cache shared by the suites: not worth it.** In the image, translated classes go to
`/opt/apksim/work/cache`, and library DEX files are often identical across an app's test APKs (57% of the bytes of
this app's 11). But a volume there shared by every suite's container made no measurable difference: four suites at
once took 175 s with a fresh volume and 159 s with a warm one, against 154 s without. Translation is a small part of
a suite (it shows only in tiny ones: the 6-test suite took 6.6 CPU-seconds cold, 4.1 warm). Containers can share one
safely (writes go through a temporary file and a rename) if a run ever needs it.

## Measured

Each row is the whole set of 4,134 tests, wall time and CPU-seconds of all processes, every test passing. "At once" is
how many suites run at the same time, longest first; the journeys suite (76 whole-app tests, the longest) is split
into shards; the end-to-end suite runs in one process except in the first row, which is the app's CI schedule today.

**Mac (M4 Pro, 12 cores):**

| Schedule | Wall | CPU |
|---|---|---|
| one suite at a time, end-to-end isolated (4 at a time) | 167 s | 333 CPU-s |
| one suite at a time | 152 s | 215 CPU-s |
| 2 at once | 77 s | 211 CPU-s |
| 4 at once, journeys in 2 shards | 59 s | 255 CPU-s |
| 4 at once, journeys in 2 shards, C1 JIT (now the default) | 58-61 s | 151-154 CPU-s |
| 6 at once, 3 shards | 65 s | 295 CPU-s |
| 8 at once, 4 shards | 66 s | 310 CPU-s |

**Linux image, 4 CPUs (Colima on the same Mac, x86 under Rosetta), runs interleaved:** C1 is the JIT test runs now
use; C2 the JVM's default, which they used before.

| Schedule | C1 (now) | C2 (before) |
|---|---|---|
| one suite at a time, end-to-end isolated (the app's CI today) | | 670 s, 1,318 CPU-s |
| 2 at once | 191 s, 381 CPU-s | 228 s, 628 CPU-s |
| 2 at once, journeys in 2 shards | 175 s, 422 CPU-s | |
| **3 at once, journeys in 2 shards** | **133-137 s, 431-440 CPU-s** (3 runs) | 312 s, 1,158 CPU-s |
| 3 at once, journeys in 3 shards | 142 s, 486 CPU-s | |
| 4 at once, journeys in 2 shards | 154-175 s, 452-525 CPU-s | 225-457 s, 789-1,617 CPU-s |

So on 4 CPUs: three suites at once, the longest split in two, five times faster than one after another with a third
of the CPU. The VM shares the Mac's cores and Apple's virtualization reports no steal time, so a busy host slows a run
without showing it: one run of the best schedule took 261 s. Compare runs made back to back, as these were.

**In the app's own CI (GitHub's ubuntu-24.04, 4 vCPU, native x86; 2026-11-05):** the same schedule (three suites at
once, the journeys in 2 shards, the end-to-end tests in one process) on the released 0.8.0 image:

| The device-test step | Wall |
|---|---|
| 0.5.0, one suite at a time, end-to-end isolated: 10 recent green runs | median 310 s (265-349 s) |
| 0.8.0, three at once | **140 s**, all 4,144 tests passing, none flaky |

Each suite takes longer side by side (the journeys 95 → 115 s, the shared module 58 → 89 s), the end-to-end tests less
(82 → 29 s, one process instead of ten), and the whole set 2.2 times less. The runner came out close to the Mac's VM
(147 s there with the same image and script). The job's Gradle build of the test APKs (4-11 minutes) is now most of it.

**Instances at once (`fleet.py`, the test app, Mac):** each instance started cold, waited for its UI, tapped the
FAB three times and checked the count, opened Trips and checked a trip, then ran the Shader tab for 2 s.

| Instances | All passed | Total time | Memory together |
|---|---|---|---|
| 4 | yes | 8 s | 1.4 GB |
| 8 | yes | 13 s | 2.6 GB |
| 16 | yes | 25 s | 4.3 GB |
| 24 | yes | 38 s (ready in 21-24 s) | 6.3 GB |
| 32 | yes | 62 s (ready in 26-36 s) | 7.0 GB |

Throughput levels off between 16 and 24 instances on 12 cores (about 38 scripts a minute); 32 still all pass, each
more slowly. Memory is not the limit: ~220-340 MB resident each.

## Spending less per instance

Launch options for what a journey does not need (AGENTS.md, "Per-instance costs"): `--max-fps N`, `--video
on|blank|off`, `--audio on|mute|off`, `--anim-scale N`. Each is per instance, so instances of one fleet or one CI job
can differ, and warm launches take them. With fleet.py: `fleet.py N APK -- --max-fps 30 --video blank -- CMD...`.

Measured 2026-11-05 on the Mac, one headless instance of the test app, CPU-seconds of the whole process over the
window (one run each; the machine was otherwise quiet):

| Launch | Home, idle 5 s | Shader tab 6 s (fps) | First clip play (9 s) | Second play |
|---|---|---|---|---|
| default, before the grid fix | 0.3 | 6.9 (105) | 3.7 | 1.0 |
| default | 0.4 | 4.1 (120) | 3.7 | 0.7 |
| `--max-fps 60` | 0.2 | 2.6 (60) | 2.6 | 0.5 |
| `--max-fps 30` | 0.4 | 1.3 (30) | 3.3 | 0.6 |
| `--video blank` | 0.2 | 4.3 (120) | 2.1 | 0.6 |
| `--video off` | 0.2 | 3.7 (120) | 2.1 | 0.5 |
| `--audio off` | 0.3 | 3.6 (120) | 3.2 | 0.5 |

The clip is the test app's 3 s 640x360 HEVC + AAC (Settings, "Play clip"); a first play includes loading Media3's
classes, which is the app's own cost and stays. apksim's own share of a play with video on is small: ~15 ms of
VideoToolbox decoding and ~5-50 ms of drawing for 90 pictures (`video` counters), plus a screen frame per picture.
With blank or off the clip still ends at 3.0 s. "Before the grid fix": the frame loop spun the main thread between
grid lines whenever anything animated (AGENTS.md, Hard-won rules), so every headless instance and every hidden
window paid about half a core extra while animating. So for many instances: cap the frame rate first (anything animating
costs in proportion), then video, which only matters on screens that play it. Memory hardly moves with any of
these: resident size was 330-500 MB in every run, the peak set by the player's own buffers.

## What sets the limits

- **CPU, not memory.** One test process keeps 1-1.5 cores busy (the test's thread, the app's coroutines and render
  thread, JIT, GC), so a 4-CPU runner is full with three. Past that, processes slow each other more than linearly with C2: three
  suites at once took 1.8 times the CPU of two in the Linux image (1,158 against 628 CPU-seconds), the JIT compiling
  under contention (and every compiled method going through Rosetta again there). With the JIT stopped at C1 it was
  1.1 times (436 against 381), which is why test runs now stop there (`--jvm -XX:TieredStopAtLevel=4` brings C2 back).
- **Real-time waits.** One journey waits for the app's 30-second poll in real time, as it would on a device: the
  suite cannot finish in less, however many shards. Past 4 suites at once, the Mac run stays at ~60 s for that reason.
  Sharding helps until the shard holding the slowest test is the last one left.
- **Starting processes.** Each JVM start costs several CPU-seconds before any test (more under Rosetta), so prefer
  fewer, longer processes: shards over `--isolate`, and only as many shards as there are cores to run them.

Two apksim bugs surfaced only with many processes at once, both macOS-only and fixed:
- A `CLOCK_MONOTONIC` condition variable's timed wait returned at once (the shim ignored `pthread_condattr_setclock`
  and Apple's `pthread_cond_timedwait` reads deadlines as wall-clock time), so two of WebRTC's threads spun a core
  each for as long as the app ran: a journey that waits 30 s took 41.5 CPU-seconds, now 10.6.
- A short run ending while WebRTC's threads were still starting crashed the exit (a JNI call from native code during
  VM exit read a signature JVMTI no longer gives), failing processes whose tests had passed. Such a thread now waits
  for the process to end.
