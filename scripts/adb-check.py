#!/usr/bin/env python3
"""Checks an instance's adb device the way a tool with an on-device server uses it (the Android CLI's `layout` and
`input`): `adb install` of an instrumentation-only APK, `am instrument -w ... &` until status 200, `am broadcast` with
result data, `adb forward` to the server's local socket, requests that read the screen and tap through UiAutomation,
then finish and uninstall. The server is the test app project's `uiserver` module.

  scripts/adb-check.py SERIAL UISERVER_APK      (SERIAL: localhost:PORT, an instance started with --adb PORT)

Runs against the test app (phone device): it opens the Home tab and taps the FAB. Exits non-zero with the reason on
the first failure. adb comes from PATH, or $ADB.
"""
import os
import re
import socket
import subprocess
import sys
import threading
import time

PKG = "dev.apksim.uiserver"
SOCKET = "dev.apksim.uiserver"


ADB = os.environ.get("ADB", "adb")


def adb(serial, *args, check=True):
    r = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=120)
    out = r.stdout + r.stderr
    if check and r.returncode != 0:
        fail(f"adb {' '.join(args)} exited {r.returncode}: {out.strip()}")
    return out


def fail(why):
    print(f"adb check FAILED: {why}", file=sys.stderr)
    sys.exit(1)


def request(port, line):
    with socket.create_connection(("127.0.0.1", port), timeout=30) as s:
        s.sendall((line + "\n").encode())
        f = s.makefile("r", encoding="utf-8")
        if line == "texts":
            lines = []
            for l in f:
                if l == "\n":
                    break
                lines.append(l.rstrip("\n"))
            return lines
        return f.readline().strip()


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    serial, apk = sys.argv[1], sys.argv[2]
    subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
    adb(serial, "wait-for-device")

    out = adb(serial, "install", "-t", "-g", apk)
    if "Success" not in out:
        fail(f"adb install: {out.strip()}")
    if f"instrumentation:{PKG}/{PKG}.UiServer" not in adb(serial, "shell", "pm", "list", "instrumentation"):
        fail("pm list instrumentation does not list the server")

    # Started as the Android CLI starts its server: in the background, read until it reports it is listening.
    proc = subprocess.Popen([ADB, "-s", serial, "shell", f"am instrument -w {PKG}/.UiServer &"],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    # readline() blocks, so a server that never reports (or never prints a whole line) is ended by a timer.
    timer = threading.Timer(60, proc.kill)
    timer.start()
    seen = ""
    while True:
        line = proc.stdout.readline()
        if not line:
            break
        seen += line
        if "INSTRUMENTATION_STATUS_CODE: 200" in line:
            break
    timer.cancel()
    # The client goes away, as the CLI's does (the server keeps running); reaped either way.
    proc.kill()
    proc.wait(timeout=10)
    proc.stdout.close()
    if "INSTRUMENTATION_STATUS_CODE: 200" not in seen:
        fail(f"am instrument never reported status 200: {seen.strip()}")

    info = adb(serial, "shell", "am", "broadcast", "-a", f"{PKG}.GET_INFO")
    if f'data="version=uiserver-1 socket={SOCKET}"' not in info:
        fail(f"am broadcast did not return the server's info: {info.strip()}")

    port = int(adb(serial, "forward", "tcp:0", f"localabstract:{SOCKET}").strip())
    try:
        adb(serial, "shell", "input", "tap", "130", "1690")  # the Home tab
        time.sleep(1)
        texts = request(port, "texts")
        before = [int(m.group(1)) for t in texts if (m := re.fullmatch(r"FAB clicks: (\d+)", t))]
        if not before:
            fail(f"the server did not read the app's screen: {texts}")
        want = f"FAB clicks: {before[0] + 1}"
        if request(port, "tap 733 1533") != "ok":  # the FAB (phone device, see AGENTS.md)
            fail("tap was not answered")
        deadline = time.time() + 10
        while time.time() < deadline:
            texts = request(port, "texts")
            if want in texts:
                break
            time.sleep(0.3)
        else:
            fail(f"the tap through UiAutomation did not reach the app (wanted {want!r}): {texts}")
        # A press and hold, as `adb shell input swipe X Y X Y MS` gives one: as on a device the command returns only
        # once the finger has lifted, so the click has happened by then (it used to return at once, mid-hold).
        held = int(want.split(": ")[1])
        start = time.time()
        adb(serial, "shell", "input", "swipe", "733", "1533", "733", "1533", "1500")
        took = time.time() - start
        if took < 1.4:
            fail(f"input swipe with a 1500 ms hold returned after {took:.2f} s, before the finger lifted")
        deadline = time.time() + 3  # the click is in; its recomposition waits for the next frame (slow under Rosetta)
        while f"FAB clicks: {held + 1}" not in (texts := request(port, "texts")) and time.time() < deadline:
            time.sleep(0.05)
        if f"FAB clicks: {held + 1}" not in texts:
            fail(f"the hold had not released when input swipe returned (wanted FAB clicks: {held + 1}): {texts}")
        if request(port, "finish") != "ok":
            fail("finish was not answered")
    finally:
        adb(serial, "forward", "--remove", f"tcp:{port}", check=False)

    time.sleep(1)
    if "Success" not in adb(serial, "uninstall", PKG, check=False):
        fail("adb uninstall failed")
    print("adb check passed: install, am instrument, am broadcast, forward to a local socket, UiAutomation read and tap, input swipe hold")


if __name__ == "__main__":
    main()
