#!/usr/bin/env python3
"""Checks that app timers keep to real time in a running instance, and that every input path reaches
Activity.onUserInteraction, with the test app's apksim://timer page (TimerActivity). An app-wide inactivity timer is
a CountDownTimer reset from onUserInteraction: it fires on time only if both hold.

  scripts/timer-check.py CONTROL_PORT [--adb SERIAL]

CONTROL_PORT: an instance of the test app started with --control (and --adb PORT for --adb localhost:PORT). Takes
about 70 s: the page's 60 s CountDownTimer must finish after 60 s of wall time, first idle, then with input arriving
every half second; a worker's SystemClock.sleep must take real time and move the clock no further than that; the
clock must stay within a few frames of real time (`stats` clockAheadMs, the page's drift); and taps, keys and text
from the control port (as the Mac window's input, which takes the same dispatch) and from adb must each count as user
interactions. Exits non-zero with the reason on the first failure.
"""
import argparse
import os
import re
import socket
import subprocess
import sys
import time

ADB = os.environ.get("ADB", "adb")
SLEEP_BUTTON = (180, 376)  # "Sleep worker" (phone device)
EMPTY = (400, 1000)        # nothing there: a touch only the activity sees
DRIFT_MS = 150             # a few frames: the loop moves the clock in whole frames while animating


def fail(why):
    print(f"timer check FAILED: {why}", file=sys.stderr)
    sys.exit(1)


class Control:
    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=30)
        self.f = self.s.makefile("r", encoding="utf-8")

    def __call__(self, cmd):
        self.s.sendall((cmd + "\n").encode())
        return self.f.readline().strip()


def page(ctl):
    """The page's texts: timer, drift, interactions, sleep."""
    out = {}
    for t in re.findall(r'text="([^"]*)"', ctl("nodes")):
        for key in ("timer", "drift", "interactions", "sleep"):
            if t.startswith(key + ":"):
                out[key] = t
    return out


def counts(ctl):
    m = re.search(r"interactions: (\d+), touches (\d+), keys (\d+)", page(ctl).get("interactions", ""))
    if not m:
        fail(f"the timer page is not showing: {page(ctl)}")
    return tuple(int(g) for g in m.groups())


def check_clock(ctl, when):
    ahead = int(re.search(r"clockAheadMs=(-?\d+)", ctl("stats")).group(1))
    drift = page(ctl).get("drift", "")
    m = re.search(r"elapsed (-?\d+) ms, uptime (-?\d+) ms", drift)
    if not m:
        fail(f"no drift on the page: {drift!r}")
    # Ahead only: a stall the loop does not replay (an activity starting) leaves the clock behind, timers late.
    if ahead > DRIFT_MS or any(int(g) > DRIFT_MS for g in m.groups()):
        fail(f"{when}: the clock left real time: stats clockAheadMs={ahead}, page {drift!r}")
    print(f"  {when}: clockAheadMs={ahead}, {drift}")


