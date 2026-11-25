#!/usr/bin/env python3
"""Device sessions of `apksim beam` (issue 626, docs/beam.md "Device sessions"): a real device attached over adb as a
session's screen and input, driven as a page drives the agent (each request with its page's Origin).

    scripts/beam-device-session-check.py APK                    # the stand-in part only (no device needed)
    scripts/beam-device-session-check.py APK --serial SERIAL    # and the device part, on that device

The stand-in part puts a fake `adb` first on the agent's PATH, which lists one unauthorized device: hello's
`devices` follows `apksim beam config devices on|off`, GET /beam/devices is refused while it is off and to a site the
Mac does not trust, lists the device with its state, and a start on it is refused naming the prompt on the device; a
start with launch options is refused naming them.

The device part needs scrcpy (`brew install scrcpy`) and an authorized device. It installs the APK (the test app) and
leaves the device as it found it: the check's server, forward and jar gone, the rotation settings as they were, and
the app uninstalled again if it was not there before (`--keep-app` keeps it). It checks: the device listed with its
model, SDK and size; a start installs and the first frame is an H.264 keyframe with its SPS at the device's size; the
`i` message's target; touch, typing, back, home and rotate answered (a rotation to landscape seen in the frames'
size); a simulator-only word refused; a keyframe request answered with one; a second start on the device refused while
the first runs; and, with apksigner and keytool, a build signed by another key answered `need: "uninstall"` and
installed with `"uninstall": true`. Last, `adb reconnect` drops the device: the session ends with a reason, the agent
stays up and the next start works.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("beamcheck", os.path.join(HERE, "beam-check.py"))
bc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bc)
check, req, sc = bc.check, bc.req, bc.sc
A = bc.A
PKG = bc.PKG


def hello(gp, origin=A):
    st, _, body = req(gp, "GET", "/beam/hello", origin)
    return json.loads(body) if st == 200 else {}


def start_body(apk, sha, **extra):
    b = {"sha256": sha, "name": os.path.basename(apk), "size": len(open(apk, "rb").read()), "protocol": 3, "options": []}
    b.update(extra)
    return b


def standin(apksim, apk):
    print("== stand-in adb: opt-in, listing, refusals")
    fake = tempfile.mkdtemp(prefix="fake-adb-")
    with open(os.path.join(fake, "adb"), "w") as f:
        f.write("#!/bin/sh\n"
                "case \"$*\" in\n"
                "  'devices -l') printf 'List of devices attached\\nFAKE0626 unauthorized usb:1-1 transport_id:9\\n' ;;\n"
                "  *) echo \"fake adb: $*\" >&2; exit 1 ;;\n"
                "esac\n")
    os.chmod(os.path.join(fake, "adb"), 0o755)
    saved = dict(bc.ENV)
    bc.ENV["PATH"] = fake + ":" + os.environ.get("PATH", "")
    gp = bc.free_port()
    g = bc.agent(apksim, gp, "deny")
    try:
        bc.trust(apksim, "allow", A)
        check(hello(gp).get("devices") == "off", "hello says devices off by default")
        st, _, body = req(gp, "GET", "/beam/devices", A)
        check(st == 403 and b"devices on" in body, f"GET /beam/devices is refused while devices are off ({st} {body[:80]})")
        sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
        st, _, body = req(gp, "POST", "/beam/start", A, start_body(apk, sha, device="FAKE0626"))
        check(st == 403 and b"devices on" in body, f"a device start is refused while devices are off ({st})")
        bc.beam(apksim, "config", "devices", "on")
        check(hello(gp).get("devices") == "on", "hello says devices on after `apksim beam config devices on`")
        st, _, body = req(gp, "GET", "/beam/devices", bc.B)
        check(st == 403 and b"trust" in body, f"an untrusted site may not list the devices ({st})")
        st, _, body = req(gp, "GET", "/beam/devices", A)
        devs = json.loads(body).get("devices", []) if st == 200 else []
        check(st == 200 and devs == [{"serial": "FAKE0626", "state": "unauthorized", "model": "", "sdk": 0, "width": 0, "height": 0,
                                      "transport": "usb"}], f"the device is listed as unauthorized ({st} {devs})")
        st, _, body = req(gp, "POST", "/beam/start", A, start_body(apk, sha, device="FAKE0626"))
        check(st == 409 and b"Allow USB debugging" in body, f"a start on it is refused naming the prompt on the device ({st} {body[:120]})")
        st, _, body = req(gp, "POST", "/beam/start", A, start_body(apk, sha, device="FAKE0626", options=["--anim-scale", "0"]))
        check(st == 400 and b"--anim-scale" in body, f"a device start with launch options is refused naming them ({st} {body[:120]})")
        st, _, body = req(gp, "POST", "/beam/start", A, start_body(apk, sha, device="FAKE0626", inspect=True))
        check(st == 400 and b"inspector" in body, f"a device start asking for the inspector is refused ({st})")
        st, _, body = req(gp, "POST", "/beam/start", A, start_body(apk, sha, device="no such; serial"))
        check(st == 400, f"a malformed serial is refused ({st})")
        bc.beam(apksim, "config", "devices", "off")
        check(hello(gp).get("devices") == "off", "and off again")
    finally:
        g.terminate()
        g.wait(15)
        bc.ENV.clear()
        bc.ENV.update(saved)
        shutil.rmtree(fake, ignore_errors=True)


def adb(serial, *args, timeout=60):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)


def shell(serial, cmd):
    return adb(serial, "shell", cmd).stdout.strip()


def device_state(serial):
    """What this check must leave as it found it."""
    return {
        "rotation": (shell(serial, "settings get system accelerometer_rotation"), shell(serial, "settings get system user_rotation")),
        "servers": shell(serial, "ps -A -o ARGS | grep -c '[s]crcpy'"),
        "jars": shell(serial, "ls /data/local/tmp | grep -c '^apksim-scrcpy' || true"),
        "forwards": [l for l in subprocess.run(["adb", "forward", "--list"], capture_output=True, text=True).stdout.splitlines()
                     if l.startswith(serial) and "scrcpy" in l],
    }


def open_device_session(gp, grant):
    """The session for a grant with H.264 asked for, pumped to its first frame."""
    w = sc.WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant.get('app')}&token={grant.get('token')}&codec=h264", {"Origin": A})
    s = sc.Session.__new__(sc.Session)
    s.ws, s.frames, s.codec, s.acking, s.offset, s.best_rtt = w, [], "jpeg", True, 0.0, float("inf")
    s.texts, s.stats, s.ack_delay, s.acks = [], {}, 0, []
    if w.status == 101:
        s.pump(90, until=lambda: len(s.frames) > 0)
    return s


def no_sessions(gp):
    """Waits until the agent has dropped every session (a tab closed: its bridge ends, then the agent lets it go)."""
    def empty():
        st, _, body = req(gp, "GET", "/beam/status")
        return st == 200 and json.loads(body).get("sessions") == []
    return bc.until(empty, 30)


def locked(serial):
    return "isKeyguardShowing=true" in shell(serial, "dumpsys window | grep isKeyguardShowing")


def resigned(apk):
    """The APK signed with a key of this check's own (apksigner and keytool from the Android SDK), or None."""
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted((os.path.join(sdk, "build-tools", d) for d in os.listdir(os.path.join(sdk, "build-tools"))), reverse=True) \
        if os.path.isdir(os.path.join(sdk, "build-tools")) else []
    signer = next((os.path.join(t, "apksigner") for t in tools if os.path.exists(os.path.join(t, "apksigner"))), None)
    if not signer or not shutil.which("keytool"):
        return None
    d = tempfile.mkdtemp(prefix="device-check-key-")
    ks = os.path.join(d, "k.jks")
    subprocess.run(["keytool", "-genkeypair", "-keystore", ks, "-storepass", "devcheck", "-keypass", "devcheck", "-alias", "k",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=device-check"], capture_output=True, check=True)
    out = os.path.join(d, "other.apk")
    subprocess.run([signer, "sign", "--ks", ks, "--ks-pass", "pass:devcheck", "--out", out, apk], capture_output=True, check=True)
    return out


def on_device(apksim, apk, serial, keep_app):
    print(f"== device {serial}")
    had_app = shell(serial, f"pm path {PKG}").startswith("package:")
    before = device_state(serial)
    gp = bc.free_port()
    g = bc.agent(apksim, gp, "deny")
    try:
        bc.trust(apksim, "allow", A)
        bc.beam(apksim, "config", "devices", "on")
        st, _, body = req(gp, "GET", "/beam/devices", A)
        devs = {d["serial"]: d for d in json.loads(body).get("devices", [])} if st == 200 else {}
        d = devs.get(serial, {})
        check(d.get("state") == "device" and d.get("model") and d.get("sdk", 0) > 0 and d.get("width", 0) > 0,
              f"the device is listed with its model, SDK and size ({d})")
        sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
        t0 = time.time()
        grant, sent = bc.begin(gp, A, start_body(apk, sha, device=serial), open(apk, "rb").read())
        check(grant.get("token") and grant.get("device") == serial, f"a start on it installs the build and grants a session ({grant}, uploaded: {sent})")
        check(shell(serial, f"pm path {PKG}").startswith("package:"), "the build is installed on the device")
        s = open_device_session(gp, grant)
        info = bc.session_info(s)
        first = s.frames[0] if s.frames else None
        print(f"     first frame after {time.time() - t0:.1f} s (start to frame, install included)")
        check(s.codec == "h264", f"the stream is H.264 ({s.codec})")
        check(first is not None and first[7] and first[8].startswith(b"\0\0\0\1\x67"), "the first frame is a keyframe with its SPS in front")
        check(first is not None and (first[4], first[5]) == (d.get("width"), d.get("height")), f"at the device's size ({first[4:6] if first else None})")
        check(info.get("target") == "device" and info.get("targetDevice", {}).get("serial") == serial and info.get("rec") is False
              and info.get("inspect") is False and "keep" not in info, f"the i message names the device ({info})")
        check(info.get("device") == f"{d['width']}x{d['height']}" and info.get("targetDevice", {}).get("width") == d["width"]
              and info.get("targetDevice", {}).get("height") == d["height"], f"the i message gives the device's size, not a preset ({info.get('device')})")
        s.pump(3)
        n = len(s.frames)
        w, h = d["width"], d["height"]
        for c in (f"touch down {w // 2} {h * 3 // 4}", f"touch move {w // 2} {h // 2}", f"touch move {w // 2} {h // 4}", f"touch up {w // 2} {h // 4}"):
            s.ws.send(c)
        s.pump(2)
        if locked(serial):
            print(f"     (the device is locked: the swipe went to its lock screen, {len(s.frames) - n} new frames; unlock it to check input)")
        else:
            check(len(s.frames) > n, f"a swipe changes the screen ({len(s.frames) - n} frames)")
        s.ws.send("appearance dark")
        s.pump(1, until=lambda: any(t.startswith("r refused: not on a device session") for t in s.texts))
        check(any(t.startswith("r refused: not on a device session") for t in s.texts), "a simulator-only word is refused")
        keys = sum(1 for f in s.frames if f[7])
        s.ws.send("key")
        s.pump(3, until=lambda: sum(1 for f in s.frames if f[7]) > keys)
        check(sum(1 for f in s.frames if f[7]) > keys, "a keyframe request is answered with a keyframe")
        if locked(serial):
            # The lock screen is portrait-only and the app is behind it: nothing to turn until someone unlocks the device.
            print("     (the device is locked: unlock it to check rotation; skipped)")
        else:
            s.ws.send("rotate landscape")
            s.pump(5, until=lambda: s.frames and s.frames[-1][4] > s.frames[-1][5])
            check(s.frames and s.frames[-1][4] > s.frames[-1][5], f"rotate landscape turns the screen ({s.frames[-1][4:6] if s.frames else None})")
            s.ws.send("rotate portrait")
            s.pump(5, until=lambda: s.frames and s.frames[-1][4] < s.frames[-1][5])
            check(s.frames and s.frames[-1][4] < s.frames[-1][5], "and back to portrait")
        for c in ("type hello", "key ENTER", "back", "home", "resume"):
            s.ws.send(c)
            s.pump(0.5)
        check(not any(t.startswith("r ") and "error" in t for t in s.texts), f"typing, back, home and resume are taken ({[t for t in s.texts if t.startswith('r ')]})")
        # The bridge's control port answers only the session's token: not a page posting to loopback, not a stray program.
        pid = s.stats.get("pid", 0) if s.stats else 0
        if not pid:
            s.ws.send("stats")
            s.pump(3, until=lambda: s.stats.get("pid", 0) > 0)
            pid = s.stats.get("pid", 0)
        ports = subprocess.run(["lsof", "-nP", "-a", "-p", str(pid), "-iTCP", "-sTCP:LISTEN", "-Fn"], capture_output=True, text=True).stdout
        bport = next((int(l.rsplit(":", 1)[1]) for l in ports.splitlines() if l.startswith("n") and ":" in l), 0)
        answers = []
        for probe in (b"stats\n", b"POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 6\r\n\r\nstats\n", b"auth wrong-token-0123456789abcdef0123456789\nstats\n"):
            try:
                c = socket.create_connection(("127.0.0.1", bport), timeout=5)
                c.sendall(probe)
                c.settimeout(3)
                answers.append(c.recv(100))
                c.close()
            except OSError as e:
                answers.append(repr(e).encode())
        check(bport and all(x == b"" or b"ConnectionReset" in x for x in answers), f"the bridge's port ({bport}) answers nothing without the session's token ({answers})")
        for sid, line, want in (("s1", "settings put global http_proxy 10.0.0.1:8080", 126), ("s2", "settings get system font_scale", 0),
                                ("s3", "am broadcast -a dev.apksim.check.NOTHING", 126), ("s4", "ls /sdcard", 126)):
            s.ws.send(f"shell {sid} {line}")
            s.pump(15, until=lambda: any(t.startswith("a ") and f'"{sid}"' in t for t in s.texts))
            got = next((json.loads(t[2:]) for t in s.texts if t.startswith("a ") and f'"{sid}"' in t), {})
            check(got.get("status") == want, f"limited shell on the device: `{line}` -> {want} ({got})")
        st, _, body = req(gp, "POST", "/beam/start", A, start_body(apk, sha, device=serial))
        check(st == 409 and b"already runs a session" in body, f"a second start on the device is refused while one runs ({st})")
        s.ws.s.close()
        check(no_sessions(gp) and bc.until(lambda: device_state(serial)["servers"] == "0", 20), "closing the tab ends the session and the device's server")

        other = resigned(apk)
        if other:
            osha = hashlib.sha256(open(other, "rb").read()).hexdigest()
            g1, _ = bc.begin(gp, A, start_body(other, osha, device=serial, uninstall=True), open(other, "rb").read())
            check(g1.get("need") == "uninstall" and shell(serial, f"pm path {PKG}").startswith("package:"),
                  f"\"uninstall\": true before any need: uninstall answer uninstalls nothing ({g1})")
            g2, _ = bc.begin(gp, A, start_body(other, osha, device=serial), open(other, "rb").read())
            check(g2.get("need") == "uninstall" and g2.get("package") == PKG and g2.get("why"),
                  f"a build signed by another key answers need: uninstall ({g2})")
            g3, _ = bc.begin(gp, A, start_body(other, osha, device=serial, uninstall=True), open(other, "rb").read())
            check(g3.get("token"), f"and with \"uninstall\": true it is installed ({g3})")
            s3 = open_device_session(gp, g3)
            check(len(s3.frames) > 0, "and its session streams")
            s3.ws.s.close()
            no_sessions(gp)
            # The original build back, so the device holds what it held (or nothing, below).
            g4, _ = bc.begin(gp, A, start_body(apk, sha, device=serial), open(apk, "rb").read())
            if g4.get("need") == "uninstall":
                g4, _ = bc.begin(gp, A, start_body(apk, sha, device=serial, uninstall=True), open(apk, "rb").read())
            check(g4.get("token"), f"the first build goes back the same way ({g4})")
            shutil.rmtree(os.path.dirname(other), ignore_errors=True)
        else:
            print("     (no apksigner or keytool: the need-uninstall check is skipped)")

        g5, _ = bc.begin(gp, A, start_body(apk, sha, device=serial), open(apk, "rb").read())
        s5 = open_device_session(gp, g5)
        check(len(s5.frames) > 0, f"another session starts ({g5})")
        adb(serial, "reconnect")
        try:
            s5.pump(30, until=lambda: any(t.startswith("x ") for t in s5.texts))
        except EOFError:
            pass  # the agent closed the socket after its last word
        why = next((t[2:] for t in s5.texts if t.startswith("x ")), None)
        check(why is not None and ("device" in why or "server" in why), f"dropping the device ends the session with a reason ({why})")
        subprocess.run(["adb", "-s", serial, "wait-for-device"], timeout=60)
        bc.until(lambda: shell(serial, "getprop sys.boot_completed") == "1", 30)
        check(hello(gp).get("apksim"), "the agent stays up")
        g6, _ = bc.begin(gp, A, start_body(apk, sha, device=serial), open(apk, "rb").read())
        s6 = open_device_session(gp, g6)
        check(len(s6.frames) > 0, "and the next start works")
        s6.ws.s.close()
        no_sessions(gp)
    finally:
        g.terminate()
        g.wait(15)
        time.sleep(1)
        after = device_state(serial)
        check(after == before, f"the device is left as it was found ({before} -> {after})")
        if not had_app and not keep_app:
            adb(serial, "uninstall", PKG)
            print(f"     uninstalled {PKG} (it was not on the device before)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--apksim", default=os.path.join(os.path.dirname(HERE), "apksim"))
    ap.add_argument("--serial", help="an authorized device for the device part (else the stand-in part alone)")
    ap.add_argument("--keep-app", action="store_true", help="leave the test app installed on the device")
    a = ap.parse_args()
    try:
        standin(a.apksim, a.apk)
        if a.serial:
            on_device(a.apksim, a.apk, a.serial, a.keep_app)
    finally:
        shutil.rmtree(bc.STATE, ignore_errors=True)
    print(f"FAIL: {len(sc.FAILED)} check(s)" if sc.FAILED else "OK")
    sys.exit(1 if sc.FAILED else 0)


if __name__ == "__main__":
    main()
