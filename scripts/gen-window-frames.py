#!/usr/bin/env python3
"""Writes the table of each window's frame on a real device for the test app's window screens
(testapks/composeapp/window-frames-api36.tsv), read from `dumpsys window windows`.
Usage: gen-window-frames.py -s SERIAL [APK] [--scenes FILE] [--out FILE] [--adb PATH] [--no-install]
The device must be at 822x1782, density 320 (the comparison AVD, AGENTS.md "Comparing with the emulator"): it is
checked, never changed. Installs APK (when given) and removes it again. docs/conformance.md "Window frames"."""
import argparse, os, re, subprocess, sys, time

PKG = "dev.apksim.composeapp"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KINDS = {"BASE_APPLICATION": "activity", "APPLICATION": "dialog", "APPLICATION_PANEL": "panel",
         "APPLICATION_MEDIA": "media", "APPLICATION_SUB_PANEL": "subpanel",
         "APPLICATION_ATTACHED_DIALOG": "attached-dialog", "APPLICATION_ABOVE_SUB_PANEL": "abovesubpanel"}


def sh(adb, serial, *a, check=True):
    r = subprocess.run([adb, "-s", serial, *a], capture_output=True, text=True, timeout=120)
    if check and r.returncode:
        sys.exit(f"adb {' '.join(a)} failed: {r.stderr.strip()}")
    return r.stdout


def scenes(path):
    out = []
    for line in open(path):
        line = line.rstrip("\n")
        if line.strip() and not line.startswith("#"):
            name, url = line.split("\t")
            out.append((name, url))
    return out


def parse(dump, focus):
    """The test app's visible windows, bottom to top: (kind, title, l, t, r, b, focused, dim)."""
    rows = []
    for blk in re.split(r"\n(?=  Window #)", dump):
        m = re.match(r"\s*Window #\d+ Window\{(\w+) u0 ([^}]*)\}:", blk)
        if not m or f"package={PKG}" not in blk:
            continue
        if "isVisible=true" not in blk:
            continue
        ty = re.search(r"\bty=(\w+)", blk)
        fr = re.search(r"\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", blk)
        if not ty or not fr:
            continue
        title = m.group(2).split("/")[-1].split(".")[-1]
        title = re.sub(r":[0-9a-f]+$", "", title)
        dim = "1" if re.search(r"\bfl=[^\n]*\bDIM_BEHIND\b", blk) else "0"
        rows.append((KINDS.get(ty.group(1), ty.group(1)), title, *fr.groups(),
                     "1" if m.group(1) == focus else "0", dim))
    rows.reverse()  # the dump lists the top window first
    return rows


def read(adb, serial):
    dump = sh(adb, serial, "shell", "dumpsys", "window", "windows")
    foc = re.search(r"mCurrentFocus=Window\{(\w+) ", sh(adb, serial, "shell", "dumpsys", "window"))
    return parse(dump, foc.group(1) if foc else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", nargs="?")
    ap.add_argument("-s", "--serial", required=True)
    ap.add_argument("--scenes", default=os.path.join(ROOT, "scripts/window-frames-scenes.txt"))
    ap.add_argument("--out", default=os.path.join(ROOT, "testapks/composeapp/window-frames-api36.tsv"))
    ap.add_argument("--adb", default=os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    ap.add_argument("--no-install", action="store_true", help="the app is installed already; leave it there")
    a = ap.parse_args()
    adb, ser = a.adb, a.serial
    size = sh(adb, ser, "shell", "wm", "size")
    dens = sh(adb, ser, "shell", "wm", "density")
    if not re.search(r"(Override|Physical) size: 822x1782", size) or not re.search(r"Override density: 320", dens):
        sys.exit(f"{ser} is not 822x1782 at density 320 (not changed):\n{size}{dens}")
    installed_here = False
    if a.apk and not a.no_install:
        sh(adb, ser, "install", "-r", a.apk)
        installed_here = True
    try:
        lines = ["# scene\tindex\tkind\ttitle\tleft\ttop\tright\tbottom\tfocused\tdim",
                 "# Written by scripts/gen-window-frames.py from `dumpsys window windows` of an API 36 emulator at 822x1782, "
                 "density 320: the test app's visible windows, bottom to top. dim: FLAG_DIM_BEHIND set."]
        nwin = 0
        sc = scenes(a.scenes)
        for name, url in sc:
            sh(adb, ser, "shell", "am", "force-stop", PKG)
            time.sleep(0.5)
            sh(adb, ser, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", url)
            prev, same, rows = None, 0, []
            for _ in range(40):
                time.sleep(0.6)
                rows = read(adb, ser)
                same = same + 1 if rows == prev and rows else 0
                prev = rows
                if same >= 3:
                    break
            else:
                sys.exit(f"{name}: windows never stable: {rows}")
            for i, r in enumerate(rows):
                lines.append("\t".join([name, str(i), *r]))
            nwin += len(rows)
            print(f"{name}: {len(rows)} windows", file=sys.stderr)
        sh(adb, ser, "shell", "am", "force-stop", PKG)
        open(a.out, "w").write("\n".join(lines) + "\n")
        print(f"wrote {a.out}: {len(sc)} scenes, {nwin} windows", file=sys.stderr)
    finally:
        if installed_here:
            sh(adb, ser, "uninstall", PKG, check=False)


main()
