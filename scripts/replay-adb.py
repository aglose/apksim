#!/usr/bin/env python3
"""Replays a flight recording (.apkrec, docs/flight-recorder.md) on a real device over adb.

    scripts/replay-adb.py BUNDLE -s SERIAL [--apk APK] [--pace 1.0] [--capture FILE.mp4] [--dry-run]

To tell whether a bug is the app's or the simulator's: the checkpoint is put on the device, the journal after it is
played as device input, and the report says what could not be.

  1. The APK on the device is checked against the manifest's apkSha256 (pulled and hashed; --apk installs that file
     first, after checking it against the same hash). A different build is refused unless --allow-other-build.
  2. The app is stopped and the checkpoint's data (checkpoint/data.zip: the app's data folder, and its /sdcard and
     Settings values) put back: the folder through `run-as` (a debuggable build) or, on a device where adb is root or
     the folder is writable over adb, directly; /sdcard by adb push; the Settings values by `settings put`.
  3. The checkpoint's display size and density are set with `wm size` / `wm density` (reset at the end), or with
     --scale-coordinates the recorded coordinates are scaled to the device's own display instead.
  4. The checkpoint's top activity is started by component (`am start -n`). Its intent's extras and the saved
     instance state are Android parcels this script does not unparcel, so they are reported, not restored.
  5. input.jsonl is played at its recorded offsets (divided by --pace): a finger down..up is one `input tap` or `input
     swipe` with the gesture's duration (a drag's path is its end points; --path-steps N cuts it into N swipes);
     keys and Back as keyevents, typed characters as `input text`, device commands (rotate, appearance, locale,
     fontscale, animscale, open, home) as `settings` / `cmd` / `am` calls. Anything else (two fingers, location,
     push, biometrics...) is skipped and listed.
  6. --capture FILE records the device's screen (screenrecord, 3 minutes at most) next to the bundle's screen.avi,
     and --logcat FILE writes the device's log of the replay.

The network is live: a device cannot be handed network.har's answers, so the app talks to its real servers, and the
report says so. Typed characters that were left out of the recording (password fields, pasted text) are replayed as "x".
--dry-run prints the plan and the report without touching adb.
"""
import argparse
import hashlib
import io
import json
import os
import re
import shlex
import struct
import subprocess
import sys
import tempfile
import time
import zipfile

REMOTE = "/data/local/tmp/apksim-replay"
KEYS = {"back": 4, "home": 3}


