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
        if not isinstance(self.pkg, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", self.pkg):
            raise SystemExit("replay-adb: the manifest's package name is not a valid one")
        self.rot = 0
        self.font = None
        self.plain = []
        self.pace = pace
        self.notes = ["network: live (a device cannot be given network.har's answers)"]
        self.skipped = []
        cp = self.man.get("checkpoint")
        self.cp = cp
        self.device = (cp or {}).get("device") or self.man.get("device") or {}
        fs = self.device.get("fontScale")
        if isinstance(fs, (int, float)) and not isinstance(fs, bool) and 0.25 <= fs <= 4:
            self.font = "%g" % fs
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
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$.]*", name):
                raise SystemExit("replay-adb: an activity name in state.bin is not a valid class name")
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
        t0 = (self.cp or {}).get("uptime", events[0]["t"])
        t0 = min(t0, events[0]["t"])
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
                if text and off == text[2]:
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
                if e.get("x"):
                    self.skip("key %d with modifiers (adb input cannot send them)" % int(e["v"]))
                else:
                    steps.append((off, "key", int(e["v"])))
            elif ev == "back":
                flush()
                steps.append((off, "key", 4))
            elif ev == "down":
                flush()
                pts = [(off, e["x"], e["y"])]
                cancelled = False
                ended = False
                while i < len(events) and events[i].get("ev") in ("move", "up", "cancel", None) and "cmd" not in events[i]:
                    f = events[i]
                    i += 1
                    pts.append((f["t"] - t0, f.get("x", pts[-1][1]), f.get("y", pts[-1][2])))
                    if f.get("ev") == "cancel":
                        cancelled = True
                    if f.get("ev") in ("up", "cancel"):
                        ended = True
                        break
                if not ended:
                    self.skip("gesture with no finger-up in the journal (not completed: it would invent a tap)")
                elif cancelled:
                    self.skip("cancelled gesture (a completed tap would activate what was never clicked)")
                else:
                    self.gesture(steps, pts, path_steps)
            elif ev == "wheel":
                flush()
                # x, y, amount (-deltaY/100: negative scrolls down): played as a swipe whose end is `amount` further down the
                # screen, so the finger moves down for a scroll up, as it does on a device
                amount = float(e.get("v", 0))
                x, y = e["x"], e["y"]
                steps.append((off, "swipe", (x, y, x, y + amount * 80, 120)))
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
            # DeviceControls: right is one quarter turn up, left three; portrait/landscape are absolute.
            if args[0] == "right":
                self.rot = (self.rot + 1) % 4
            elif args[0] == "left":
                self.rot = (self.rot + 3) % 4
            elif args[0] == "portrait":
                self.rot = 0
            elif args[0] == "landscape":
                self.rot = 1 if self.rot % 2 == 0 else self.rot
            else:
                return self.skip("rotate " + args[0])
            steps.append((off, "rotate", self.rot))
        elif name == "appearance" and args and args[0] in ("dark", "light"):
            steps.append((off, "shell", "cmd uimode night " + ("yes" if args[0] == "dark" else "no")))
        elif name == "fontscale" and args and re.fullmatch(r"([0-3](\.\d+)?|4(\.0+)?)", args[0]):
            steps.append((off, "shell", "settings put system font_scale " + args[0]))
        elif name == "animscale" and args and re.fullmatch(r"\d+(\.\d+)?", args[0]):
            steps.append((off, "shell", "; ".join("settings put global %s %s" % (k, args[0]) for k in (
                "window_animation_scale", "transition_animation_scale", "animator_duration_scale"))))
        elif name == "locale" and args and re.fullmatch(r"[A-Za-z0-9-]+", args[0]):
            steps.append((off, "shell", "cmd locale set-app-locales %s --locales %s" % (self.pkg, args[0])))
        elif name == "open" and args and re.fullmatch(r"[A-Za-z][A-Za-z0-9+.-]*:[^\x00-\x20\x7f]+", args[0]):
            steps.append((off, "shell", "am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -p %s -d %s" % (self.pkg, shlex.quote(args[0]))))
        elif name == "home":
            steps.append((off, "key", 3))
        elif name == "back":
            steps.append((off, "key", 4))
        elif name in ("lock", "unlock"):
            # SLEEP / WAKEUP are the discrete keys (POWER toggles, 82 is MENU); an unsecured keyguard is then dismissed.
            steps.append((off, "key", 223 if name == "lock" else 224))
            if name == "unlock":
                steps.append((off, "shell", "wm dismiss-keyguard"))
                self.notes.append("unlock wakes the screen and dismisses an unsecured keyguard; a secured one stays")
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

    def try_sh(self, line):
        """Runs a shell line; (ok, output). A nonzero exit or an error message in the output is a failure."""
        p = subprocess.run(self.base + ["shell", line], capture_output=True, timeout=300)
        out = (p.stdout + p.stderr).decode("utf-8", "replace")
        # Diagnostics only: `am start` echoes its intent, so a URL or class containing "error" is not a failure.
        bad = re.search(r"(?im)^(error\b|exception\b|java\.\S+(exception|error)|security\s*exception|.*permission denied"
                        r"|.*: (inaccessible or not found|not found)\s*$|.*unknown command)", out)
        return p.returncode == 0 and not bad, out.strip()


