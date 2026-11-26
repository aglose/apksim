# Quick Start

Go from zero to running an Android APK on your Apple Silicon Mac, driving it from scripts, and running its instrumented tests. All you need is an APK — a debug build of your own app is the easiest (`./gradlew :app:assembleDebug`).

## 1. Install

=== "Homebrew"

    ```bash
    brew tap aglose/apksim
    brew trust aglose/apksim      # once: Homebrew 7 loads casks only from trusted taps
    brew install apksim
    ```

=== "Install script"

    ```bash
    curl -fsSL https://raw.githubusercontent.com/aglose/apksim/main/install.sh | bash
    ```

    Installs into `~/.local/share/apksim` and links `~/.local/bin/apksim`.

=== "From source"

    ```bash
    git clone https://github.com/aglose/apksim && cd apksim
    scripts/setup.sh
    ./apksim app.apk
    ```

    Requires Xcode command-line tools (`xcode-select --install`). `setup.sh` fetches JDK 24, builds the runtime, and trains the startup cache. See [Building and contributing](building-and-contributing.md).

!!! tip "First launch builds a startup cache"

    It runs for 1–2 minutes in the background once per version; launches drop to ~0.2 s afterward. Run `apksim --train` to build it immediately. See [Installation](installation.md).

## 2. Run an APK

```bash
apksim app-debug.apk
```

The app opens in a phone-sized window — click to tap, drag to scroll, type on your Mac keyboard, and press `⌘V` to paste. Nothing is installed globally, and app data persists across launches (pass `--fresh` for a clean device).

## 3. Switch devices & appearance

Set the device configuration at launch:

```bash
apksim --device tablet app-debug.apk
apksim --appearance dark --locale de-DE --font-scale 1.3 app-debug.apk
apksim --device pixel-10-pro-fold --skin pixel-10-pro-fold app-debug.apk
```

You can also toggle devices, foldables, dark mode, text size, location, biometrics, and deep links live from the macOS menu bar, or drag any window edge to resize adaptive layouts in real time. See [Devices and launch options](devices-and-launch-options.md) and [The simulator window](simulator-window.md).

## 4. Drive from a script or `adb`

=== "Control channel"

    ```bash
    apksim --control 7400 app-debug.apk &
    scripts/drive.py 7400 'tap-text Settings' 'wait 500' 'shot /tmp/settings.png'
    scripts/ctl.py rotate landscape ';' shot /tmp/landscape.png
    scripts/drive.py 7400 quit
    ```

    Use `nodes` to inspect the accessibility tree and `tap-text TEXT` to tap elements by label.

=== "adb device"

    ```bash
    apksim --adb 5600 app-debug.apk &
    adb connect localhost:5600
    adb shell input tap 733 1533
    adb exec-out screencap -p > shot.png
    ```

    Standard `adb` tooling works out of the box. See [adb device](adb-device.md).

!!! note "Finding the helper scripts"

    In a release install, helper scripts (`drive.py`, `ctl.py`, `instrument.py`, `snapshot.py`, `crawl.py`) live in the install folder's `scripts/` directory — for Homebrew: `$(brew --prefix)/Caskroom/apksim/<version>/apksim-<version>-macos-arm64/scripts`.

## 5. Run instrumented tests

Build your app and test APKs (`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`), then run:

```bash
# Direct run (prints am instrument output)
apksim --instrument app-debug-androidTest.apk app-debug.apk
apksim --instrument app-debug-androidTest.apk -e class com.example.LoginTest app-debug.apk

# CI runner with shards, JUnit XML, and failure bundles
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --shards 4 --xml junit.xml --bundles bundles/
```

The same runner works headless in Docker (`ghcr.io/aglose/apksim`). See [Instrumented tests](instrumented-tests.md) and [Linux and Docker](linux-and-docker.md).

## 6. Share a build in a browser tab

```bash
apksim serve --apps builds/
```

Open `http://localhost:8080/`, pick or upload an APK, and interact with it in your browser — no Android tools required. See [apksim serve](serve.md) and [Beam](beam.md).

## Next steps

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Overview</div>
**Explore all features**

See everything apksim supports and when to choose it over an emulator.

<div class="sim-card-link" markdown>[Features →](features.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Honest boundaries</div>
**Read the limitations**

Check what is simulated, native library rules, and how to audit your APK.

<div class="sim-card-link" markdown>[Limitations →](limitations.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">AI &amp; automation</div>
**Connect AI agents**

Let Claude or other MCP clients launch, tap, inspect diffs, and record sessions.

<div class="sim-card-link" markdown>[MCP server →](mcp.md)</div>
</div>
</div>