class Plan:
    """What the replay will do, built from the bundle alone (so --dry-run and the tests need no device)."""

    def __init__(self, z, pace=1.0, path_steps=1):
        self.man = json.loads(z.read("manifest.json"))
        self.pkg = self.man["package"]
        self.pace = pace
        self.notes = ["network: live (a device cannot be given network.har's answers)"]
        self.skipped = []
        cp = self.man.get("checkpoint")
        self.cp = cp
        self.device = (cp or {}).get("device") or self.man.get("device") or {}
        names = set(z.namelist())
        self.stack = self.read_stack(z) if "checkpoint/state.bin" in names else []
        self.has_data = "checkpoint/data.zip" in names
        events = []
        if "input.jsonl" in names:
            events = [json.loads(line) for line in z.read("input.jsonl").decode().splitlines() if line.strip()]
        self.steps = self.build(events, path_steps)

    @staticmethod
    def read_stack(z):
        """Activity class names bottom first, and how many bytes of intent and state each had (-1: not parcelled)."""
        b = io.BytesIO(z.read("checkpoint/state.bin"))
        n = struct.unpack(">i", b.read(4))[0]
        out = []
        for _ in range(n):
            ln = struct.unpack(">H", b.read(2))[0]
            name = b.read(ln).decode("utf-8", "replace")
            sizes = []
            for _ in range(2):
                k = struct.unpack(">i", b.read(4))[0]
                sizes.append(k)
                if k > 0:
                    b.read(k)
            out.append({"class": name, "intent": sizes[0], "state": sizes[1]})
        return out

    def skip(self, what):
        self.skipped.append(what)

    def build(self, events, path_steps):
        """[(offset ms, kind, args)]: kinds tap, swipe, key, text, shell (a shell line)."""
        steps = []
        if not events:
            return steps
        t0 = events[0]["t"]
        i = 0
        text = None  # [offset, chars]

        def flush():
            nonlocal text
            if text:
                steps.append((text[0], "text", "".join(text[1])))
            text = None

        while i < len(events):
            e = events[i]
            off = e["t"] - t0
            i += 1
            if "cmd" in e:
                flush()
                self.command(steps, off, e["cmd"])
                continue
            ev = e.get("ev")
            if ev == "char":
                ch = "x" if e.get("redacted") else chr(int(e.get("v", 63)))
                if text and off - text[2] < 1500:
                    text[1].append(ch)
                    text[2] = off
                else:
                    flush()
                    text = [off, [ch], off]
                continue
            if ev == "key":
                flush()
                if e.get("redacted"):
                    if text is None:
                        text = [off, [], off]
                    text[1].append("x")
                    continue
                steps.append((off, "key", int(e["v"])))
            elif ev == "back":
                flush()
                steps.append((off, "key", 4))
            elif ev == "down":
                flush()
                pts = [(off, e["x"], e["y"])]
                while i < len(events) and events[i].get("ev") in ("move", "up", "cancel", None) and "cmd" not in events[i]:
                    f = events[i]
                    i += 1
                    pts.append((f["t"] - t0, f.get("x", pts[-1][1]), f.get("y", pts[-1][2])))
                    if f.get("ev") in ("up", "cancel"):
                        break
                self.gesture(steps, pts, path_steps)
            elif ev == "wheel":
                flush()
                # x, y, amount: a scroll of that many notches, played as a swipe the other way (content follows the finger)
                amount = float(e.get("v", 0))
                x, y = e["x"], e["y"]
                steps.append((off, "swipe", (x, y, x, y - amount * 80, 120)))
                self.notes.append("wheel events are played as swipes (approximate)")
            elif ev in ("resize", "close"):
                self.skip(ev)
            elif ev and ev.startswith("touch2"):
                self.skip("two-finger gesture (adb input has no multi-touch)")
            elif ev in ("move", "up", "cancel"):
                pass  # a stray one after a gesture the window closed
            else:
                self.skip("input " + str(ev))
        flush()
        return steps

    def gesture(self, steps, pts, path_steps):
        (t0, x0, y0), (t1, x1, y1) = pts[0], pts[-1]
        dur = max(1, int(t1 - t0))
        if abs(x1 - x0) < 20 and abs(y1 - y0) < 20 and all(abs(x - x0) < 20 and abs(y - y0) < 20 for _, x, y in pts):
            steps.append((t0, "tap" if dur < 400 else "swipe", (x0, y0) if dur < 400 else (x0, y0, x0, y0, dur)))
            return
        n = max(1, path_steps)
        picks = [pts[round(k * (len(pts) - 1) / n)] for k in range(n + 1)]
        for a, b in zip(picks, picks[1:]):
            steps.append((a[0], "swipe", (a[1], a[2], b[1], b[2], max(1, int(b[0] - a[0])))))

    def command(self, steps, off, cmd):
        name, args = cmd[0], cmd[1:]
        if name == "rotate" and args:
            rot = {"portrait": 0, "left": 1, "landscape": 1, "right": 3}.get(args[0])
            if rot is None:
                return self.skip("rotate " + args[0])
            steps.append((off, "shell", "settings put system accelerometer_rotation 0; settings put system user_rotation %d" % rot))
        elif name == "appearance" and args and args[0] in ("dark", "light"):
            steps.append((off, "shell", "cmd uimode night " + ("yes" if args[0] == "dark" else "no")))
        elif name == "fontscale" and args and re.fullmatch(r"[0-3](\.\d+)?", args[0]):
            steps.append((off, "shell", "settings put system font_scale " + args[0]))
        elif name == "animscale" and args and re.fullmatch(r"\d+(\.\d+)?", args[0]):
            steps.append((off, "shell", "; ".join("settings put global %s %s" % (k, args[0]) for k in (
                "window_animation_scale", "transition_animation_scale", "animator_duration_scale"))))
        elif name == "locale" and args and re.fullmatch(r"[A-Za-z0-9-]+", args[0]):
            steps.append((off, "shell", "cmd locale set-app-locales %s --locales %s" % (self.pkg, args[0])))
        elif name == "open" and args and re.fullmatch(r"[A-Za-z][A-Za-z0-9+.-]*:[^\s'\"`$;&|<>\\]+", args[0]):
            steps.append((off, "shell", "am start -a android.intent.action.VIEW -d " + shlex.quote(args[0])))
        elif name == "home":
            steps.append((off, "key", 3))
        elif name == "back":
            steps.append((off, "key", 4))
        elif name in ("lock", "unlock"):
            steps.append((off, "key", 26 if name == "lock" else 82))
        else:
            self.skip(" ".join(cmd[:2]) if cmd else "command")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def parse_properties(text):
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line[0] in "#!":
            continue
        m = re.match(r"((?:[^=:\\\s]|\\.)+)\s*[=:]\s*(.*)$", line)
        if not m:
            continue

        def un(s):
            s = re.sub(r"\\u([0-9a-fA-F]{4})", lambda g: chr(int(g.group(1), 16)), s)
            return re.sub(r"\\(.)", lambda g: {"n": "\n", "t": "\t"}.get(g.group(1), g.group(1)), s)

        out[un(m.group(1))] = un(m.group(2))
    return out