def fail(msg):
    print("replay-adb: " + msg, file=sys.stderr)
    sys.exit(1)


def prepare(adb, plan, z, args, report, warn):
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
        restore_data(adb, plan, z, report, warn, args)
    dev = plan.device
    w, h, dens = dev.get("width"), dev.get("height"), dev.get("density")
    if plan.font:
        ok, out = adb.try_sh("settings put system font_scale %s" % plan.font)
        (report if ok else warn).append("font scale %s %s" % (plan.font, "set" if ok else "NOT set: " + out))
    quals = str(dev.get("qualifiers") or "").replace("+", "-").split("-")
    if "night" in quals or "notnight" in quals:
        ok, out = adb.try_sh("cmd uimode night " + ("yes" if "night" in quals else "no"))
        (report if ok else warn).append("%s mode %s" % ("dark" if "night" in quals else "light", "set" if ok else "NOT set: " + out))
    loc = [q for q in quals if re.fullmatch(r"[a-z]{2,3}", q) and q not in ("ldrtl", "ldltr", "car", "tv", "xr")]
    if loc:
        tag = loc[0] + (("-" + quals[quals.index(loc[0]) + 1][1:]) if len(quals) > quals.index(loc[0]) + 1 and re.fullmatch(r"r[A-Z]{2}", quals[quals.index(loc[0]) + 1]) else "")
        ok, out = adb.try_sh("cmd locale set-app-locales %s --locales %s" % (pkg, tag))
        (report if ok else warn).append("locale %s %s" % (tag, "set" if ok else "NOT set: " + out))
    scale = (1.0, 1.0)
    if w and h and not args.scale_coordinates:
        state["display"] = True
        adb.sh("wm size %dx%d" % (w, h))
        if dens:
            adb.sh("wm density %d" % round(dens * 160))
        report.append("display set to %dx%d at %d dpi (reset at the end)" % (w, h, round((dens or 2) * 160)))
    elif w and h:
        out = adb.sh("wm size")
        # With an override, `wm size` prints the physical size first and the override second: the override is in effect.
        m = re.search(r"Override size: (\d+)x(\d+)", out) or re.search(r"Physical size: (\d+)x(\d+)", out)
        if m:
            scale = (int(m.group(1)) / w, int(m.group(2)) / h)
            report.append("coordinates scaled by %.3f x %.3f to the device's %sx%s" % (scale[0], scale[1], m.group(1), m.group(2)))
    return scale


state = {"display": False}
IDENTITY = {"secure/android_id", "global/device_name", "secure/bluetooth_address", "secure/bluetooth_name",
            "global/device_provisioned", "secure/user_setup_complete"}
APP_DIRS = ("files", "databases", "shared_prefs", "cache", "code_cache", "no_backup")


