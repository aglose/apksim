# Screenshot tests

```bash
# record goldens in the Linux image, where CI checks them (spec, APK and goldens in the mounted folder; --out keeps
# the report there too, outside the goldens folder: without it, it stays in the container)
docker run --rm -v "$PWD:/work" --entrypoint snapshot.py ghcr.io/aglose/apksim:0.69.0 spec.json --update --out snapshots/record
# compare every screen in every combination; exit 1 on any difference (in CI: the same image, without --update)
docker run --rm -v "$PWD:/work" --entrypoint snapshot.py ghcr.io/aglose/apksim:0.69.0 spec.json --out snapshots/check
# a quick local check on a Mac before pushing, against the image's goldens with a one-level tolerance in the spec
scripts/snapshot.py spec.json -j 4
```

`scripts/snapshot.py` captures a list of screens (deep links, or a few control commands each) across a matrix of
devices, dark and light mode, locales and font scales, compares each capture with its golden image, and writes
`report.html` with the actual, golden and difference images side by side. Each screen's tolerance is set in the spec.
It runs the real APK, so there is no screenshot test to write per screen. Captures are deterministic: animations off,
a fixed clock and time zone, the UI settled before each shot (`settle`), and CPU-only drawing (`--snapshot`). 80
captures of the test app came out byte-identical across five runs, both on the Mac and in the Linux image, and a 1 dp
change was caught. Make goldens in the Linux image, where CI checks them. Spec format and measurements:
[docs/snapshots.md](snapshots.md).
