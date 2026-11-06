#!/usr/bin/env python3
"""Checks an instance's adb device the way a tool with an on-device server uses it (the Android CLI's `layout` and
`input`): `adb install` of an instrumentation-only APK, `am instrument -w ... &` until status 200, `am broadcast` with
result data, `adb forward` to the server's local socket, requests that read the screen and tap through UiAutomation,
then finish and uninstall. The server is the test app project's `uiserver` module.

  scripts/adb-check.py SERIAL UISERVER_APK      (SERIAL: localhost:PORT, an instance started with --adb PORT)

Runs against the test app (phone device): it opens the Home tab, reads it with `uiautomator dump` (to /sdcard and to
/dev/tty, full and --compressed), taps the FAB, then presses and holds it with `input swipe`. Exits non-zero with the
reason on
the first failure. adb comes from PATH, or $ADB.
"""
import os
import re
import socket
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

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


def uiautomator_dump(serial):
    """`uiautomator dump` as scripts use it on a device: the stock XML, parsed, against what `apksim nodes` reports."""
    adb(serial, "shell", "input", "tap", "130", "1690")  # the Home tab
    time.sleep(1)
    out = adb(serial, "shell", "uiautomator", "dump").strip()
    if out != "UI hierchary dumped to: /sdcard/window_dump.xml":
        fail(f"uiautomator dump said {out!r}")
    text = adb(serial, "exec-out", "cat", "/sdcard/window_dump.xml")
    if not text.startswith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"):
        fail(f"uiautomator dump's file does not start as the stock tool's: {text[:120]!r}")
    root = ET.fromstring(text)
    nodes = list(root.iter("node"))
    attrs = ["index", "text", "resource-id", "class", "package", "content-desc", "checkable", "checked", "clickable",
             "enabled", "focusable", "focused", "scrollable", "long-clickable", "password", "selected", "bounds"]
    for n in nodes:
        if [k for k in n.attrib if k != "NAF"] != attrs or not re.fullmatch(r"\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]", n.get("bounds")):
            fail(f"a dumped node differs from the stock tool's: {n.attrib}")
    if not any(re.fullmatch(r"FAB clicks: \d+", n.get("text")) for n in nodes):
        fail("uiautomator dump has no 'FAB clicks' text")
    if not any(n.get("resource-id") == "android:id/content" for n in nodes):
        fail("uiautomator dump leaves out views not important for accessibility (android:id/content)")
    # The FAB: Modifier.testTag("fab") under testTagsAsResourceId, its Icon's description "Add".
    fab = [n for n in nodes if n.get("resource-id") == "fab"]
    if not fab or fab[0].get("clickable") != "true":
        fail(f"uiautomator dump has no clickable node with resource-id 'fab': {[n.attrib for n in fab]}")
    if not any(n.get("content-desc") == "Add" for n in fab[0].iter("node")):
        fail("uiautomator dump has no content-desc 'Add' in the FAB")
    # Every element `nodes` lists is in the dump, with the same bounds.
    bounds = {n.get("bounds") for n in nodes}
    listed = re.findall(r"\[(\d+),(\d+),(\d+),(\d+)\]", adb(serial, "shell", "apksim", "nodes"))
    missing = [b for b in listed if "[%s,%s][%s,%s]" % b not in bounds]
    if not listed or missing:
        fail(f"uiautomator dump lacks bounds that `nodes` reports: {missing or 'nodes listed nothing'}")
    # To /dev/tty, as `adb exec-out uiautomator dump /dev/tty` is used: the XML, then the line.
    out = adb(serial, "exec-out", "uiautomator", "dump", "--compressed", "/dev/tty")
    xml, _, tail = out.rpartition("UI hierchary dumped to: ")
    if tail.strip() != "/dev/tty":
        fail(f"uiautomator dump /dev/tty ended {out[-80:]!r}")
    compressed_root = ET.fromstring(xml)
    compressed = list(compressed_root.iter("node"))
    if not compressed or len(compressed) >= len(nodes) or any(n.get("class") == "android.widget.LinearLayout" for n in compressed):
        fail(f"uiautomator dump --compressed kept views not important for accessibility ({len(compressed)} of {len(nodes)})")
    # The provider host (AndroidComposeView, the ComposeView's child) is important on a device, so compressed keeps it
    # and every Compose node under it: the same path from the ComposeView down to the FAB as in the full dump.
    def path_to_fab(tree):
        for cv in tree.iter("node"):
            if cv.get("class") == "androidx.compose.ui.platform.ComposeView":
                def down(n, path):
                    if n.get("resource-id") == "fab":
                        return path
                    for c in n:
                        p = down(c, path + [(c.get("index"), c.get("class"), c.get("bounds"))])
                        if p:
                            return p
                    return None
                return down(cv, [])
        return None
    full_path, compressed_path = path_to_fab(root), path_to_fab(compressed_root)
    if not compressed_path or compressed_path != full_path:
        fail(f"uiautomator dump --compressed dropped the AndroidComposeView or Compose nodes above the FAB:"
             f" {compressed_path} against {full_path}")
    adb(serial, "shell", "rm", "/sdcard/window_dump.xml")
    # A file it cannot write (no such folder) is an error the shell goes on from, so a `||` fallback runs.
    out = adb(serial, "shell", "uiautomator dump /sdcard/missing/dump.xml || echo fallback").strip()
    if not out.startswith("ERROR: could not write /sdcard/missing/dump.xml") or not out.endswith("fallback"):
        fail(f"uiautomator dump to a missing folder said {out!r}")


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    serial, apk = sys.argv[1], sys.argv[2]
    subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
    adb(serial, "wait-for-device")
    uiautomator_dump(serial)

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
        # A press and hold, as `input swipe X Y X Y MS` holds on a device: it returns once the finger has lifted after
        # MS, so the FAB's click (on release) shows straight away. It used to return at once and lift much later.
        want = f"FAB clicks: {before[0] + 2}"
        t0 = time.time()
        adb(serial, "shell", "input", "swipe", "733", "1533", "733", "1533", "1500")
        took = time.time() - t0
        if took < 1.5:
            fail(f"input swipe with a 1500 ms hold returned after {took:.2f} s")
        deadline = time.time() + 5
        while time.time() < deadline:
            texts = request(port, "texts")
            if want in texts:
                break
            time.sleep(0.1)
        else:
            fail(f"the hold had not lifted 5 s after input swipe returned (wanted {want!r}): {texts}")
        if request(port, "finish") != "ok":
            fail("finish was not answered")
    finally:
        adb(serial, "forward", "--remove", f"tcp:{port}", check=False)

    time.sleep(1)
    if "Success" not in adb(serial, "uninstall", PKG, check=False):
        fail("adb uninstall failed")
    print("adb check passed: uiautomator dump, install, am instrument, am broadcast, forward to a local socket,"
          " UiAutomation read and tap, input swipe hold")


if __name__ == "__main__":
    main()
