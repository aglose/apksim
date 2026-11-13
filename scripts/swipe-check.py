#!/usr/bin/env python3
"""A timed swipe flings as a device's `input swipe` does, and a press held in place does not scroll (issue 346).

Usage: swipe-check.py PORT
Drives a running instance of the test app (phone device, Home tab: a list of "Item N" rows 128 px apart) over its
--control port. A 500 px swipe over 300 ms, and one with no duration (300 ms by default), must scroll further than the
drag (a fling); the same swipe over 1000 ms must scroll about the drag distance; `swipe X Y X Y 1500` must not scroll.
The app is restarted before each swipe and left on a fresh Home tab. Exit 0 when it passes, 1 otherwise.
"""
import re
import socket
import sys
import time


def main():
    port = int(sys.argv[1])

    def ctl(cmd, timeout=30.0):
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall((cmd + "\n").encode())
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = s.recv(65536)
                if not chunk:
                    raise RuntimeError(f"connection closed before the reply to {cmd!r}")
                buf += chunk
            return buf.decode(errors="replace").strip()

    def scrolled():
        m = re.search(r'\[\d+,(-?\d+),\d+,\d+\] text="Item (\d+)"', ctl("nodes"))
        if not m:
            raise RuntimeError("no list item on screen")
        return 320 + 128 * (int(m.group(2)) - 1) - int(m.group(1))

    def swipe(args, ms):
        ctl("restart")
        ctl("settle 10000")
        if scrolled() != 0:
            raise RuntimeError("the list did not start at its top")
        if ctl("swipe " + args) != "ok":
            raise RuntimeError("swipe was not answered")
        time.sleep(ms / 1000 + 0.3)
        ctl("settle 15000")
        return scrolled()

    drag = 500 - 16  # the drag less the 8 dp touch slop
    failed = []
    for name, args, ms, ok in (
            ("500 px in 300 ms", "411 1300 411 800 300", 300, lambda d: d > drag + 80),
            ("500 px, no duration", "411 1300 411 800", 300, lambda d: d > drag + 80),
            ("500 px in 1000 ms", "411 1300 411 800 1000", 1000, lambda d: drag - 16 <= d < drag + 150),
            ("a 1500 ms press", "411 900 411 900 1500", 1500, lambda d: d == 0)):
        d = swipe(args, ms)
        print(f"{name}: scrolled {d} px")
        if not ok(d):
            failed.append(name)
    ctl("restart")
    ctl("settle 10000")
    if failed:
        print("swipe-check: FAILED: " + ", ".join(failed), file=sys.stderr)
        return 1
    print("swipe-check: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
