# Folding devices, the fold animation and device skins

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Code notes
- `Fold`, `FoldExtensions`, `androidx/window/extensions/` (in apksim.jar): folding devices (`--device
  pixel-10-pro-fold|galaxy-z-fold7|galaxy-z-flip7|galaxy-z-trifold`, `--fold open|half|closed`, `fold
  [open|half|closed|toggle|angle DEGREES]`, Device > Fold / Unfold). `Fold.Spec` has each device's two displays
  (pixels, density and diagonal each: the TriFold's inner display is 320 dpi, its cover 420), one panel's
  thickness and its `Hinge`: BOOK (one fold down the middle of the inner display: the Pixel, the Z Fold7),
  CLAMSHELL (one fold across it: the Z Flip7, its 948x1048 FlexWindow as the cover display) or TRIFOLD (two folds
  at its thirds; its inner display is wider than tall in its natural orientation). Panels are the makers'
  published pixels and sizes; the densities are assumed (the Pixel's is the Pixel 9 Pro Fold emulator's, Samsung's
  420 dpi), not read from devices; whole dp make some displays a pixel or two narrower than the panel. Closed ↔ open
  is `reshape` to the other display (rotation kept), so the app gets a size change as on a device. Apps read the
  fold through Jetpack WindowManager, which loads `androidx.window.extensions.WindowExtensionsProvider` by name and
  checks its shape by reflection (window 1.5.0's `SafeWindowLayoutComponentProvider`); the framework jar has no such
  library, so apksim.jar carries a stand-in at vendor API level 2 (layout component only: no activity embedding or
  window areas) for every device, as phones have it. `FoldExtensions` tells each listener its window's layout as it
  registers and after every posture, rotation or display change (`Fold.changed()`): a FoldingFeature per fold
  (`Fold.bounds`: lines across the inner display, flat or half-opened; none when closed or on other devices).
  Whether the display is turned is read from its shape against the inner panel's, not from the rotation: the
  framework reports a display wider than tall as ROTATION_90, and the TriFold's inner one is. `-Dapksim.windowExtensions=false` reports level
  0 (no extensions). The hinge has an angle (`Fold.angle()`: 0 closed, 90 half, 180 open, or `fold angle N`, which is
  a posture by the emulator's ranges: under 30 closed, under 150 half-open, else open), which a folding device's
  hinge-angle sensor reports on change (`Sensors` HINGE: TYPE_HINGE_ANGLE, wake-up, feature
  `android.hardware.sensor.hinge_angle`; other devices have neither). Half-open and turned a quarter, a book's fold is
  horizontal: tabletop (a clamshell's is upright). Test runs: `--device`/`--fold` set the display, `executeShellCommand("apksim fold half|open")`
  (or `angle N`) changes the state until the next setup (not across closed: nothing resizes a test run's display). `apksim://fold`
  (`FoldActivity`: each fold, a tri-fold's two joined by " | "), `FoldTest` (`-e foldDevice NAME` for another than the Pixel; CI runs the Flip and TriFold too), `ci-smoke.sh` (the Pixel, the Flip and
  the TriFold). The Mac window plays the fold (`native/foldanim.m`, included by `simwindow.m`'s "fold animation";
  `SimWindow.foldBegin`/`foldRun` around the fold's reshape, given the hinge and thickness): the device's picture,
  lit and with its screen off (its skin's frame around the screen, status bar included; `deviceImage`), goes on a
  transparent overlay window (above the menu bar: the device nears the viewer as it turns) and the window itself is
  hidden (alpha 0); once the new display has drawn two frames, an `SCNView` plays it in
  SceneKit (the view covers only where the device can be, open, closed and nearing the viewer, and is shown only once
  its first frame is drawn: its Metal layer starts opaque with nothing drawn, and full-screen it flashed the whole
  screen), with a depth buffer (Core Animation's layer sorting let panels pop through each other and sliced them where
  they met; the first version was layers): each panel is an `SCNShape` extruded from the skin's body outline
  (skin.json's `fold.body`: rounded outer corners, square at a hinge) by its real thickness, chamfered with a rounded
  profile, the screen's picture on a flush flat face over its front (two panels meet at a hinge with no seam), its back
  the cover display or `back.png`'s slice, its sides physically based metal in `fold.edge`'s colour; keys are chamfered
  boxes; each hinge is a barrel (`foldBarrel`, rebuilt per pose) filling the wedge between the panels' ends to the
  hinge's angle. A studio environment and a shadow-casting key light light it; screens are their dark picture plus an
  emission (the lit picture less the dark one) scaled by how lit they are. The camera sits at the open device's centre
  with an off-centre frustum that draws the plane z 0 1:1 onto the overlay. Choreography (`foldPoseAt`): a BOOK's left
  half turns half a turn about the hinge towards the viewer onto the right half; a CLAMSHELL's bottom half folds up and
  the closed phone then turns over about its vertical axis, lifted towards the viewer, to show the cover display on the
  top half's back (upright, the hinge at its bottom); a TRIFOLD's left panel folds onto the middle one, then the right
  panel over both (about an axis half a panel's thickness up). The hinge angle follows a hand (an even swing, the
  magnets' snap over the last ~25 degrees and a settle under a degree; opening, the magnets' pop, the swing and the
  flat detent), each screen turns off and on as a timed fade of ~0.2-0.25 s, eased and even to the eye (brightness to the 2.2nd
  power: `foldFadeIn`/`foldFadeOut`), placed where that screen is in view (closing, the inner one dims as the panel
  comes over it and the cover wakes as it comes round; opening, the reverse), done before the run's flat ends; tied to
  the hinge's angle, they had dropped to black within the snap's ~40 ms. The
  SceneKit render loop (`FoldStage`, the view's delegate) poses each frame from its own clock and ends the fold; the
  shaders are built ahead (`SimWindow.foldWarm`, from `showTitle` for a folding device, once a process, a second
  later: a tiny fold plays in a 32 pt window at 1% opacity, so a real one finds the same pipelines; before, the first
  fold waited ~0.3 s), and building the scene leaves ~0.1-0.16 s of the still picture. At the end the window comes
  back under the overlay, which fades out over 0.2 s two display frames later (`foldFinish`): removing both at once
  showed the desktop for a frame, and the lit 3D frame jumped to the window's flat picture. No handoff changes a pixel:
  the window hides only 34 ms after the overlay (the same picture) is up; each screen face blends with its exact
  flat picture (`FOLD_FLAT_MODIFIER`, a fragment shader modifier, `flatness` 1 at the run's ends, lit in between), so
  the scene's first frame is the still and its last the window's picture (`fold-frames.m`: a closing's first frame
  and an opening's last differ in no pixel); the still goes 50 ms after the scene's first frame. The closed device lands exactly where the window's picture
  is: its footprint is the cover panel's face (counting the hinge barrel in it squeezed the cover and showed as a resize
  as the window took over), every picture is on a flat, double-sided face of the full outline (a chamfered slab's own
  caps are smaller), the barrels fade out on landing (the picture draws its hinge), and in the run's last ~13% the
  window's own picture of the new device fades in over the 3D one (`landing` in `foldPlay`), so keys and corners that
  the 3D model draws differently never jump. `fold-frames.m` also writes `window-open.png` and `window-closed.png` (the
  pictures as the window shows them) to compare a run's first and last frames with. Openings are their
  own runs, not closings reversed (1.0 s book, 1.5 s clamshell, 1.7 s tri-fold, `-Dapksim.foldAnimationMs` for all).
  The window is resized about its centre meanwhile (`sizeWindow` while `foldWindow` is set) at the other panel's
  pixel density (`SimWindow.foldZoom`, the ratio of `Fold.Spec.ppi`s, held until the switch and its skin re-layout
  are done), so the device keeps its size on the Mac's screen and folds in place; zoomed out only as far as the
  screen needs. The app sees none of it, only the switch. Not played headless, with the window hidden or
  minimized, while rotated, or when a fold follows another before a frame is drawn; `-Dapksim.foldAnimation=false`
  turns it off; `APKSIM_FOLD_DEBUG=1` logs its steps, and `APKSIM_FOLD_FRAMES=DIR` renders 13 frames of each fold
  off screen (`CARenderer`) into DIR as PNGs, which is how to look at it from a script (`wshot` cannot see the
  overlay). `scripts/fold-frames.m` (its header has the clang line) renders the same scene from a skin's frames with
  test screens and no app, any number of frames: iterate on the animation there. The built-in skins
  `pixel-10-pro-fold`, `galaxy-z-fold7`, `galaxy-z-flip7` and `galaxy-z-trifold` (`--skin NAME`, which implies the
  device; docs/skins.md "Built-in skins") are pairs: `frame.png` around the inner display and `cover.png` around the
  cover display (skin.json's `cover`) with `back.png` and the `fold` look, all drawn by `scripts/gen-device-skins.java`
  (the Samsung S25, S25+, S25 Ultra and A56 phones too) from the devices'
  published sizes at each panel's pixels per millimetre; `Skinning.part` picks the cover frame, corners and camera
  cutout while the device is closed, so the skin stays on through a fold. Not done: the posture in flight-recorder
  checkpoints (a replayed shape has one frame), a fold while rotated, a TriFold in its two-panel posture (it has
  none), an R8 app checked.
- `foldanim.m`: the fold animation's SceneKit scene (book, clamshell, tri-fold; see `Fold`), `#include`d by
  `simwindow.m` and by `scripts/fold-frames.m`, which renders it without an app.
- `skin/` (`Skins`, `Skin`, `Frames`; `DeviceSpec` resolves a `--device` value): `apksim skin`, the local skin store and its commands, docs/skins.md. Runs on a plain JVM with only apksim.jar (the launcher's `skin` case). serve and beam hand skins to a page (issue 246, docs/serve.md "Skins"): `Serve.skins()`/`skinsJson`/`skinImage` (`/api/skins`, the agent's `/beam/skins` for trusted sites only), `--skins DIR` (`Skins.addDirs`), the `--skin` launch option (`Catalog`), `/ws?skin=` and the app's own `--skin` setting the session's device (`Session.skin`, `appSkin`; a serve instance gets `--skin` and the skin folders in `APKSIM_SKINS`, a beamed one not, as its sandbox cannot read them), and serve.html's Skin menu and `skinFit()` (`window.apksimSkin()`); `scripts/serve-skin-check.py` (Chrome).
- `Skinning`, `SkinShape`: a skin drawn around the screen (`--skin NAME`, `skin NAME|off`, Device > Skin, issue 245;
  docs/skins.md "Drawing"). `Skinning` (plain Java, used by Main before the framework exists) picks the skin, its
  device unless `--device` names one, and the layout for the display's size and rotation: the frame turned with
  ROTATION_90 putting the device's top on the left (`turnRect`, the native `turnImage` and Host's `turned` matrix
  agree), scaled by the screen's long side, the display centred in the hole (black around it). The window
  (`simwindow.m` `SkinView`, `layoutSkin`) makes the frame the window's content with SimView inside it, so the app's
  coordinates are unchanged and bezel clicks reach nothing; `outerW/H` replace devW/H wherever the window is sized,
  and dragging is off while a skin is on. `SkinShape` tells the app: `Display.getCutout/getRoundedCorner`
  (`ShadowSimDisplay`) and each window's insets state as it arrives (`ShadowSimInsetsController.onStateChanged`:
  DisplayCutout, RoundedCorners and displayCutout() insets sources, as DisplayPolicy adds them). DeviceControls'
  `skinChanged` runs after a rotation, a reshape (which turns the skin off for another shape) and the command.
  `shot --skin` composites on an Android Canvas (headless and Linux too). `scripts/skin-window-check.py` (CI headless in
  `ci-smoke.sh`; `--window` on the Mac).