def data_path(adb, pkg):
    """How the app's data can be written, found without changing anything: 'direct' (adb may write there), 'run-as'
    (a debuggable build), or None."""
    data = "/data/data/" + pkg
    ok, _ = adb.try_sh("mkdir -p %s/.apksim-probe && rm -rf %s/.apksim-probe" % (data, data))
    if ok:
        return "direct"
    ok, _ = adb.try_sh("run-as %s true" % pkg)
    return "run-as" if ok else None


def back_up(adb, pkg, how, args, report):
    """The app's data as it is now, on this machine, before it is replaced."""
    d = args.backup_dir or os.path.join(os.getcwd(), "replay-backup-%s-%s" % (pkg, time.strftime("%Y%m%d-%H%M%S")))
    os.makedirs(d, mode=0o700, exist_ok=True)
    os.chmod(d, 0o700)
    if how == "direct":
        out = adb.run("pull", "/data/data/" + pkg, d, check=False)
        ok = not re.search(r"(?i)error|denied|failed", out)
    else:
        p = subprocess.run(adb.base + ["exec-out", "run-as %s tar -cf - ." % pkg], capture_output=True, timeout=600)
        ok = p.returncode == 0 and len(p.stdout) > 0
        if ok:
            with open(os.path.join(d, "data.tar"), "wb") as f:
                f.write(p.stdout)
    if not ok:
        fail("could not back up the app's current data (pass --no-backup to replace it anyway)")
    report.append("the app's previous data is saved in " + d)


def restore_data(adb, plan, z, report, warn, args):
    pkg = plan.pkg
    zin = zipfile.ZipFile(io.BytesIO(z.read("checkpoint/data.zip")))
    if "package" in zin.namelist() and zin.read("package").decode().strip() != pkg:
        fail("data.zip is for another package")
    # Before anything on the device changes: can the data be written at all, and does the person agree to replace it.
    how = data_path(adb, pkg)
    if how is None:
        fail("the app's data cannot be written: the build must be debuggable (run-as) or adb root")
    if not args.yes:
        fail("replaying replaces %s's data, /sdcard files and Settings values on this device with the checkpoint's; "
             "it is backed up first. Pass --yes to go on" % pkg)
    if not args.no_backup:
        back_up(adb, pkg, how, args, report)
    with tempfile.TemporaryDirectory(prefix="replay-adb-") as stage:
        count = 0
        for n in zin.namelist():
            if n == "package":
                continue
            if ".." in n.split("/") or n.startswith("/") or n.split("/")[0] not in ("slot", "device"):
                continue
            if n.endswith("/"):
                os.makedirs(os.path.join(stage, n), exist_ok=True)  # an empty directory can be a marker
                continue
            dest = os.path.join(stage, n)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(zin.read(n))
            count += 1
        slot, dev = os.path.join(stage, "slot"), os.path.join(stage, "device")
        sp = os.path.join(dev, "settings.properties")
        if os.path.isfile(sp):
            n = bad = skipped_id = 0
            for k, v in parse_properties(open(sp, encoding="utf-8").read()).items():
                deleted = k.startswith("!")
                ns, _, name = k.lstrip("!").partition("/")
                if ns not in ("global", "secure", "system") or not re.fullmatch(r"[A-Za-z0-9_.:-]+", name):
                    continue
                if "%s/%s" % (ns, name) in IDENTITY:
                    skipped_id += 1  # the device's own identity is never overwritten by a recording's
                    continue
                line = ("settings delete %s %s" % (ns, name)) if deleted else \
                    "settings put %s %s %s" % (ns, name, shlex.quote(v))
                ok, out = adb.try_sh(line)
                if ok:
                    n += 1
                else:
                    bad += 1
            report.append("%d Settings values put back" % n + (", %d device-identity ones left alone" % skipped_id if skipped_id else ""))
            if bad:
                warn.append("%d Settings values could not be written (secure/global need the WRITE_SECURE_SETTINGS permission "
                            "or a rooted device)" % bad)
        if os.path.isdir(os.path.join(dev, "sdcard")):
            out = adb.run("push", os.path.join(dev, "sdcard") + "/.", "/sdcard/", check=False)
            if re.search(r"(?i)error|denied|failed", out):
                warn.append("/sdcard was NOT fully put back: " + out.strip().splitlines()[-1])
            else:
                report.append("/sdcard put back")
        data = "/data/data/" + pkg
        # The app's own folders are emptied first, so state the checkpoint does not have (an empty snapshot included) is gone.
        if how == "direct":
            ok, out = adb.try_sh("rm -rf %s && mkdir -p %s" % (" ".join("%s/%s" % (data, d) for d in APP_DIRS), data))
            if not ok:
                fail("could not clear the app's data folders: " + out)
            if not os.path.isdir(slot):
                report.append("the checkpoint's app data is empty: the app's folders were emptied")
                return
            probe = adb.run("push", slot + "/.", data + "/", check=False)
            if re.search(r"(?i)error|denied|failed", probe):
                fail("could not push the app's data: " + probe.strip()[-150:])
            # Pushed as adb's user (root on such a device): give it to the app's uid and relabel it.
            adb.try_sh("u=$(stat -c %%u %s) && chown -R $u:$u %s && restorecon -R %s" % (data, data, data))
            report.append("app data (%d files) pushed straight into %s and handed to the app's uid" % (count, data))
            return
        clear = "; ".join("rm -rf ./%s" % d for d in APP_DIRS)
        copy = clear + ("; cp -R %s/. ." % REMOTE if os.path.isdir(slot) else "")
        try:
            if os.path.isdir(slot):
                adb.sh("rm -rf %s; mkdir -p %s" % (REMOTE, REMOTE))
                adb.run("push", slot + "/.", REMOTE + "/")
                adb.sh("chmod -R a+rX %s" % REMOTE)
            ok, out = adb.try_sh("run-as %s sh -c %s" % (pkg, shlex.quote(copy)))
        finally:
            adb.sh("rm -rf " + REMOTE, check=False)  # also after a failed push or Ctrl-C: it holds private data
        if not ok:
            fail("could not put the app's data back through run-as: " + out)
        report.append("app data (%d files) copied in through run-as" % count)


