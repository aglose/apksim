# Skins

A skin is a device frame image plus the device it frames. apksim stores them and `apksim skin` adds, shows and
exports them. They are local data: nothing is built in, anyone can add their own, and a private skin stays on the
machine. This page is the format and the commands. Drawing a skin in the window and in screenshots, and serving them
to a browser tab, are separate issues and not done yet.

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
  skin implies it unless the launch names its own `--device` (that part comes with the drawing work). A device profile
  file is not supported yet.
- `kind` is `phone`, `foldable`, `tablet`, `desktop`, `tv` or `watch`; derived from `device` when absent.
- `cutout` is optional: `type` (`hole`), and `x`, `y`, `radius` in frame pixels.
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

## Where skins are

`~/.apksim/skins` is the store: `add` and `remove` work there, whatever the install (`APKSIM_SKINS_DIR` names another
folder). `APKSIM_SKINS` adds folders of skins (colon-separated), searched after the store, so a team can keep a
shared set in a repository; `skin list` shows them as `shared` and `skin remove` leaves them alone.

## Check

`scripts/skin-check.py` draws a frame with a known hole, adds it, and checks the found rectangle and radius, the
preview's pixels, the zip round trip and each refusal.