class Adb:
    def __init__(self, serial):
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args, check=True, input=None, timeout=300):
        p = subprocess.run(self.base + list(args), capture_output=True, input=input, timeout=timeout)
        out = (p.stdout + p.stderr).decode("utf-8", "replace")
        if check and p.returncode != 0:
            raise SystemExit("adb %s failed: %s" % (" ".join(args[:3]), out.strip()))
        return out

    def sh(self, line, **kw):
        return self.run("shell", line, **kw)


def fail(msg):
    print("replay-adb: " + msg, file=sys.stderr)
    sys.exit(1)


def prepare(adb, plan, z, args, report):
    pkg = plan.pkg
    want = plan.man.get("apkSha256")
    if args.apk:
        data = open(args.apk, "rb").read()
        if want and sha256(data) != want:
            fail("--apk is not the build that was recorded (SHA-256 differs from the manifest's)")
        adb.run("install", "-r", args.apk, timeout=600)
        report.append("installed " + os.path.basename(args.apk))
    path = adb.sh("pm path " + pkg).strip().splitlines()
    if not path or not path[0].startswith("package:"):
        fail("%s is not installed on the device (pass --apk)" % pkg)
    if want and not args.apk:
        with tempfile.TemporaryDirectory() as d:
            dest = os.path.join(d, "base.apk")
            pulled = adb.run("pull", path[0][8:], dest, check=False)
            got = sha256(open(dest, "rb").read()) if os.path.isfile(dest) else None
        if got is None:
            if args.require_build:
                fail("could not pull the installed APK to check it: " + pulled.strip())
            report.append("build: could not be checked (the APK cannot be pulled from this device)")
        elif got != want and not args.allow_other_build:
            fail("the installed build differs from the recorded one (%s vs %s); --apk installs the right one, "
                 "--allow-other-build replays on this one anyway" % (got[:12], want[:12]))
        else:
            report.append("build: " + ("the recorded one" if got == want else "DIFFERENT from the recorded one"))
    adb.sh("am force-stop " + pkg)
    if not plan.has_data:
        report.append("no checkpoint data in the bundle: the app starts with the data it has")
    else:
        restore_data(adb, plan, z, report)
    # The checkpoint's display
    dev = plan.device
    w, h, dens = dev.get("width"), dev.get("height"), dev.get("density")
    scale = (1.0, 1.0)
    if w and h and not args.scale_coordinates:
        adb.sh("wm size %dx%d" % (w, h))
        if dens:
            adb.sh("wm density %d" % round(dens * 160))
        report.append("display set to %dx%d at %d dpi (reset at the end)" % (w, h, round((dens or 2) * 160)))
    elif w and h:
        m = re.search(r"(?:Override|Physical) size: (\d+)x(\d+)", adb.sh("wm size"))
        if m:
            scale = (int(m.group(1)) / w, int(m.group(2)) / h)
            report.append("coordinates scaled by %.3f x %.3f to the device's %sx%s" % (scale[0], scale[1], m.group(1), m.group(2)))
    return scale


