# Skins

A skin is a device frame image plus the device it frames. apksim stores them and `apksim skin` adds, shows and
exports them. They are local data: nothing is built in, anyone can add their own, and a private skin stays on the
machine. This page is the format, the commands, and how a running instance draws one ("Drawing"). Serving skins to a
browser tab (`serve`, `beam`) is a separate issue and not done yet.

## Format

A skin is a folder, `~/.apksim/skins/NAME/`:

```
skin.json
frame.png         the device, with a transparent screen hole
frame-land.png    optional: the landscape frame (else frame.png rotated)
mask.png          optional: the screen's shape (alpha), for round or notched screens; the screen's size
```

```json
{
  "name": "pixel-9",
  "title": "Pixel 9",
  "frame": "frame.png",
  "screen": { "x": 64, "y": 58, "width": 1080, "height": 2424, "cornerRadius": 96 },
  "device": "411x914@2.625",
  "kind": "phone",
  "cutout": { "type": "hole", "x": 540, "y": 52, "radius": 24 },
  "source": { "url": "https://...", "license": "CC BY 4.0", "author": "..." }
}
```

- `name` is `[a-z0-9-]+`, and the folder's name.
- `screen` is in the frame image's pixels: where the app's pixels go. `cornerRadius` clips its corners; `mask`
  overrides it for any other shape.
- `device` is a `--device` value: a preset (`phone`, `tablet`...), `WxH[@density]` in dp, or `WxHpx@Ndpi`. Using the
  skin implies it unless the launch names its own `--device` ("Drawing"). A device profile file is not supported yet.
- `kind` is `phone`, `foldable`, `tablet`, `desktop`, `tv` or `watch`; derived from `device` when absent.
- `cutout` is optional: `type` (`hole`), and `x`, `y`, `radius` in frame pixels: the centre and radius of the camera
  hole. A `notch` or `pill` written by hand with `width` and `height` instead of `radius` is that rectangle around the
  centre. It is what the app is told ("Drawing"); the frame image draws the camera itself.
