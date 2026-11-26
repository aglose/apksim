---
title: Instrumented Tests
---

<div class="page-hero" markdown>

# Instrumented tests &amp; CI

<p class="sim-lead">Run Compose UI, Espresso, ActivityScenario, and UiAutomator test APKs directly with <code>--instrument</code> — locally on macOS or headless in Linux CI containers, with no emulator and no KVM.</p>

<div class="sim-pills">
<span class="sim-pill">Same test APKs</span>
<span class="sim-pill">JUnit XML &amp; shards</span>
<span class="sim-pill">Per-failure bundles</span>
<span class="sim-pill">No KVM required</span>
</div>

</div>

<div class="stat-strip">
<div class="stat-card">
<div class="stat-value">4,107</div>
<div class="stat-label">Real app tests passing</div>
<div class="stat-note">100% agreement with API 34 CI emulators</div>
</div>
<div class="stat-card">
<div class="stat-value">4.7 min</div>
<div class="stat-label">On Apple Silicon Mac</div>
<div class="stat-note">vs. ~19 min on two CI emulators</div>
</div>
<div class="stat-card">
<div class="stat-value">8.3 min</div>
<div class="stat-label">In Linux Docker image</div>
<div class="stat-note">~55% fewer CI runner-minutes per PR</div>
</div>
<div class="stat-card">
<div class="stat-value">0</div>
<div class="stat-label">Emulator VMs needed</div>
<div class="stat-note">Runs on standard Ubuntu GitHub runners</div>
</div>
</div>

## Quick start

=== "Local run"

    ```bash
    # Run an entire test APK (prints standard am instrument output)
    apksim --instrument app-debug-androidTest.apk app-debug.apk

    # Filter by class or package with -e
    apksim --instrument app-debug-androidTest.apk -e class com.example.LoginTest app-debug.apk

    # Self-instrumenting library test APK (e.g. KMP androidDeviceTest): pass it twice
    apksim --instrument shared-androidTest.apk shared-androidTest.apk
    ```

=== "CI runner (`instrument.py`)"

    ```bash
    # Sharded run with JUnit XML and failure bundles
    scripts/instrument.py app-debug.apk app-debug-androidTest.apk \
      --shards 4 --xml junit.xml --bundles bundles/

    # One fresh process per test (like AndroidX Test Orchestrator)
    scripts/instrument.py app-debug.apk app-debug-androidTest.apk \
      --isolate -j 4 --xml junit.xml -- --device tablet
    ```

    Pass `-e` arguments (`class`, `notClass`, `package`, `notPackage`) directly to `instrument.py`, and put extra `apksim` flags after `--`.

=== "GitHub Actions"

    ```yaml
    - name: Device tests on apksim
      run: |
        mkdir -p out
        cp app/build/outputs/apk/debug/*.apk app/build/outputs/apk/androidTest/debug/*.apk out/
        docker run --rm -v "$PWD/out:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:latest \
          app-debug.apk app-debug-androidTest.apk --shards 4 --xml junit.xml --bundles bundles
    - uses: actions/upload-artifact@v4
      if: always()
      with:
        name: apksim-tests
        path: |
          out/junit.xml
          out/bundles
    ```

## Parallelism &amp; isolation

Choose how suites split across processes depending on your speed vs. isolation needs:

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Fastest · Default choice</div>
**Shards (`--shards N`)**

Splits tests across `N` concurrent processes into one XML report. Add `--timings prev.xml` to balance shards by measured duration instead of name hash, or `--shard I` for a CI matrix.

