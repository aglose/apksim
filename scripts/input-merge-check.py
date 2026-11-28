#!/usr/bin/env python3
"""Queued touch moves and wheel events are merged at dispatch; everything else keeps its order (issue 450).

Usage: input-merge-check.py PORT
Drives a running instance of the test app (phone device) over its --control port. A touch down, 200 touch moves and
a touch up sent as one burst must merge where they queued up (`stats` inputMerged), the up must arrive (the Home list has scrolled
and the screen settles), and a burst of wheel events must merge too. The app is restarted first and left on a fresh
Home tab. Exit 0 when it passes, 1 otherwise.
"""
import re
import socket
import sys


def main():
    port = int(sys.argv[1])

    def burst(cmds, timeout=60.0):
        """Every command in one write, as a backlogged client's; the replies, one per command."""
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall(("\n".join(cmds) + "\n").encode())
            buf = b""
            while buf.count(b"\n") < len(cmds):
                chunk = s.recv(65536)
                if not chunk:
                    raise RuntimeError("connection closed before every reply")
                buf += chunk
            return buf.decode(errors="replace").splitlines()

    def ctl(cmd):
        return burst([cmd])[0]

    def merged():
        return int(re.search(r"inputMerged=(\d+)", ctl("stats")).group(1))

    def first_item():
        """(number, -top) of the first list row on screen: grows as the list scrolls, even while that row is still
        partly on screen (the test app's header is taller than a short drag)."""
        m = re.search(r'\[\d+,(-?\d+),\d+,\d+\] text="Item (\d+)"', ctl("nodes"))
        return (int(m.group(2)), -int(m.group(1))) if m else (-1, 0)

    failed = []

    def check(ok, what):
        print(("ok    " if ok else "FAIL  ") + what)
        if not ok:
            failed.append(what)

    ctl("restart")
    ctl("settle 10000")
    top = first_item()
    before = merged()
    moves = [f"touch move 400 {1400 - 4 * i}" for i in range(1, 201)]
    replies = burst(["touch down 400 1400"] + moves + ["touch up 400 600"])
    check(all(r == "ok" for r in replies), "every touch command answered ok")
    settled = ctl("settle 10000")
    check(settled.startswith("settled"), f"the screen settles after the burst ({settled})")
    n = merged() - before
    check(10 <= n <= 199, f"queued moves merged ({n} of 200)")
    now = first_item()
    check(now > top, f"the list scrolled, so the moves and the up arrived (Item {top[0]} at y {-top[1]} -> Item {now[0]} at y {-now[1]})")

    before = merged()
    replies = burst(["wheel 400 900 1"] * 60)
    check(all(r == "ok" for r in replies), "every wheel command answered ok")
    ctl("settle 10000")
    n = merged() - before
    check(n >= 10, f"queued wheel events at one point merged ({n} of 60)")

    ctl("restart")
    ctl("settle 10000")
    print("input-merge-check: " + ("FAILED: " + "; ".join(failed) if failed else "passed"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