def restore_data(adb, plan, z, report):
    pkg = plan.pkg
    zin = zipfile.ZipFile(io.BytesIO(z.read("checkpoint/data.zip")))
    if zin.namelist().count("package") and zin.read("package").decode().strip() != pkg:
        fail("data.zip is for another package")
    stage = tempfile.mkdtemp(prefix="replay-adb-")
    count = 0
    for n in zin.namelist():
        if n.endswith("/") or n == "package":
            continue
        if ".." in n.split("/") or n.startswith("/") or n.split("/")[0] not in ("slot", "device"):
            continue
        dest = os.path.join(stage, n)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(zin.read(n))
        count += 1
    slot, dev = os.path.join(stage, "slot"), os.path.join(stage, "device")
    # Settings values first: they are not files on a device.
    sp = os.path.join(dev, "settings.properties")
    if os.path.isfile(sp):
        n = 0
        for k, v in parse_properties(open(sp, encoding="utf-8").read()).items():
            deleted = k.startswith("!")
            ns, _, name = k.lstrip("!").partition("/")
            if ns not in ("global", "secure", "system") or not re.fullmatch(r"[A-Za-z0-9_.:-]+", name):
                continue
            if deleted:
                adb.sh("settings delete %s %s" % (ns, name), check=False)
            else:
                adb.sh("settings put %s %s %s" % (ns, name, shlex.quote(v)), check=False)
            n += 1
        report.append("%d Settings values put back" % n)
    if os.path.isdir(os.path.join(dev, "sdcard")):
        adb.run("push", os.path.join(dev, "sdcard") + "/.", "/sdcard/", check=False)
        report.append("/sdcard put back")
    if not os.path.isdir(slot):
        return
    data = "/data/data/" + pkg
    # Directly when adb may write there (an emulator with adb root, apksim itself), else through run-as.
    probe = adb.run("push", os.path.join(stage, "slot", "."), data + "/", check=False)
    if "error" not in probe.lower() and "denied" not in probe.lower() and "failed" not in probe.lower():
        report.append("app data (%d files) pushed straight into %s" % (count, data))
        return
    adb.sh("rm -rf %s; mkdir -p %s" % (REMOTE, REMOTE))
    adb.run("push", slot + "/.", REMOTE + "/")
    adb.sh("chmod -R a+rX %s" % REMOTE)
    out = adb.sh("run-as %s sh -c %s" % (pkg, shlex.quote(
        "rm -rf ./files ./databases ./shared_prefs ./cache; cp -R %s/. ." % REMOTE)), check=False)
    adb.sh("rm -rf " + REMOTE, check=False)
    if out.strip():
        fail("could not put the app's data back (the app must be debuggable for run-as, or adb root): " + out.strip())
    report.append("app data (%d files) copied in through run-as" % count)


