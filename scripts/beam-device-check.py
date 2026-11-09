#!/usr/bin/env python3
"""`apksim beam to-device` against a stand-in `adb` (issue 191), no device needed:

    scripts/beam-device-check.py [APK]   (default: the test app's debug APK)

A temporary state folder holds one saved session (data slot, Settings file, launch options, build); a fake `adb` on PATH
logs every call and answers like a debuggable device. Checks: --dry-run changes nothing; no --yes with no terminal refuses;
--data backs the device's data up before clearing it, then writes the slot's (its .lock left out); --settings records old
values, quotes awkward ones, leaves identity keys out and writes a restore script; a differing build whose signer cannot
be read stops with nothing changed; two devices need -s; a build that is not debuggable (run-as refused) stops before
anything is cleared; the session's activity is started.
"""
import os
import shutil
import stat
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OK = True


def check(c, what):
    global OK
    OK &= bool(c)
    print(("ok   " if c else "FAIL ") + what, flush=True)


FAKE = r'''#!/usr/bin/env python3
import os, sys, json
d = os.environ["FAKE_ADB"]
a = sys.argv[1:]
open(d + "/log", "a").write(json.dumps(a) + "\n")
serial = None
if a[:1] == ["-s"]:
    serial, a = a[1], a[2:]
if a[0] == "devices":
    print("List of devices attached")
    for s in os.environ.get("FAKE_DEVICES", "SER1").split(","):
        print(s + "\tdevice")
elif a[0] == "pull":
    open(a[2], "wb").write(open(os.environ["FAKE_DEVICE_APK"], "rb").read())
elif a[0] == "install":
    print("Success")
elif a[0] == "exec-out":
    sys.stdout.buffer.write(b"T" * 2048)
elif a[0] == "shell":
    s = a[1]
    if s.startswith("pm path"):
        print("package:/data/app/x/base.apk")
    elif s.startswith("run-as") and s.endswith(" true"):
        sys.exit(0 if not os.environ.get("FAKE_NO_RUNAS") else 1)
    elif "tar -xf -" in s:
        open(d + "/pushed.tar", "wb").write(sys.stdin.buffer.read())
    elif s.startswith("settings get"):
        print({"global/foo": "7", "secure/token": "null"}.get(s.split()[2] + "/" + s.split()[3], "null"))
    elif s.startswith("am start"):
        print("Starting: Intent")
'''


