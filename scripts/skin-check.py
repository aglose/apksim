#!/usr/bin/env python3
"""Checks `apksim skin` (docs/skins.md) against a frame drawn here with a known hole, in a temporary skin store.

Usage: scripts/skin-check.py   (needs work/apksim.jar from scripts/build.sh; no Mac window or app)
"""
import json, os, struct, subprocess, sys, tempfile, zlib, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FW, FH = 560, 1100                 # the frame image
HX, HY, HW, HH, HR = 60, 70, 440, 960, 48   # the screen hole: 440x960 is phone's 822x1782 within 1%
OR = 70                            # the frame's outer corner radius


def png(path, w, h, px):
    raw = b"".join(b"\0" + b"".join(bytes(px(x, y)) for x in range(w)) for y in range(h))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    Path(path).write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def inside_round(x, y, x0, y0, w, h, r):
    if not (x0 <= x < x0 + w and y0 <= y < y0 + h):
        return False
    cx = min(max(x + .5, x0 + r), x0 + w - r)
    cy = min(max(y + .5, y0 + r), y0 + h - r)
    return (x + .5 - cx) ** 2 + (y + .5 - cy) ** 2 <= r * r


def frame_px(x, y):
    if inside_round(x, y, HX, HY, HW, HH, HR):
        return (0, 0, 0, 0)                  # the hole
    if inside_round(x, y, 0, 0, FW, FH, OR):
        return (30, 30, 34, 255)             # the device
    return (0, 0, 0, 0)                      # outside the device


