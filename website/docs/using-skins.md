# Skins

A skin is a device frame image plus the device it frames, kept on your machine and added from the command line.
Skins are local data: apksim ships none, and a private one never leaves your machine. `apksim serve` hands its skins
to the page, which draws them around the screen (`&skin=NAME`, a Skin menu; [docs/serve.md](serve.md) "Skins").

```bash
apksim skin add pixel --frame frame.png --screen auto --device phone   # the screen is the frame's transparent hole
apksim skin preview pixel                                               # pixel-preview.png: a test pattern in the screen
apksim skin list
apksim skin show pixel --json
apksim skin export pixel --out pixel.zip
apksim skin add --from pixel.zip                                        # or a folder, or an https:// URL
apksim skin remove pixel
```

`--screen auto` takes the largest fully transparent region the frame closes in, and reads its corner radius from the
hole's corners; `skin add` prints what it found and `skin preview` shows it. With no `--device` it prints sizes that
fit the hole and stops: the device is never guessed. A skin whose screen does not match its device's pixel aspect
within 1%, whose images are missing or whose hole is not found is refused with the reason. Skins live in
`~/.apksim/skins/NAME/`; `APKSIM_SKINS` (colon-separated folders) adds shared sets searched after it. The format is in
[docs/skins.md](skins.md).

```bash
apksim --skin pixel app.apk           # the frame around the screen, and the skin's device unless --device names one
scripts/drive.py 7400 'skin off' 'skin pixel' 'shot --skin framed.png'
```

`--skin NAME` (Device > Skin, the `skin NAME|off` command) draws the frame around the screen in the window: the window
becomes the frame's size, the screen is clipped to its corners (or its mask), and only the screen takes clicks. It
turns with the display (`rotate`), using `frame-land.png` when there is one. A skin with another device switches to it;
a device switch to another shape turns the skin off. The app is told what the frame implies: its cutout as the
display's `DisplayCutout` and its corner radius as `RoundedCorner`s, in `WindowInsets` and from `Display`, so a layout
that pads for the camera hole does so as on the device. `shot --skin FILE` (File > Save Screen with Skin) saves the
screen in the frame, transparent outside the device; `shot` is still the app's pixels alone. Headless and in the Linux
image there is no window, but the device, the cutout and corners and `shot --skin` are the same.