def main():
    apk = next((x for x in sys.argv[1:] if not x.startswith("-")), None)
    if not apk:
        for c in (os.path.join(ROOT, "testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk"),
                  "/Users/aglose/apksim/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk"):
            if os.path.exists(c):
                apk = c
                break
    if not apk:
        sys.exit("no APK: pass one")
    import hashlib
    sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    tmp = tempfile.mkdtemp(prefix="beam-device-")
    home, fake = tmp + "/home", tmp + "/fake"
    os.makedirs(fake + "/bin")
    os.makedirs(home + "/beam/saved")
    os.makedirs(home + "/beam/uploads")
    shutil.copy(apk, home + "/beam/uploads/" + sha + ".apk")
    pkg, sid = "dev.example.app", "0123456789ab"
    slot = home + "/cache/data/" + pkg + "/k" + sid + "-0"
    os.makedirs(slot + "/shared_prefs")
    open(slot + "/shared_prefs/a.xml", "w").write("<map/>")
    open(slot + "/.lock", "w").write("")
    os.makedirs(slot + ".device")
    open(slot + ".device/settings.properties", "w").write(
        "global/foo=it's \\$(bad) value\nsecure/android_id=deadbeef\nsecure/token=abc\n")
    open(home + "/beam/saved/" + sid + ".json", "w").write(
        '{"id":"%s","app":"app","sha256":"%s","package":"%s","device":"phone","appearance":"dark","fontscale":"1.3",'
        '"launch":["--activity",".Main"],"saved":1,"lastUsed":1}' % (sid, sha, pkg))
    adb = fake + "/bin/adb"
    open(adb, "w").write(FAKE)
    os.chmod(adb, 0o755)
    env = dict(os.environ, APKSIM_HOME=home, FAKE_ADB=fake, FAKE_DEVICE_APK=apk, PATH=fake + "/bin:" + os.environ["PATH"])

    def run(*args, **e):
        open(fake + "/log", "w").close()
        r = subprocess.run([ROOT + "/apksim", "beam", "to-device", sid, *args], env=dict(env, **e), capture_output=True,
                           text=True, stdin=subprocess.DEVNULL, timeout=120)
        return r, [l for l in open(fake + "/log").read().splitlines()]

    r, log = run("--dry-run", "--data", "--settings", "all")
    check(r.returncode == 0 and "dry run" in r.stdout, "dry run succeeds: " + (r.stdout + r.stderr)[-200:])
    check(not any('"install"' in l or "settings put" in l or "exec-out" in l for l in log), "dry run changes nothing on the device")
    check("secure/android_id: it identifies the device" in r.stdout, "identity setting left out")

    r, log = run("--data")
    check(r.returncode == 1 and "--yes" in r.stderr, "no terminal and no --yes: refuses")
    check(not any("exec-out" in l for l in log), "refusal touched nothing")

    r, log = run("--data", "--settings", "all", "--yes")
    text = " ".join(log)
    check(r.returncode == 0, "--data --settings --yes succeeds: " + (r.stdout + r.stderr)[-300:])
    iback = next((i for i, l in enumerate(log) if "exec-out" in l), -1)
    istop = next((i for i, l in enumerate(log) if "force-stop" in l), -1)
    check(0 <= istop < iback, "app stopped before the backup is read")
    iclear = next((i for i, l in enumerate(log) if "rm -rf" in l), -1)
    check(0 <= iback < iclear, "device data backed up before it is cleared")
    check(os.path.getsize(fake + "/pushed.tar") > 0, "slot written to the device")
    t = subprocess.run(["tar", "-tf", fake + "/pushed.tar"], capture_output=True, text=True).stdout
    check("shared_prefs/a.xml" in t and ".lock" not in t, "tar holds the slot's files, not its .lock")
    check(any("settings put global foo" in l and "'\\\\''" in l for l in log), "awkward value quoted")
    check(not any("android_id" in l and "put" in l for l in log), "android_id never put")
    check(any("settings put system font_scale 1.3" in l for l in log) and any("night yes" in l for l in log),
          "appearance and font scale carried")
    check(any("am start -n" in l and "dev.example.app/.Main" in l for l in log), "session's activity started")
    backups = home + "/beam/device-backup"
    names = os.listdir(backups) if os.path.isdir(backups) else []
    check(any(n.endswith(".tar") for n in names) and any(n.endswith("-undo.sh") for n in names), "backup and restore script kept")
    sh = [n for n in names if n.endswith("-undo.sh")]
    body = open(backups + "/" + sh[0]).read() if sh else ""
    check("settings put global foo" in body and "7" in body and "settings delete secure token" in body, "restore script has the old values")

    r, log = run("--yes")
    check(r.returncode == 0 and any("night yes" in l for l in log), "launch options alone run with --yes")
    r, log = run()
    check(r.returncode == 1 and "--yes" in r.stderr and not any("night" in l and "yes" in l for l in log),
          "launch options alone need --yes too")
    r, log = run("--data", "--yes")
    check(len([n for n in os.listdir(backups) if n.endswith(".tar")]) >= 2, "each handoff has its own backup file")
    r, log = run("--settings", "secure/android_id", "--dry-run")
    check("refused secure/android_id" in r.stdout, "naming an identity key is refused")

    r, log = run("--dry-run", FAKE_DEVICES="A,B")
    check(r.returncode == 1 and "-s SERIAL" in r.stderr, "two devices: asks for -s")
    r, log = run("--dry-run", "-s", "B", FAKE_DEVICES="A,B")
    check(r.returncode == 0, "-s picks one")

    r, log = run("--data", "--yes", FAKE_NO_RUNAS="1")
    check(r.returncode == 1 and "debuggable" in r.stderr and not any("rm -rf" in l for l in log), "not debuggable: stops before clearing")

    other = tmp + "/other.apk"
    open(other, "wb").write(b"PK\x05\x06" + b"\0" * 18)
    r, log = run("--install", "replace", "--yes", FAKE_DEVICE_APK=other)
    check(r.returncode == 1 and "who signed" in r.stderr and not any('"install"' in l for l in log), "unreadable signer: stops, no install")

    if "-v" in sys.argv: print(body)
    shutil.rmtree(tmp)
    print("PASS" if OK else "FAIL")
    sys.exit(0 if OK else 1)


main()