def read_png(path):
    d = Path(path).read_bytes()
    pos, idat, w, h = 8, b"", 0, 0
    while pos < len(d):
        n = struct.unpack(">I", d[pos:pos + 4])[0]
        t = d[pos + 4:pos + 8]
        body = d[pos + 8:pos + 8 + n]
        if t == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and ctype in (2, 6), (depth, ctype)
        if t == b"IDAT":
            idat += body
        pos += 12 + n
    bpp = 4 if ctype == 6 else 3
    raw = zlib.decompress(idat)
    rows, prev, i = [], bytearray(w * bpp), 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + w * bpp]); i += 1 + w * bpp
        for k in range(len(line)):
            a = line[k - bpp] if k >= bpp else 0
            b = prev[k]
            c = prev[k - bpp] if k >= bpp else 0
            if f == 1: line[k] = (line[k] + a) & 255
            elif f == 2: line[k] = (line[k] + b) & 255
            elif f == 3: line[k] = (line[k] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[k] = (line[k] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, lambda x, y: tuple(rows[y][x * bpp:x * bpp + bpp]) + ((255,) if bpp == 3 else ())


failures = []


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        failures.append(what)


def main():
    tmp = Path(tempfile.mkdtemp(prefix="skin-check-"))
    env = dict(os.environ, APKSIM_SKINS_DIR=str(tmp / "store"), APKSIM_SKINS="")

    def skin(*a, cwd=tmp, **kw):
        r = subprocess.run([str(ROOT / "apksim"), "skin", *map(str, a)], capture_output=True, text=True, cwd=cwd, env=kw.get("env", env))
        return r.returncode, r.stdout + r.stderr

    frame = tmp / "frame.png"
    png(frame, FW, FH, frame_px)

    rc, out = skin("add", "test", "--frame", frame, "--screen", "auto")
    check(rc == 2 and "220x480@2" in out and "--device" in out, "auto without --device suggests sizes and stops (exit 2)")
    check(not (tmp / "store" / "test").exists(), "nothing stored by the refused add")

    rc, out = skin("add", "test", "--frame", frame, "--screen", "auto", "--device", "phone", "--license", "CC0", "--title", "Test phone")
    print(out.strip())
    check(rc == 0, "add --screen auto --device phone")
    rc, out = skin("show", "test", "--json")
    j = json.loads(out)
    s = j["screen"]
    check((s["x"], s["y"], s["width"], s["height"]) == (HX, HY, HW, HH), f"screen rect found: {s}")
    check(abs(s["cornerRadius"] - HR) <= 3, f"corner radius found: {s['cornerRadius']} (drawn {HR})")
    check(j["kind"] == "phone" and j["deviceResolved"]["widthPx"] == 822 and j["problems"] == [], "device resolved, no problems")
    check(j["source"]["license"] == "CC0", "license kept")
    rc, out = skin("show", "test")
    check(rc == 0 and "check:   ok" in out, "show prints the check")

    rc, out = skin("preview", "test", "--out", tmp / "p.png")
    check(rc == 0, "preview")
    w, h, px = read_png(tmp / "p.png")
    check((w, h) == (FW, FH), "preview is the frame's size")
    c = px(HX + HW // 2, HY + HH // 2 + 200)
    check(c[3] == 255 and c[:3] != (0xE8, 0xE8, 0xE8) and c[:3] != (30, 30, 34), f"pattern fills the hole {c}")
    check(px(HX + 1, HY + 1)[:3] == (30, 30, 34), "the red corner marker is hidden by the frame at the rounded corner")
    check(px(HX + 5, HY + HH // 2)[3] == 255 and px(HX - 5, HY + HH // 2)[:3] == (30, 30, 34), "pattern lines up with the hole, frame beside it")

    rc, out = skin("list")
    check(rc == 0 and "test" in out and "Test phone" in out and "local" in out, "list")
    rc, out = skin("list", "--json")
    check(json.loads(out)[0]["name"] == "test", "list --json")

    rc, out = skin("export", "test", "--out", tmp / "t.zip")
    check(rc == 0 and set(zipfile.ZipFile(tmp / "t.zip").namelist()) == {"skin.json", "frame.png"}, "export zip")
    skin("remove", "test")
    rc, out = skin("show", "test")
    check(rc == 1 and "no skin named test" in out, "remove")
    rc, out = skin("add", "--from", tmp / "t.zip")
    check(rc == 0, "add --from zip")
    rc, out = skin("show", "test", "--json")
    check(rc == 0 and json.loads(out)["screen"]["width"] == HW, "zip round-trips")
    rc, out = skin("add", "--from", tmp / "store" / "test")
    check(rc == 1 and "exists" in out, "add --from DIR of an existing name refused without --force")
    rc, out = skin("add", "--from", tmp / "store" / "test", "--force")
    check(rc == 0, "--force replaces")
    # a zip whose files sit in one folder
    z = tmp / "nested.zip"
    with zipfile.ZipFile(z, "w") as zf:
        for n in ("skin.json", "frame.png"):
            zf.write(tmp / "store" / "test" / n, "test/" + n)
    skin("remove", "test")
    rc, out = skin("add", "--from", z)
    check(rc == 0, "add --from a zip with a top folder")
    with zipfile.ZipFile(tmp / "evil.zip", "w") as zf:
        zf.writestr("../evil.txt", "x")
    rc, out = skin("add", "--from", tmp / "evil.zip")
    check(rc == 1 and "unsafe path" in out, "zip path escape refused")

    # refusals name the problem
    rc, out = skin("add", "bad1", "--frame", frame, "--screen", f"{HX},{HY},{HW},500", "--device", "phone")
    check(rc == 1 and "440x500" in out and "822x1782" in out, "aspect mismatch names both sizes")
    full = tmp / "full.png"
    png(full, 200, 400, lambda x, y: (1, 2, 3, 255))
    rc, out = skin("add", "bad2", "--frame", full, "--screen", "auto", "--device", "phone")
    check(rc == 1 and "no hole" in out or "no alpha" in out, "hole not found refused")
    rc, out = skin("add", "bad3", "--frame", tmp / "nope.png", "--screen", "auto", "--device", "phone")
    check(rc == 1 and "does not exist" in out, "missing image refused")
    rc, out = skin("add", "Bad Name", "--frame", frame, "--screen", "auto", "--device", "phone")
    check(rc == 1 and "not a skin name" in out, "bad name refused")
    rc, out = skin("add", "bad4", "--frame", frame, "--screen", "auto", "--device", "nonsense")
    check(rc == 1 and "unknown device" in out, "bad device refused")
    rc, out = skin("add", "bad5", "--frame", frame, "--screen", "-10,0,440,960", "--device", "phone")
    check(rc == 1 and "does not fit" in out, "screen outside the frame refused")
    check(not any(p.name.startswith("bad") for p in (tmp / "store").iterdir()), "no refused skin stored")
    check(not any(p.name.startswith(".") for p in (tmp / "store").iterdir()), "no temporary folders left")

    # APKSIM_SKINS: shared folders are searched after the store
    shared = tmp / "shared"
    (shared).mkdir()
    subprocess.run(["cp", "-R", str(tmp / "store" / "test"), str(shared / "other")], check=True)
    sj = json.loads((shared / "other" / "skin.json").read_text()); sj["name"] = "other"
    (shared / "other" / "skin.json").write_text(json.dumps(sj))
    e2 = dict(env, APKSIM_SKINS=str(shared))
    rc, out = skin("list", env=e2)
    check("other" in out and "shared" in out and "test" in out, "APKSIM_SKINS skins listed")
    rc, out = skin("remove", "other", env=e2)
    check(rc == 1 and "shared folder" in out, "a shared skin is not removed")

    print("\nFAILED: " + ", ".join(failures) if failures else "\nall passed")
    sys.exit(1 if failures else 0)


main()
