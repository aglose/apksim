# Installation

**Requirements:** an Apple Silicon Mac. For CI, a Linux host with Docker, x86_64 or arm64 (see [Linux and Docker](linux-and-docker.md)).

## Homebrew

```bash
brew tap aglose/apksim
brew trust aglose/apksim      # once: Homebrew 7 loads casks only from taps you trust
brew install apksim
```

`brew upgrade apksim` updates it, and `brew uninstall --zap apksim` removes it along with its data.

## Install script

```bash
curl -fsSL https://raw.githubusercontent.com/aglose/apksim/main/install.sh | bash
```

This installs the release into `~/.local/share/apksim` and links `~/.local/bin/apksim`.

## What gets installed

A release is self-contained: its own JDK 24 runtime, libraries and native code, a ~310 MB download. The first launch
builds a startup cache in the background (1-2 minutes, once per version), downloading the 22 MB test app it trains on.
Launches are faster once it's built, and `apksim --train` builds it right away (`apksim --train APP.apk` trains on
any APK, offline). An installed apksim keeps everything it writes in `~/.apksim`, or in
`$APKSIM_HOME` if set: the startup cache, translated classes, app data and screenshots. `apksim --version` prints
the installed version.

To build apksim from a source checkout instead, see [Building from source](building-and-contributing.md).
