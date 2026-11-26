---
title: The simulator window
---

<div class="sim-hero" markdown>
<div class="sim-hero-copy" markdown>

# The simulator window

<p class="sim-lead">Drag any edge to resize the app's display in dp, just like a freeform Android window. Adaptive layouts — window size classes and <code>BoxWithConstraints</code> — respond live as you drag.</p>

Activities receive the exact configuration change they would on a device: they recreate, or resize in place if they declare `screenSize` in `configChanges`. With a [skin](using-skins.md) active, the window locks to the frame's proportions and scales via the **Window** menu.

<div class="sim-pills">
<span class="sim-pill">Live freeform resize</span>
<span class="sim-pill">Up to 120 fps</span>
<span class="sim-pill">Skins &amp; foldables</span>
<span class="sim-pill">Native macOS menus</span>
</div>

</div>
<div class="sim-hero-visual" markdown>
<figure class="sim-shot sim-shot--phone" markdown>
![ComposeApp running inside a Galaxy S25 device skin](site-assets/win-skin.png)
<figcaption>Galaxy S25 skin</figcaption>
</figure>
<figure class="sim-shot sim-shot--fold" markdown>
![ComposeApp running unfolded on a Pixel 10 Pro Fold](site-assets/win-fold.png)
<figcaption>Pixel 10 Pro Fold</figcaption>
</figure>
</div>
</div>

## Menus

Every common device action lives in the macOS menu bar with a keyboard shortcut. Click a menu below to jump to its commands.

<nav class="mac-menubar" aria-label="Simulator menus">
<span class="mac-menubar-app">apksim</span>
<a href="#menu-file">File</a>
<a href="#menu-device">Device</a>
<a href="#menu-features">Features</a>
<a href="#menu-window">Window</a>
<a href="#menu-debug">Debug</a>
</nav>

### File {#menu-file}