<div class="sim-card-link" markdown>[Parallel runs →](parallel.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Full isolation</div>
**Process per test (`--isolate -j N`)**

Matches AndroidX Test Orchestrator with `clearPackageData`. Pre-warmed standby test processes boot ahead of tests (`--prewarm`), cutting isolated wall time by 29–47%.

<div class="sim-card-link" markdown>[Pre-warmed pool →](test-pool.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Best of both</div>
**Class groups (`--group-size N`)**

Runs up to `N` tests of one class per process, then exits. Combine with `--isolate-classes REGEX` for stateful classes and `--retry-isolated N` to auto-retry failures in a clean process.

<div class="sim-card-link" markdown>[Parallel runs →](parallel.md)</div>
</div>
</div>

### Crash &amp; hang resilience

- **Crash recovery:** if a shared process dies mid-suite (native crash or stuck test), `instrument.py` marks the running test failed and continues the remaining tests in a fresh process.
- **Timeout watchdog (`--test-timeout SEC`):** default 900 s (`0` disables). Kills hung processes, captures a JUnit thread dump, and resumes the rest of the suite.

## What works

<div class="beam-security" markdown>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Supported out of the box</div>

- **Frameworks:** Compose UI (`createComposeRule`, `createAndroidComposeRule`), Espresso, `ActivityScenario`, UiAutomator, AndroidX Test rules, `Parameterized`, and custom `AndroidJUnitRunner.newApplication`.
- **Rendering:** Offscreen GLES 2.0/3.0, EGL14, and legacy EGL10 via ANGLE/Metal (macOS) or Mesa (Linux).
- **Device environment:** `ANDROID_ID`, granted normal permissions, AndroidX Startup providers, validated network, test APK assets, `--start-activities`, and `UiAutomation` shell commands (`settings`, `getprop`, `logcat`, `am broadcast`).
- **Clock modes:** Robolectric-style simulated clock by default (skips idle time); pass `--realtime-clock` for UI with real-time delays or timeouts.
</div>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Good to know</div>

- **Match CI emulators:** pass `--timezone` and `--device tablet` after `--` to mirror your existing emulator config.
- **Mockito:** `--mock-maker auto` (default) swaps ART mock makers (`dexmaker`/`mockito-android`) for Mockito's JVM subclass maker automatically.
- **Not supported:** multi-process tests waiting on another process, and `adb shell am instrument` of external test APKs.
</div>
</div>

## Failure bundles

Pass `--bundles out/bundles` to automatically capture a self-contained diagnostic folder (`out/bundles/<class>/<method>-<hash>/`) for every failing test — at zero cost to passing tests:

| File | What it contains |
|---|---|
| `screen.png`, `nodes.txt` | Screen capture and accessibility tree at the moment of failure |
| `failure.txt` | Stack trace, device settings, process test count, and capture point |
| `logcat.txt` | App logs scoped strictly to that test |
| `threads.txt`, `looper.txt` | All thread stacks and queued main-looper messages |
| `repro.sh` | Standalone script that reruns that single test with identical options |
| `process.txt` | Exit status and stderr tail if the process crashed |

Open `out/bundles/index.html` to browse every failure with thumbnails, or drop a zipped bundle into the [Trace Viewer](trace-viewer.md). Pass `-- --bundles-redact REGEX` to blank sensitive values from text logs before uploading CI artifacts (screenshots and `repro.sh` are not redacted).

## More CI tools

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Impact analysis</div>
**Affected tests**

Run only tests reachable from changed Kotlin/Java files using zero-overhead class probes (`--record-deps`, `--affected-by`).

<div class="sim-card-link" markdown>[Affected tests →](affected-tests.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Quality</div>
**Coverage &amp; flake hunter**

Export JaCoCo/LCOV/HTML coverage without rebuilding (`--coverage`), or pinpoint order vs. timing flakes (`flake-hunt.py`).

<div class="sim-card-link" markdown>[Coverage](coverage.md) · [Flake hunter](flake-hunt.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Visual testing</div>
**Screenshot goldens**

Capture deterministic golden screenshots across device, dark mode, locale, and font-scale matrices.

<div class="sim-card-link" markdown>[Screenshot tests →](screenshot-tests.md)</div>
</div>
</div>
