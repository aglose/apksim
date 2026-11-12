#!/usr/bin/env python3
"""Checks a skin drawn around the screen (`--skin`, the `skin` command, `shot --skin`; docs/skins.md "Drawing").

A fixture skin (a red phone frame with a rounded hole and a camera hole, the phone device) is written into a temporary
skin store, and the test app runs with it:
  - the app is told the skin's cutout and rounded corners (`insets`: WindowInsets' getDisplayCutout,
    getInsets(displayCutout()) and getRoundedCorner), and none once the skin is off;
  - `shot --skin` is the frame's size scaled to the display, transparent outside the device, the frame's pixels on the
    bezel and over the camera hole, and the app's own pixels (`shot`) in the screen;
  - a rotation turns the frame and the cutout with it (ROTATION_90: the camera on the left);
  - an unknown skin stops the launch with exit 2.
With --window (Mac only) the app runs in a window behind the others, and clicks sent through the window (`window click`)
check that the screen's corners land at the app's corners, the floating button counts a click, and the bezel takes none.

Usage: scripts/skin-window-check.py APK [--window] [--port 7463]
       scripts/skin-window-check.py APK --docker IMAGE --out DIR   (headless, in the Linux image; DIR is mounted at /work)
"""
import argparse, json, os, re, shutil, struct, subprocess, sys, tempfile, time, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FW, FH = 560, 1100                          # the frame image
HX, HY, HW, HH, HR = 60, 70, 440, 960, 48   # the screen hole (phone's 822x1782 within 1%)
CX, CY, CR = 280, 90, 10                    # the camera hole, opaque, inside the screen
OR = 70                                     # the frame's outer corner radius
RED = (200, 60, 60)

failures = []


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        failures.append(what)


def png(path, w, h, px):
    raw = b"".join(b"\0" + b"".join(bytes(px(x, y)) for x in range(w)) for y in range(h))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    Path(path).write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def read_png(path):
    d = Path(path).read_bytes()
    pos, idat = 8, b""
    while pos < len(d):
        n = struct.unpack(">I", d[pos:pos + 4])[0]
        t, body = d[pos + 4:pos + 8], d[pos + 8:pos + 8 + n]
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


def inside_round(x, y, x0, y0, w, h, r):
    if not (x0 <= x < x0 + w and y0 <= y < y0 + h):
        return False
    cx = min(max(x + .5, x0 + r), x0 + w - r)
    cy = min(max(y + .5, y0 + r), y0 + h - r)
    return (x + .5 - cx) ** 2 + (y + .5 - cy) ** 2 <= r * r


def frame_px(x, y):
    if (x + .5 - CX) ** 2 + (y + .5 - CY) ** 2 <= CR * CR:
        return (10, 10, 12, 255)               # the camera
    if inside_round(x, y, HX, HY, HW, HH, HR):
        return (0, 0, 0, 0)                    # the hole
    if inside_round(x, y, 0, 0, FW, FH, OR):
        return RED + (255,)                    # the device
    return (0, 0, 0, 0)                        # outside it


def write_skin(store):
    d = store / "fixture"
    d.mkdir(parents=True)
    png(d / "frame.png", FW, FH, frame_px)
    (d / "skin.json").write_text(json.dumps({
        "name": "fixture", "title": "Fixture phone", "frame": "frame.png",
        "screen": {"x": HX, "y": HY, "width": HW, "height": HH, "cornerRadius": HR},
        "device": "phone", "cutout": {"type": "hole", "x": CX, "y": CY, "radius": CR}}))


