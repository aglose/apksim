# Quick Start

From nothing to an Android app running on your Mac, driven from a script and running its tests. You need an Apple
Silicon Mac and an APK: a debug build of your own app is the easiest (`./gradlew :app:assembleDebug`).

## 1. Install

=== "Homebrew"

    ```bash
    brew tap aglose/apksim
    brew trust aglose/apksim      # once: Homebrew 7 loads casks only from taps you trust
    brew install apksim
    ```

=== "Install script"

    ```bash
    curl -fsSL https://raw.githubusercontent.com/aglose/apksim/main/install.sh | bash
    ```

    This installs the release into `~/.local/share/apksim` and links `~/.local/bin/apksim`.

=== "From source"

    ```bash
    git clone https://github.com/aglose/apksim && cd apksim
    scripts/setup.sh
    ./apksim app.apk
    ```

    This needs the Xcode command-line tools (`xcode-select --install`). `setup.sh` runs once: it finds or fetches
    JDK 24, builds the runtime and trains the startup cache. See
    [Building and contributing](building-and-contributing.md).

!!! tip "The first launch builds a startup cache"

    It takes 1-2 minutes in the background, once per version, and launches are faster once it is there.
    `apksim --train` builds it right away. [Installation](installation.md) has the details.

## 2. Run an APK

```bash
apksim app-debug.apk
```

The app opens in a phone-sized window. Click to tap, drag to scroll, type on the Mac's keyboard, and Cmd+V pastes.
Nothing is installed anywhere: the APK runs as it is, and its data is kept between launches (`--fresh` starts from
a clean device).

## 3. Change the device

Options set the device a launch starts as:

```bash
apksim --device tablet app-debug.apk
apksim --appearance dark --locale de-DE --font-scale 1.3 app-debug.apk
apksim --device pixel-10-pro-fold --skin pixel-10-pro-fold app-debug.apk
```

While the app runs, the same switches are in the menus, as in the iOS Simulator: Device > Device Type, Rotate,
Features > Dark Appearance, Language and Region, Location, and more. Dragging the window's edge resizes the app's
display, so adaptive layouts respond live.

[Devices and launch options](devices-and-launch-options.md) lists every option, and
[The simulator window](simulator-window.md) every menu.

## 4. Drive it from a script

Start an instance with a control port, then send it commands, one per line:

```bash
apksim --control 7400 app-debug.apk &
scripts/drive.py 7400 'tap 720 1690' 'wait 1000' 'shot /tmp/home.png'
scripts/ctl.py list                                   # running instances
scripts/ctl.py rotate landscape ';' shot /tmp/landscape.png
scripts/drive.py 7400 quit
```

Coordinates are device pixels (the phone is 822 × 1782). `nodes` prints the accessibility tree with each
element's bounds, and `tap-text TEXT` taps one by its text.

!!! tip "Where the scripts are"

    In a release they live in the install folder's `scripts/`. With Homebrew that is
    `$(brew --prefix)/Caskroom/apksim/<version>/apksim-<version>-macos-arm64/scripts`.

If you already have adb tooling, make the instance an adb device instead:

```bash
apksim --adb 5600 app-debug.apk &
adb connect localhost:5600
adb shell input tap 733 1533
adb exec-out screencap -p > shot.png
```

More in [Automation](automation.md) and [adb device](adb-device.md). AI agents can drive instances through the
[MCP server](mcp.md).

## 5. Run your device tests

Build the app and its test APK (`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`), then:

```bash
apksim --instrument app-debug-androidTest.apk app-debug.apk
apksim --instrument app-debug-androidTest.apk -e class com.example.LoginTest app-debug.apk
```

This runs the suite as `am instrument -w -r` would and prints the same output. For CI, `instrument.py` wraps it
with JUnit XML, shards and a failure bundle for each failing test:

```bash
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --shards 4 --xml junit.xml --bundles bundles/
```

The same command runs in the headless Linux image, `ghcr.io/aglose/apksim`. See
[Instrumented tests](instrumented-tests.md) and [Linux and Docker](linux-and-docker.md).

## 6. Share a build in a browser tab

```bash
apksim serve --apps builds/
```

Open `http://localhost:8080/`, pick an APK from the folder (or upload one) and use it in the tab. See
[In a browser](browser-tab.md).

## Next steps

- [Features](features.md): everything apksim offers, and how it compares with the Android emulator.
- [Limitations](limitations.md): apksim is not a device. Read this before relying on it.
- [Network](network-overview.md): record an app's HTTP into a HAR, replay it offline, or simulate a bad connection.
- [Record & replay](replay-trace-crawler.md): the flight recorder, replays, the trace viewer and the crawler.
- [Screenshot tests](screenshot-tests.md): goldens for real APKs across devices, appearances and locales.
- [Debugging](debugging.md): breakpoints in app code, logs and crashes.