def play(adb, plan, scale, args, warn):
    t0 = time.monotonic()
    done = 0
    rot = [0]
    for off, kind, a in plan.steps:
        due = t0 + (off / 1000.0) / args.pace
        wait = due - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        line = step_line(kind, a, scale, args.pace)
        if line is None:
            continue
        ok, out = adb.try_sh(line)
        if ok and kind == "rotate" and (a % 2) != (rot[0] % 2):
            scale = (scale[1], scale[0])  # the axes swapped: so do their scale factors
        if ok and kind == "rotate":
            rot[0] = a
        if ok:
            done += 1
        else:
            warn.append("step at %d ms failed (%s): %s" % (off, line.split(";")[0][:60], out[:120]))
    return done


def step_line(kind, a, scale, pace):
    if kind == "tap":
        return "input tap %d %d" % (round(a[0] * scale[0]), round(a[1] * scale[1]))
    if kind == "swipe":
        return "input swipe %d %d %d %d %d" % (a[0] * scale[0], a[1] * scale[1], a[2] * scale[0], a[3] * scale[1],
                                               max(1, int(a[4] / pace)))
    if kind == "key":
        return "input keyevent %d" % a
    if kind == "text":
        t = re.sub(r"[^A-Za-z0-9._,@ -]", "", a).replace(" ", "%s")
        return ("input text " + shlex.quote(t)) if t else None
    if kind == "rotate":
        return "settings put system accelerometer_rotation 0; settings put system user_rotation %d" % a
    return a