def play(adb, plan, scale, args):
    t0 = time.monotonic()
    done = skipped = 0
    for off, kind, a in plan.steps:
        due = t0 + (off / 1000.0) / args.pace
        wait = due - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        if kind == "tap":
            line = "input tap %d %d" % (a[0] * scale[0], a[1] * scale[1])
        elif kind == "swipe":
            dur = max(1, int(a[4] / args.pace))
            line = "input swipe %d %d %d %d %d" % (a[0] * scale[0], a[1] * scale[1], a[2] * scale[0], a[3] * scale[1], dur)
        elif kind == "key":
            line = "input keyevent %d" % a
        elif kind == "text":
            line = "input text " + shlex.quote(re.sub(r"[^A-Za-z0-9._,@-]", lambda m: "%s" if m.group(0) == " " else "", a))
            if a.strip() == "":
                continue
        else:
            line = a
        adb.sh(line, check=False)
        done += 1
    return done


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle")
    ap.add_argument("-s", "--serial")
    ap.add_argument("--apk")
    ap.add_argument("--allow-other-build", action="store_true")
    ap.add_argument("--require-build", action="store_true", help="refuse when the installed APK cannot be pulled and hashed")
    ap.add_argument("--pace", type=float, default=1.0, help="2: twice as fast, 0.5: half speed")
    ap.add_argument("--path-steps", type=int, default=1)
    ap.add_argument("--scale-coordinates", action="store_true", help="scale input to the device's display instead of wm size")
    ap.add_argument("--capture", metavar="FILE.mp4")
    ap.add_argument("--logcat", metavar="FILE")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--json", action="store_true", help="the report as JSON")
    args = ap.parse_args()
    if args.pace <= 0:
        fail("--pace must be above 0")
    z = zipfile.ZipFile(args.bundle)
    plan = Plan(z, args.pace, args.path_steps)
    if plan.man.get("format", "").split("/")[0] != "apksim-flight-recording":
        fail("not a flight recording")
    report = []
    if not plan.cp:
        report.append("no checkpoint in the bundle: the replay starts from whatever state the app is in")
    if plan.stack:
        top = plan.stack[-1]
        if top["intent"] != 0 or any(a["state"] > 0 for a in plan.stack):
            plan.notes.append("the intent's extras and the saved instance state are not restored (parcels this script "
                              "does not read); the top activity is started by its class")
    if args.dry_run:
        report.append("dry run: nothing was sent to the device")
        played = 0
    else:
        adb = Adb(args.serial)
        adb.run("get-state")
        scale = prepare(adb, plan, z, args, report)
        if args.logcat:
            adb.run("logcat", "-c", check=False)
        cap = False
        if args.capture:
            subprocess.Popen(adb.base + ["shell", "screenrecord --time-limit 180 /sdcard/apksim-replay.mp4"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            cap = True
            time.sleep(1)
        top = plan.stack[-1]["class"] if plan.stack else None
        if top:
            out = adb.sh("am start -n %s/%s" % (plan.pkg, top))
            if "Error" in out:
                report.append("could not start %s: %s" % (top, out.strip().splitlines()[-1]))
        else:
            adb.sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % plan.pkg, check=False)
        time.sleep(2)
        try:
            played = play(adb, plan, scale, args)
        finally:
            if not args.scale_coordinates and plan.device.get("width"):
                adb.sh("wm size reset; wm density reset", check=False)
        if cap:
            time.sleep(1)
            adb.sh("pkill -INT screenrecord", check=False)
            time.sleep(2)
            adb.run("pull", "/sdcard/apksim-replay.mp4", args.capture, check=False)
            adb.sh("rm /sdcard/apksim-replay.mp4", check=False)
            report.append("device screen recorded to " + args.capture)
        if args.logcat:
            with open(args.logcat, "w") as f:
                f.write(adb.run("logcat", "-d", check=False))
            report.append("device log written to " + args.logcat)
    result = {
        "package": plan.pkg,
        "bundle": os.path.basename(args.bundle),
        "steps": len(plan.steps),
        "played": played,
        "skipped": sorted(set(plan.skipped)),
        "notes": report + sorted(set(plan.notes)),
    }
    if args.json:
        print(json.dumps(result, indent=1))
    else:
        print("replay of %s: %d steps%s" % (result["bundle"], result["steps"], "" if args.dry_run else ", %d played" % played))
        for n in result["notes"]:
            print("  " + n)
        if result["skipped"]:
            print("  not replayed: " + "; ".join(result["skipped"]))


if __name__ == "__main__":
    main()