def expect(ctl, before, interactions, touches, keys, how):
    deadline = time.time() + 5
    while time.time() < deadline:
        now = counts(ctl)
        if now == (before[0] + interactions, before[1] + touches, before[2] + keys):
            print(f"  {how}: +{interactions} interactions")
            return now
        time.sleep(0.1)
    fail(f"{how}: wanted +{interactions} interactions, +{touches} touches, +{keys} keys from {before}, got {now}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("port", type=int)
    ap.add_argument("--adb", metavar="SERIAL", help="also send input through adb (an instance started with --adb)")
    a = ap.parse_args()
    ctl = Control(a.port)

    def adb(*args):
        r = subprocess.run([ADB, "-s", a.adb, "shell", *args], capture_output=True, text=True, timeout=60)
        if r.returncode != 0 or r.stdout.strip():
            fail(f"adb shell {' '.join(args)}: {(r.stdout + r.stderr).strip()}")

    if a.adb:
        subprocess.run([ADB, "connect", a.adb], capture_output=True, timeout=30)
        subprocess.run([ADB, "-s", a.adb, "wait-for-device"], capture_output=True, timeout=60)

    # A fresh page: its timer starts with the activity.
    ctl("restart apksim://timer")
    start = time.time()
    ctl("settle 5000")
    if "running" not in page(ctl).get("timer", ""):
        fail(f"the timer page did not open: {page(ctl)}")

    print("input paths:")
    n = counts(ctl)
    ctl(f"tap {EMPTY[0]} {EMPTY[1]}")
    n = expect(ctl, n, 1, 1, 0, "control tap")
    ctl(f"swipe {EMPTY[0]} {EMPTY[1]} {EMPTY[0]} {EMPTY[1] - 200} 300")
    n = expect(ctl, n, 1, 1, 0, "control swipe")
    ctl("key B")
    n = expect(ctl, n, 2, 0, 1, "control key (down and up)")
    ctl("type x")
    n = expect(ctl, n, 2, 0, 1, "control type")
    if a.adb:
        adb("input", "tap", str(EMPTY[0]), str(EMPTY[1]))
        n = expect(ctl, n, 1, 1, 0, "adb input tap")
        adb("input", "swipe", str(EMPTY[0]), str(EMPTY[1]), str(EMPTY[0]), str(EMPTY[1]), "500")
        n = expect(ctl, n, 1, 1, 0, "adb input swipe (a hold)")
        adb("input", "keyevent", "29")
        n = expect(ctl, n, 2, 0, 1, "adb input keyevent 29")
        adb("input", "text", "y")
        n = expect(ctl, n, 2, 0, 1, "adb input text")

    print("SystemClock.sleep on a worker:")
    ctl(f"tap {SLEEP_BUTTON[0]} {SLEEP_BUTTON[1]}")
    n = counts(ctl)
    deadline = time.time() + 10
    while time.time() < deadline and not re.search(r"took \d+", page(ctl).get("sleep", "")):
        time.sleep(0.2)
    s = page(ctl).get("sleep", "")
    m = re.search(r"took (\d+) ms of wall time, clock moved (\d+) ms", s)
    if not m:
        fail(f"the sleep worker did not finish: {s!r}")
    wall, clock = int(m.group(1)), int(m.group(2))
    if wall < 1950 or clock > wall + DRIFT_MS:
        fail(f"SystemClock.sleep: 20 x 100 ms took {wall} ms of wall time and moved the clock {clock} ms")
    print(f"  {s}")

    print("idle:")
    while time.time() - start < 30:
        time.sleep(1)
    check_clock(ctl, "after 30 s, idle")
    if "running" not in page(ctl).get("timer", ""):
        fail(f"the 60 s timer finished early: {page(ctl)['timer']}")

    print("with input every 0.5 s:")
    finished = None
    i = 0
    while time.time() - start < 75:
        ctl(f"tap {EMPTY[0]} {EMPTY[1] + (i % 5) * 40}")
        if a.adb and i % 4 == 1:
            adb("input", "tap", str(EMPTY[0]), str(EMPTY[1]))
        i += 1
        t = page(ctl).get("timer", "")
        if "finished" in t:
            finished = t
            break
        if time.time() - start > 45 and i % 10 == 0:
            check_clock(ctl, f"after {time.time() - start:.0f} s, with input")
        time.sleep(0.5)
    if not finished:
        fail("the 60 s timer had not finished after 75 s")
    secs = float(re.search(r"after ([\d.]+) s", finished).group(1))
    if not 59.5 <= secs <= 61.5:
        fail(f"the 60 s timer {finished}")
    print(f"  {finished}")
    check_clock(ctl, "at the end")
    print("timer check passed: a 60 s CountDownTimer took 60 s idle and with input, SystemClock.sleep took real time,"
          " and taps, swipes, keys and text from every path reached onUserInteraction")


if __name__ == "__main__":
    main()