<div class="menu-showcase" markdown>
<div class="mac-menu">
<div class="mac-menu-title">File</div>
<div class="mac-row"><span>Save Screen</span><span class="mac-keys"><kbd>⌘</kbd><kbd>S</kbd></span></div>
<div class="mac-row mac-row--highlight"><span>Save Screen with Skin</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⌥</kbd><kbd>S</kbd></span></div>
<div class="mac-row"><span>Record Screen</span><span class="mac-keys"><kbd>⌘</kbd><kbd>R</kbd></span></div>
<div class="mac-row"><span>Save Replay <small>(flight recorder's last minutes)</small></span><span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>S</kbd></span></div>
<div class="mac-sep"></div>
<div class="mac-row"><span>Show Screenshots and Recordings</span><span class="mac-keys"></span></div>
</div>
<div class="menu-preview" markdown>
<figure class="menu-preview-frame menu-preview-frame--skin" markdown>
![Screenshot saved with the Galaxy S25 device frame around the screen](site-assets/win-skin.png)
</figure>
<div class="menu-preview-meta" markdown>
**Save Screen with Skin** composites the app's live pixels inside the active device frame and corner mask. Captures are saved to `work/screenshots` (or `~/.apksim/screenshots` in a release).
</div>
</div>
</div>

### Device {#menu-device}

<div class="menu-showcase" markdown>
<div class="mac-menu">
<div class="mac-menu-title">Device</div>
<div class="mac-row mac-row--highlight">
<div><span>Device Type</span><div class="mac-sub">Phone, Foldable, Tablet, Desktop, TV, Pixel 10 Pro Fold, Galaxy S25 / S25+ / S25 Ultra / A56, Galaxy Z Fold7 / Flip7 / TriFold</div></div>
<span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>1–9</kbd> <span class="mac-chev">▸</span></span>
</div>
<div class="mac-row">
<div><span>Skin</span><div class="mac-sub">None and every stored skin</div></div>
<span class="mac-keys"><span class="mac-chev">▸</span></span>
</div>
<div class="mac-sep"></div>
<div class="mac-row"><span>Fold / Unfold</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>F</kbd></span></div>
<div class="mac-row"><span>Rotate Left / Right</span><span class="mac-keys"><kbd>⌘</kbd><kbd>←</kbd> <kbd>→</kbd></span></div>
<div class="mac-sep"></div>
<div class="mac-row"><span>Back</span><span class="mac-keys"></span></div>
<div class="mac-row"><span>Home</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>H</kbd></span></div>
<div class="mac-row"><span>Lock</span><span class="mac-keys"><kbd>⌘</kbd><kbd>L</kbd></span></div>
<div class="mac-row"><span>Volume Up / Down</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⌥</kbd><kbd>=</kbd> <kbd>-</kbd></span></div>
<div class="mac-sep"></div>
<div class="mac-row"><span>Software Keyboard</span><span class="mac-keys"><kbd>⌘</kbd><kbd>K</kbd></span></div>
<div class="mac-row"><span>Status Bar / Navigation Bar</span><span class="mac-keys"></span></div>
</div>
<div class="menu-preview" markdown>
<figure class="menu-preview-frame menu-preview-frame--fold" markdown>
![ComposeApp running on an unfolded Pixel 10 Pro Fold](site-assets/win-fold.png)
</figure>
<div class="menu-preview-meta" markdown>
Switch form factors on the fly — from compact phones to tablets, desktops, TVs, and animated foldables with real hinge angles and `FoldingFeature` bounds.
</div>
</div>
</div>

### Features {#menu-features}

<div class="menu-showcase" markdown>
<div class="mac-menu">
<div class="mac-menu-title">Features</div>
<div class="mac-row mac-row--highlight"><span>Dark Appearance</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>A</kbd></span></div>
<div class="mac-row mac-row--highlight"><span>Larger, Smaller &amp; Default Text</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>=</kbd> <kbd>-</kbd> <kbd>0</kbd></span></div>
<div class="mac-row"><span>Language and Region…</span><span class="mac-keys"></span></div>
<div class="mac-row">
<div><span>Location</span><div class="mac-sub">None, Custom Location…, City Run, City Bicycle Ride, Freeway Drive</div></div>
<span class="mac-keys"><span class="mac-chev">▸</span></span>
</div>
<div class="mac-row">
<div><span>Fingerprint</span><div class="mac-sub">Enrolled, Matching Finger, Non-matching Finger</div></div>
<span class="mac-keys"><kbd>⌘</kbd><kbd>⌥</kbd><kbd>M</kbd> <kbd>N</kbd> <span class="mac-chev">▸</span></span>
</div>
<div class="mac-row">
<div><span>Network</span><div class="mac-sub">Online, Offline, 3G, Lossy, Wi-Fi, Mobile Data</div></div>
<span class="mac-keys"><span class="mac-chev">▸</span></span>
</div>
<div class="mac-row">
<div><span>Camera</span><div class="mac-sub">Off, Test Pattern, Image File…</div></div>
<span class="mac-keys"><span class="mac-chev">▸</span></span>
</div>
<div class="mac-sep"></div>
<div class="mac-row"><span>Open URL…</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>O</kbd></span></div>
</div>
<div class="menu-preview" markdown>
<figure class="menu-preview-frame menu-preview-frame--screen" markdown>
![ComposeApp with Dark Appearance and 1.25x Larger Text enabled](site-assets/win-dark-type.png)
</figure>
<div class="menu-preview-meta" markdown>
Toggle **Dark Appearance** (`⌘⇧A`) and scale **font size** (`⌘⇧=`) live without restarting the app. Simulate routes, biometrics, throttled networks, and deep links from the same menu.
</div>
</div>
</div>

### Window {#menu-window}

<div class="menu-showcase">
<div class="mac-menu">
<div class="mac-menu-title">Window</div>
<div class="mac-row"><span>Minimize</span><span class="mac-keys"><kbd>⌘</kbd><kbd>M</kbd></span></div>
<div class="mac-sep"></div>
<div class="mac-row mac-row--highlight"><span>Actual Size</span><span class="mac-keys"><kbd>⌘</kbd><kbd>1</kbd></span></div>
<div class="mac-row"><span>Fit Screen</span><span class="mac-keys"><kbd>⌘</kbd><kbd>3</kbd></span></div>
<div class="mac-row"><span>Zoom In / Out</span><span class="mac-keys"><kbd>⌘</kbd><kbd>=</kbd> <kbd>-</kbd></span></div>
</div>
<div class="menu-preview menu-preview--zoom">
<div class="zoom-diagram" aria-hidden="true">
<div class="zoom-box zoom-box--sm"><span>Fit</span><kbd>⌘3</kbd></div>
<div class="zoom-box zoom-box--md"><span>Actual</span><kbd>⌘1</kbd></div>
<div class="zoom-box zoom-box--lg"><span>Zoomed</span><kbd>⌘=</kbd></div>
</div>
<div class="menu-preview-meta">
Zooming scales the window on your Mac's display without changing the Android device's resolution or density in dp.
</div>
</div>
</div>

### Debug {#menu-debug}

<div class="menu-showcase" markdown>
<div class="mac-menu">
<div class="mac-menu-title">Debug</div>
<div class="mac-row"><span>Slow Animations</span><span class="mac-keys"><kbd>⌘</kbd><kbd>T</kbd></span></div>
<div class="mac-row"><span>Simulate Memory Warning</span><span class="mac-keys"><kbd>⌘</kbd><kbd>⇧</kbd><kbd>M</kbd></span></div>
<div class="mac-sep"></div>
<div class="mac-row mac-row--highlight"><span>Show Layout Bounds</span><span class="mac-keys"></span></div>
<div class="mac-row"><span>Accessibility Audit</span><span class="mac-keys"></span></div>
</div>
<div class="menu-preview" markdown>
<figure class="menu-preview-frame menu-preview-frame--screen" markdown>
![ComposeApp with Show Layout Bounds enabled showing red and blue bounds overlays](site-assets/win-layout-bounds.png)
</figure>
<div class="menu-preview-meta" markdown>
**Show Layout Bounds** draws Android's layout and clip bounds over Views and Compose nodes. Pair with **Slow Animations** (`⌘T`) to inspect transitions frame by frame.
</div>
</div>
</div>

## Input

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Multi-touch</div>
**Pinch & two-finger pan**

Hold <kbd>⌥ Option</kbd> and drag to pinch around the centre of the screen. Hold <kbd>⌥ Option</kbd> + <kbd>⇧ Shift</kbd> and drag to pan with two fingers.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Trackpad</div>
**Native gestures**

Mac trackpad pinch and rotate gestures map directly to Android two-finger touch events.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Keyboard</div>
**Full Android key events**

Typing sends Android `KeyEvent`s, including `Control` shortcuts and hardware navigation keys.
</div>
</div>

## System UI

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">System bars</div>
**Status bar & gesture handle**

Drawn over the app whenever the device has them (`--status-bar`, `--nav-bar`, `bars`) and the app hasn't hidden them via `WindowInsetsController.hide` or legacy fullscreen flags.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Immersive mode</div>
**Edge swipes**

Swiping in from an edge reveals hidden system bars just as on a device — transiently in immersive-sticky mode.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Prompts & alerts</div>
**Sheets & banners**

Runtime permission requests and biometric prompts appear as native sheets, and posted notifications appear as banners.
</div>
</div>

## The Mac

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Clipboard</div>
**Two-way sync**

The Android clipboard stays in sync with your Mac's pasteboard in both directions.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Outbound links</div>
**Browser & Mail**

`http(s)` and `mailto` intents open directly in your Mac's default browser and mail app.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">System panels</div>
**Share sheet & file picker**

Android sharing opens the macOS share menu, and document or photo pickers open the native macOS file panel.
</div>
</div>