- `frame-land.png` is the frame for the display turned to ROTATION_90 (the device's top edge on the left), the same
  image turned and redrawn (lighting, buttons); it must be `frame.png`'s size turned, or it is not used.
- `source` is free text shown by `skin show`, for attribution.
- The aspect of `screen` must match the device's pixels within 1%, or the skin is refused with both sizes.

## Commands

```
apksim skin add NAME --frame frame.png [--screen X,Y,W,H | --screen auto] [--corner-radius PX]
                     [--device NAME|WxH@D] [--kind KIND] [--title T] [--cutout hole:X,Y,R]
                     [--mask mask.png] [--frame-land F] [--license L] [--author A] [--source-url U] [--force]
apksim skin add --from DIR|ZIP|URL      an existing skin folder (skin.json + images), zipped or not
apksim skin list [--json]               name, title, device, kind, local or shared
apksim skin show NAME [--json]          skin.json, the resolved device, a check of the images
apksim skin preview NAME [--out PNG]    the frame with a test pattern in the screen (default NAME-preview.png)
apksim skin remove NAME
apksim skin export NAME [--out ZIP]
```

- `--screen auto` finds the largest fully transparent (alpha 0) region that does not reach the image's edge: the
  transparent pixels around a rounded frame connect to the edge, the hole does not. Its corner radius is worked out
  from what the hole lacks of its bounding box. A camera cut-out drawn opaque inside the screen makes that radius too
  big: pass `--corner-radius` for such a frame. Anti-aliased hole edges (alpha above 0) are left out of the hole, so
  the rectangle can be a pixel smaller than the artist drew; use `--screen X,Y,W,H` to set it.
- With `--screen auto` or explicit coordinates and no `--device`, `skin add` prints sizes that fit (at densities 2.625
  and 2) and exits 2; it never guesses.
- `skin show` and `skin add` warn when the frame covers more than 10% of the screen rectangle (the position is
  probably wrong); `skin preview` draws the frame over the pattern, so any misfit shows.
- Images given as JPEG or GIF are stored as PNG. `--force` replaces a skin of the same name.
- `skin add --from` takes a folder, a zip (files at the top or inside one folder) or an http(s) URL of a zip, at most
  64 MB unpacked, with no paths that leave the folder. The name is the one in its skin.json. `skin export` writes the
  same layout, so the two round-trip.

## Drawing

`apksim --skin NAME app.apk` (or the `skin NAME` command, or Device > Skin in the window) puts a skin around the
screen. The launch takes the skin's `device` unless `--device` names one; that device must have the skin's screen shape
(either orientation, within 1%), or a line says so and no skin is drawn. An unknown or broken skin stops the launch with
exit 2, as a bad `--device` does. `skin` alone says where everything falls now and lists the stored skins; `skin off`
takes it away. `skin NAME` for a skin with another device switches to that device, as `device` does; a `device`,
`size` or Device Type switch to another shape turns the skin off and says so.

- **Geometry.** The display's pixels set the scale: device pixels per frame pixel, from the screen's long side. The
  display is centred in the hole; the shapes agree within 1%, so a pixel or a few of the hole can be left around it,
  drawn black as a device's glass is. The frame turns with the display's rotation as a device does (`rotate`:
  ROTATION_90 puts the device's top edge, and its camera, on the left), with `frame-land.png` when the skin has one,
  and turns a quarter more when a display's orientation still differs from the frame's (a landscape tablet in a
  portrait frame).
- **The window** (`Skinning`, `simwindow.m`'s `SkinView`) is the frame's size: the frame image above the screen, the
  screen and the black behind it clipped to `cornerRadius` or `mask` (stretched over the hole). Clicks on the bezel go nowhere; the screen's own view takes the rest,
  with the app's coordinates as before. While a skin is on the window is not resized by dragging (the frame has its
  own shape); Zoom In/Out, Actual Size and Fit Screen scale it. `skin off` gives the bare window back at actual size.
  The status bar, the gesture handle and the software keyboard are drawn inside the screen, clipped with it.
- **What the app is told** (`SkinShape`): the cutout as the display's `DisplayCutout` (its bounds on the nearest edge,
  the safe inset on that side clearing it), and `cornerRadius` as four `RoundedCorner`s, from `Display.getCutout()` and
  `getRoundedCorner()` and in every window's `WindowInsets` (`getDisplayCutout()`, `getRoundedCorner()`, and
  `getInsets(Type.displayCutout())`, from insets sources as a device's window manager adds them). They follow the
  rotation. With no skin the app sees none, as before. `insets` shows them as the app's windows have them.
- **Screenshots.** `shot FILE` is still the app's pixels alone. `shot --skin FILE` (File > Save Screen with Skin,
  Cmd+Opt+S) is the screen in the frame, at the frame's size scaled to the display, transparent outside the device.
- **Headless and Linux** there is no window, but the device, what the app is told and `shot --skin` are the same.
- **The device's kind** is the skin's `kind` when it names one: a `227x227@2` skin of kind `watch` runs as a watch
  (round, UI_MODE_TYPE_WATCH, no bars), as `--device watch` would, at launch and with `skin NAME`.
- **Test runs** (`--instrument`) take the skin's device and report its cutout and corners; nothing is drawn. The
  shape follows the framework's display (its size and rotation), so UiDevice's rotation turns it too.
- **Flight recordings** keep the skin's shape (not its images) in each checkpoint's device, and `apksim replay` tells
  the app the same cutout and corners, with or without that skin stored where it replays.
- A warm launch has the standby's environment, so the launcher passes `APKSIM_SKINS_DIR` and `APKSIM_SKINS` on as
  `-Dapksim.skinsDir` and `-Dapksim.skinsShared`.

`scripts/skin-window-check.py APK [--window]` writes a fixture skin into a temporary store and checks the cutout and
corners the app sees, `shot --skin`'s pixels (bezel, camera, the app's pixels in the screen, transparency outside),
rotation, `skin off`, a device switch and an unknown skin's exit; with `--window` (Mac), clicks through the window at
the screen's corners, on the floating button and on the bezel (`window click X Y`, points of the window's content;
`window` reports the content's size and the device pixels per point). CI runs it headless in the Linux image
(`ci-smoke.sh`).

## Where skins are

`~/.apksim/skins` is the store: `add` and `remove` work there, whatever the install (`APKSIM_SKINS_DIR` names another
folder). `APKSIM_SKINS` adds folders of skins (colon-separated), searched after the store, so a team can keep a
shared set in a repository; `skin list` shows them as `shared` and `skin remove` leaves them alone.

## Check

`scripts/skin-check.py` draws a frame with a known hole, adds it, and checks the found rectangle and radius, the
preview's pixels, the zip round trip and each refusal.
