#!/usr/bin/env python3
"""Compares apksim's window frames with a device's, for the test app's window screens.
Usage: window-frames-check.py APK [--port N | --apksim CMD] [--table FILE] [--known FILE] [--scenes FILE] [-v]
The device's side is testapks/composeapp/window-frames-api36.tsv (scripts/gen-window-frames.py, an API 36 emulator at
822x1782, density 320): no emulator is needed here. Without --port a headless phone instance is started (CMD is
./apksim by default) and quit at the end. Each scene is `restart URL` + `settle`, then `windows`: the windows the
activity stack shows (those of stopped or destroyed activities are left out), bottom to top, are matched to the
table's rows by position, and every difference is printed in pixels (field: kind, left, top, right, bottom, focused,
count). A difference not in window-frames-known.tsv (scene, window, field, device, apksim, reason) fails the run (1);
so does a known one that no longer differs or differs otherwise. docs/conformance.md "Window frames"."""
import argparse, os, re, shlex, socket, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIELDS = ["kind", "left", "top", "right", "bottom", "focused"]


def tsv(path):
    return [l.rstrip("\n").split("\t") for l in open(path) if l.strip() and not l.startswith("#")]


class Control:
    def __init__(self, port):
        for _ in range(600):
            try:
                self.s = socket.create_connection(("127.0.0.1", port), timeout=1)
                break
            except OSError:
                time.sleep(0.1)
        else:
            sys.exit(f"nothing is listening on 127.0.0.1:{port}")
        self.s.settimeout(60)
        self.f = self.s.makefile("rw")

    def __call__(self, cmd):
        self.f.write(cmd + "\n")
        self.f.flush()
        return self.f.readline().strip()


WIN = re.compile(r"(\w+)@(-?\d+),(-?\d+) (\d+)x(\d+)( focused)? (\S+?)\{([^}]*)\}")


def apksim_windows(reply):
    """(kind, l, t, r, b, focused) of the windows a device would show, bottom to top."""
    rows = []
    for m in WIN.finditer(reply):
        cls, x, y, w, h, foc, act, state = m.groups()
        if "stopped" in state or "destroyed" in state:
            continue
        x, y, w, h = int(x), int(y), int(w), int(h)
        rows.append((cls, str(x), str(y), str(x + w), str(y + h), "1" if foc else "0"))
    return rows


def kind_of(cls, dev_kind):
    """apksim's root view class against the device's window type: `kind` is the device's, or the class when they
    disagree (a DecorView is an activity's or a dialog's window; anything else is a panel on the device)."""
    if cls == "DecorView":
        return dev_kind if dev_kind in ("activity", "dialog") else "decor"
    return dev_kind if dev_kind not in ("activity", "dialog") else cls


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--port", type=int)
    ap.add_argument("--apksim", default=os.path.join(ROOT, "apksim"))
    ap.add_argument("--table", default=os.path.join(ROOT, "testapks/composeapp/window-frames-api36.tsv"))
    ap.add_argument("--known", default=os.path.join(ROOT, "testapks/composeapp/window-frames-known.tsv"))
    ap.add_argument("--scenes", default=os.path.join(ROOT, "scripts/window-frames-scenes.txt"))
    ap.add_argument("-v", action="store_true", help="print the windows of each scene")
    a = ap.parse_args()

    scenes = [l.rstrip("\n").split("\t") for l in open(a.scenes) if l.strip() and not l.startswith("#")]
    table = {}
    for r in tsv(a.table):
        table.setdefault(r[0], []).append(r)

    proc, port = None, a.port
    if port is None:
        port = 7400 + (os.getpid() % 500)
        proc = subprocess.Popen(shlex.split(a.apksim) + ["--headless", "--control", str(port), a.apk],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    t0 = time.time()
    try:
        c = Control(port)
        diffs = []  # (scene, window, field, device, apksim)
        for name, url in scenes:
            r = c("restart " + url)
            if not r.startswith("ok"):
                diffs.append((name, "-", "restart", "ok", r))
                continue
            s = c("settle 8000")
            if not s.startswith("settled"):
                print(f"note: {name}: {s}", file=sys.stderr)
            rows = apksim_windows(c("windows"))
            if rows == [] or len(rows) != len(table.get(name, [])):  # a window may still be coming up
                time.sleep(1.0)
                c("settle 8000")
                rows = apksim_windows(c("windows"))
            dev = table.get(name, [])
            if a.v:
                print(f"{name}: device {[tuple(d[2:9]) for d in dev]}\n{' ' * len(name)}  apksim {rows}", file=sys.stderr)
            if len(rows) != len(dev):
                diffs.append((name, "-", "count", str(len(dev)), str(len(rows))))
            for i in range(min(len(rows), len(dev))):
                d, m = dev[i], rows[i]
                dv = {"kind": d[2], "left": d[4], "top": d[5], "right": d[6], "bottom": d[7], "focused": d[8]}
                av = dict(zip(FIELDS, m))
                av["kind"] = kind_of(m[0], d[2])
                for f in FIELDS:
                    if dv[f] != av[f]:
                        diffs.append((name, str(i), f, dv[f], av[f]))
        c("quit") if proc else None
    finally:
        if proc:
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.terminate()

    known = {}
    for r in tsv(a.known) if os.path.exists(a.known) else []:
        known[tuple(r[:3])] = r
    seen = set()
    bad = 0
    for sc, w, f, dv, av in diffs:
        k = (sc, w, f)
        px = ""
        if f in ("left", "top", "right", "bottom"):
            px = f" ({int(av) - int(dv):+d} px)"
        line = f"{sc} window {w} {f}: device {dv}, apksim {av}{px}"
        if k in known and known[k][3] == dv and known[k][4] == av:
            seen.add(k)
            print("known:   " + line + "  # " + (known[k][5] if len(known[k]) > 5 else ""))
        else:
            bad += 1
            print("DIFFERS: " + line + (f"  (known with {known[k][3]} / {known[k][4]})" if k in known else ""))
            seen.add(k)
    for k, r in known.items():
        if k not in seen:
            bad += 1
            print(f"STALE:   {k[0]} window {k[1]} {k[2]}: listed as device {r[3]}, apksim {r[4]}, but it no longer differs")
    print(f"window-frames: {len(scenes)} scenes, {sum(len(v) for v in table.values())} device windows, "
          f"{len(diffs)} differences ({len(diffs) - bad if bad <= len(diffs) else 0} known), {bad} to look at "
          f"({time.time() - t0:.0f} s)")
    sys.exit(1 if bad else 0)


main()