def describe(plan, args):
    """The plan as lines, for --dry-run: what would be sent to the device, in order."""
    dev = plan.device
    out = []
    if plan.has_data:
        out.append("restore the checkpoint's app data, /sdcard and Settings values for " + plan.pkg)
    if plan.font:
        out.append("settings put system font_scale %s" % plan.font)
    if dev.get("width") and not args.scale_coordinates:
        out.append("wm size %dx%d; wm density %d" % (dev["width"], dev["height"], round((dev.get("density") or 2) * 160)))
    if plan.stack:
        out.append("am start -n %s" % shlex.quote("%s/%s" % (plan.pkg, plan.stack[-1]["class"])))
    for off, kind, a in plan.steps:
        line = step_line(kind, a, (1.0, 1.0), args.pace)
        out.append("+%6d ms  %s" % (round(off / args.pace), line))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle")
    ap.add_argument("-s", "--serial")
    ap.add_argument("--apk")
    ap.add_argument("--yes", action="store_true", help="agree to replace the app's data, /sdcard and Settings values")
    ap.add_argument("--backup-dir", help="where the app's current data is saved first (default ./replay-backup-PKG-TIME)")
    ap.add_argument("--no-backup", action="store_true", help="replace the data without saving it first")
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
    report, warn, ops = [], [], []
    if not plan.cp:
        report.append("no checkpoint in the bundle: the replay starts from whatever state the app is in")
    if plan.stack:
        if plan.stack[-1]["intent"] != 0 or any(a["state"] > 0 for a in plan.stack):
            plan.notes.append("the intent's extras and the saved instance state are not restored (parcels this script "
                              "does not read); the top activity is started by its class")
    played = 0
    if args.dry_run:
        report.append("dry run: nothing was sent to the device")
        ops = describe(plan, args)
    else:
        adb = Adb(args.serial)
        adb.run("get-state")
        cap = None
        capture_pid = ""
        try:
            scale = prepare(adb, plan, z, args, report, warn)
            if args.logcat:
                adb.run("logcat", "-c", check=False)
            if args.capture:
                # Its own pid is kept, so ending the replay stops this recording and no other.
                cap = subprocess.Popen(adb.base + ["shell", "echo $$; exec screenrecord --time-limit 180 /sdcard/apksim-replay.mp4"],
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
                capture_pid = (cap.stdout.readline() or "").strip()
                time.sleep(1)
            if plan.stack:
                ok, out = adb.try_sh("am start -n %s" % shlex.quote("%s/%s" % (plan.pkg, plan.stack[-1]["class"])))
                if not ok:
                    fail("could not start %s (input was not played, it would reach another app): %s"
                         % (plan.stack[-1]["class"], out.splitlines()[-1] if out else ""))
            else:
                ok, out = adb.try_sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % plan.pkg)
                if not ok or "No activities found" in out:
                    fail("could not launch %s (input was not played): %s" % (plan.pkg, out[-150:]))
            time.sleep(2)
            played = play(adb, plan, scale, args, warn)
        finally:
            if state["display"]:
                adb.sh("wm size reset; wm density reset", check=False)
            if cap is not None:
                time.sleep(1)
                if capture_pid.isdigit():
                    adb.sh("kill -INT " + capture_pid, check=False)
                time.sleep(2)
                if cap.poll() is None:
                    cap.terminate()
                pulled = adb.run("pull", "/sdcard/apksim-replay.mp4", args.capture, check=False)
                adb.sh("rm /sdcard/apksim-replay.mp4", check=False)
                if os.path.isfile(args.capture) and "error" not in pulled.lower():
                    report.append("device screen recorded to " + args.capture)
                else:
                    warn.append("the screen capture could not be saved: " + pulled.strip()[:150])
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
        "failed": warn,
        "notes": report + sorted(set(plan.notes)),
    }
    if args.dry_run:
        result["plan"] = ops
    if args.json:
        print(json.dumps(result, indent=1))
    else:
        print("replay of %s: %d steps%s" % (result["bundle"], result["steps"], "" if args.dry_run else ", %d played" % played))
        for n in result["notes"]:
            print("  " + n)
        for n in warn:
            print("  PROBLEM: " + n)
        if result["skipped"]:
            print("  not replayed: " + "; ".join(result["skipped"]))
        for l in ops:
            print("    " + l)
    if warn:
        sys.exit(1)


if __name__ == "__main__":
    main()
