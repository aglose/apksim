---
title: Automation
---

<div class="page-hero" markdown>

# Automation &amp; control channel

<p class="sim-lead">Start any instance with <code>--control PORT</code> (or <code>auto</code>) to drive it over a simple, line-based TCP protocol — one command per line, one reply per line.</p>

<div class="sim-pills">
<span class="sim-pill">drive.py · single instance</span>
<span class="sim-pill">ctl.py · simctl-style CLI</span>
<span class="sim-pill">fleet.py · parallel runs</span>
<span class="sim-pill">Accessibility tree &amp; settle</span>
</div>

</div>

## Three ways to drive instances

=== "By port (`drive.py`)"

    ```bash
    apksim --control 7400 app.apk &
    scripts/drive.py 7400 'tap 720 1690' 'wait 1000' 'shot /tmp/home.png' 'quit'
    ```

=== "By running instance (`ctl.py`)"

    ```bash
    scripts/ctl.py list                                                      # list active instances
    scripts/ctl.py @myapp appearance dark ';' wait 500 ';' shot /tmp/dark.png
    scripts/ctl.py apps                                                      # list apps with stored data
    scripts/ctl.py container com.example.app                                 # show app data folder
    scripts/ctl.py erase com.example.app                                     # wipe app data
    ```

=== "Many instances at once (`fleet.py`)"

    ```bash
    scripts/fleet.py 16 app.apk -- 'tap-text Trips' 'until 3000 Tokyo' 'shot /tmp/out.png'
    ```

    Launches `N` headless instances in parallel, drives each through the same script, and reports pass/fail status and memory usage. See [Parallel runs](parallel.md).

!!! info "Before you script"

    - **Coordinates** are in device pixels (the default phone is `822 × 1782`). Use `nodes` to inspect bounds or `tap-text TEXT` to tap by label.
    - **Relative paths** resolve against the launch's working directory.
    - **macOS sandbox paths:** `shot` and `record` can write to `/tmp` (anywhere outside `$HOME`) or inside apksim's state folder (`~/.apksim`). Other paths under `$HOME` return `error:`.
    - **Errors are non-fatal:** a failing command replies `error: ...` and leaves the app running.

## Command reference

| Category | Commands |
|---|---|
| **Input** | `tap X Y`, `tap-text TEXT`, `swipe X1 Y1 X2 Y2 [ms]` (flings), `drag X1 Y1 X2 Y2 [ms]` (no fling), `pinch CX CY FROM TO [ms]`, `wheel X Y AMOUNT`, `type TEXT`, `key NAME [count] [ctrl\|shift\|alt\|meta]`, `back`, `paste` |
| **Device &amp; window** | `device [NAME\|WxH[@d]]`, `fold [open\|half\|closed\|toggle\|angle DEG]`, `skin [NAME\|off]`, `size WxH`, `window WxH`, `window click X Y`, `window front\|show\|background\|minimize\|hide`, `rotate left\|right\|portrait\|landscape`, `zoom actual\|fit\|in\|out`, `home` / `resume`, `lock` / `unlock` |
| **Settings** | `appearance dark\|light\|toggle`, `locale TAG`, `fontscale N\|+\|-`, `timezone [ZONE]`, `animscale 10\|1\|0\|toggle`, `keyboard soft on\|off`, `bars [status on\|off] [nav gesture\|off]`, `config` |
| **Network** | `network` (status), `network online\|offline\|3g\|lossy`, `network latency MS`, `network bandwidth KBPS`, `network wifi [on\|off\|signal DBM]`, `network cellular [on\|off]`, `network unmatched [clear]`, `network save` |
| **Device state** | `permission [allow\|deny\|grant NAME\|revoke NAME\|reset]`, `biometrics [enroll\|unenroll\|match\|nomatch\|cancel]`, `bluetooth [on\|off\|toggle]`, `location LAT LNG\|run\|bike\|drive\|gpx FILE\|none`, `camera pattern\|image PATH\|off`, `memory [moderate\|low\|critical]` |
| **Lifecycle &amp; background** | `open URL` (deep link), `recreate`, `process-death`, `pick FILE...\|cancel`, `jobs [run ID]`, `services`, `alarms`, `push key=value...` (FCM), `notifications [open N\|last\|clear]` |
| **Capture &amp; recorder** | `shot FILE.png`, `shot --skin FILE.png`, `wshot FILE.ppm`, `record start [FILE]\|stop`, `clipboard [set TEXT]`, `rec [on\|off [CHANNEL...]]`, `rec save [SECONDS] [FILE]`, `rec checkpoint now\|every SEC\|off`, `rec clear` |
| **Inspection** | `activity`, `windows`, `viewtree [FILE]`, `nodes`, `insets`, `display`, `a11y [show]`, `debug layout on\|off`, `logcat on\|off` |
| **Deterministic tests** | `settle [MAX_MS] [QUIET_MS] [HORIZON_MS]`, `restart [URL]`, `clock [ISO\|epoch-ms\|real]`, `diff GOLDEN ACTUAL [MAP] [DELTA]` |
| **Performance &amp; misc** | `frames`, `fps`, `shaders`, `video [blank\|on]`, `maxfps [N]`, `stats`, `ping`, `quit` |

Looking for standard `adb` commands or AI agent integration? See [adb device](adb-device.md) and [MCP server](mcp.md).