def near(a, b, tol=12):
    return all(abs(x - y) <= tol for x, y in zip(a[:3], b[:3]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--window", action="store_true", help="Mac: also check clicks through the window")
    ap.add_argument("--docker", help="run in this Linux image (headless)")
    ap.add_argument("--out", help="with --docker: a folder for the store and shots, mounted at /work")
    ap.add_argument("--port", type=int, default=int(os.environ.get("SKINCHECK_PORT", 7463)))
    a = ap.parse_args()
    if a.docker and not a.out:
        sys.exit("--docker needs --out DIR")
    if a.docker and a.window:
        sys.exit("--window is the Mac window: not with --docker")
    work = Path(a.out).resolve() if a.out else Path(tempfile.mkdtemp(prefix="skin-window-"))
    work.mkdir(parents=True, exist_ok=True)
    store = work / "skins"
    shutil.rmtree(store, ignore_errors=True)
    write_skin(store)
    shutil.copy(a.apk, work / "app.apk")
    inside = Path("/work") if a.docker else work   # paths as the instance sees them

    def launch(*opts, detach=True):
        if a.docker:
            name = f"apksim-skin-check-{os.getpid()}-{len(opts)}"
            cmd = ["docker", "run", "--rm", "--name", name, "-v", f"{work}:/work", "-e", "APKSIM_SKINS_DIR=/work/skins"]
            if detach:
                cmd += ["-d", "-p", f"127.0.0.1:{a.port}:{a.port}"]
            cmd += [a.docker, *opts, "app.apk"]
            if detach:
                cmd[-1:-1] = ["--control", str(a.port), "--control-bind", "0.0.0.0"]
            return name, subprocess.run(cmd, capture_output=True, text=True)
        env = dict(os.environ, APKSIM_SKINS_DIR=str(store), APKSIM_SKINS="", APKSIM_STANDBY="0")
        cmd = [str(ROOT / "apksim"), *opts]
        if detach:
            cmd += ["--control", str(a.port)]
            cmd += ["--window", "background"] if a.window else ["--headless"]
            log = open(work / "run.log", "w")
            return subprocess.Popen(cmd + [str(work / "app.apk")], stdout=log, stderr=subprocess.STDOUT, env=env), None
        return None, subprocess.run(cmd + [str(work / "app.apk")], capture_output=True, text=True, env=env, timeout=120)

    # An unknown skin stops the launch, as a bad --device does.
    _, r = launch("--skin", "nope", "--headless", detach=False)
    check(r.returncode == 2 and "no skin named nope" in r.stdout + r.stderr, f"an unknown skin stops the launch (exit {r.returncode})")

    def drive(*cmds):
        r = subprocess.run(["python3", str(ROOT / "scripts" / "drive.py"), str(a.port), *cmds], capture_output=True, text=True, timeout=120)
        return r.stdout

    def reply(out, cmd):
        m = re.search("^" + re.escape(cmd) + r" -> (.*)$", out, re.M)
        return m.group(1) if m else ""

    proc, started = launch("--skin", "fixture")
    proc_name = proc if a.docker else None
    if a.docker:
        proc = None
    try:
        # One overall deadline: each drive.py ping already retries its connection for a while, and an app that exited
        # never answers, so stop as soon as the process (or container) is gone.
        deadline = time.time() + 240
        up = False
        while time.time() < deadline:
            if proc is not None and proc.poll() is not None:
                break
            if a.docker and subprocess.run(["docker", "inspect", "-f", "{{.State.Running}}", proc_name], capture_output=True,
                                           text=True).stdout.strip() != "true":
                break
            if "pong" in drive("ping"):
                up = True
                break
            time.sleep(1)
        if not up:
            check(False, "the app answered on the control channel")
            return
        drive("wait 1500")
        s = reply(drive("skin"), "skin")
        print("skin: " + s)
        m = re.search(r"frame (\d+)x(\d+) px \(x([\d.]+), 0 deg\), screen at (\d+),(\d+) 822x1782, corners (\d+), cutout (\d+),(\d+)-(\d+),(\d+)", s)
        check(m is not None, "the skin is on, at the phone's 822x1782")
        if not m:
            return
        ow, oh, k, sx, sy, rad = int(m[1]), int(m[2]), float(m[3]), int(m[4]), int(m[5]), int(m[6])
        cut = tuple(int(m[i]) for i in range(7, 11))
        ke = 1782 / HH  # device pixels per frame pixel, from the screen's long side
        check(abs(k - ke) < 0.001 and abs(ow - FW * ke) <= 1 and abs(oh - FH * ke) <= 1, f"frame scaled to the display ({ow}x{oh}, x{k})")
        check(abs(rad - HR * k) <= 1, f"corner radius scaled ({rad})")
        ccx, ccy = (cut[0] + cut[2]) / 2, (cut[1] + cut[3]) / 2
        check(abs(ccx - ((CX - HX) * k - (sx - round(HX * k)))) <= 2 and abs(ccy - ((CY - HY) * k - (sy - round(HY * k)))) <= 2,
              f"cutout at the camera hole ({cut})")

        ins = reply(drive("insets"), "insets")
        print("insets: " + ins)
        check(f"cutout=[{cut[0]},{cut[1]}][{cut[2]},{cut[3]}]" in ins, "the app's WindowInsets have the cutout")
        check(f"top={cut[3]}" in (re.search(r"safe=(Insets\{[^}]*\})", ins) or [None, ""])[1], "displayCutout() insets clear it at the top")
        check(f"corners={rad},{rad},{rad},{rad}" in ins, "the app's WindowInsets have the rounded corners")

        drive(f"shot --skin {inside}/skinned.png", f"shot {inside}/plain.png")
        w, h, px = read_png(work / "skinned.png")
        pw, ph, ppx = read_png(work / "plain.png")
        check((w, h) == (ow, oh), f"shot --skin is the frame's size ({w}x{h})")
        check(px(2, 2)[3] == 0 and px(w - 3, h - 3)[3] == 0, "transparent outside the device")
        check(near(px(int(20 * k), int(FH / 2 * k)), RED), f"the bezel is the frame's {px(int(20 * k), int(FH / 2 * k))}")
        check(px(int(CX * k), int(CY * k))[:3] == (10, 10, 12) or near(px(int(CX * k), int(CY * k)), (10, 10, 12), 6),
              "the camera hole is drawn over the screen")
        same = 0
        for fx, fy in ((0.5, 0.5), (0.25, 0.3), (0.75, 0.7), (0.5, 0.9), (0.1, 0.5)):
            x, y = int(pw * fx), int(ph * fy)
            same += near(px(sx + x, sy + y), ppx(x, y), 3)
        check(same == 5, f"the app's pixels fill the screen ({same} of 5 samples match `shot`)")
        check(px(sx + 1, sy + 1)[:3] != ppx(1, 1)[:3] or ppx(1, 1)[:3] == RED, "the screen's corner is rounded off")

        if a.window:
            st = lambda: reply(drive("wait 400", "stats"), "stats")
            win = reply(drive("window"), "window")
            check(win.startswith("visible"), f"window: {win}")
            s0 = float(re.search(r"([\d.]+) px/pt", win)[1])  # device pixels per point (the window's zoom)
            fab = lambda: re.search(r'FAB clicks: (\d+)', drive("nodes"))
            n0 = int(fab()[1])
            at = lambda x, y: f"window click {(x + sx) / s0:.2f} {(y + sy) / s0:.2f}"
            drive(at(1, 1))
            d1 = re.search(r"lastDown=(-?\d+),(-?\d+)", st())
            drive(at(820, 1780))
            d2 = re.search(r"lastDown=(-?\d+),(-?\d+)", st())
            check(abs(int(d1[1]) - 1) <= 2 and abs(int(d1[2]) - 1) <= 2, f"a click at the screen's top left lands at the app's ({d1[1]},{d1[2]})")
            check(abs(int(d2[1]) - 820) <= 2 and abs(int(d2[2]) - 1780) <= 2, f"a click at its bottom right lands at the app's ({d2[1]},{d2[2]})")
            drive(at(733, 1533))
            n1 = n0
            for _ in range(15):  # the count shows once the app has recomposed: poll for it, up to 3 s
                n1 = int(fab()[1])
                if n1 != n0:
                    break
                time.sleep(0.2)
            check(n1 == n0 + 1, f"a click on the floating button counts ({n0} -> {n1})")
            d2 = re.search(r"lastDown=(-?\d+),(-?\d+)", st())
            drive(f"window click {10 * k / s0:.2f} {FH / 2 * k / s0:.2f}")
            d3 = re.search(r"lastDown=(-?\d+),(-?\d+)", st())
            check((d3[1], d3[2]) == (d2[1], d2[2]) and int(fab()[1]) == n1, "a click on the bezel reaches nothing")
            drive(f"wshot {inside}/window.ppm")
            check((work / "window.ppm").stat().st_size > 1000, "wshot captured the framed window")

        out = drive("rotate right", "wait 1200", "skin", "insets")
        s = reply(out, "skin")
        print("rotated: " + s)
        m = re.search(r"90 deg\), screen at \d+,\d+ 1782x822, .* cutout (\d+),(\d+)-(\d+),(\d+)", s)
        check(m is not None and int(m[3]) < 100 and 300 < int(m[2]) < 500, "a rotation turns the frame: the camera is on the left")
        check(m is not None and f"cutout=[{m[1]},{m[2]}][{m[3]},{m[4]}]" in reply(out, "insets"), "and the app's cutout with it")
        out = drive("rotate left", "wait 800", "skin off", "wait 800", "insets", "skin")
        ins = reply(out, "insets")
        check("cutout=" not in ins and "corners=" not in ins, f"no cutout or corners once the skin is off ({ins[:80]})")
        check(reply(out, "skin").startswith("off; stored: fixture"), "skin off, the fixture still listed")
        out = drive("skin fixture", "device tablet", "skin")
        check("does not frame 2560x1600" in reply(out, "device tablet") and reply(out, "skin").startswith("off"),
              "another device's shape turns the skin off")
    finally:
        try:
            drive("quit")
        except Exception:
            pass
        if a.docker:
            subprocess.run(["docker", "rm", "-f", f"apksim-skin-check-{os.getpid()}-2"], capture_output=True)
        elif proc:
            try:
                proc.wait(20)
            except subprocess.TimeoutExpired:
                proc.kill()


# The verdict whatever way main() ended: an early return (the app never answered, the skin was not on) is a failure
# already recorded, and must not exit 0.
try:
    main()
finally:
    print("\nFAILED: " + ", ".join(failures) if failures else "\nall passed")
sys.exit(1 if failures else 0)
