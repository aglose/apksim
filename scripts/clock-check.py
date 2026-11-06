#!/usr/bin/env python3
"""Checks that an app's timers keep wall-clock time in a running instance, and that input reaches
Activity.onUserInteraction, with the test app's apksim://clock screen (ClockCheckActivity: a CountDownTimer restarted
from onUserInteraction, plus a thread polling with SystemClock.sleep, as libraries do).

  scripts/clock-check.py PORT [--adb SERIAL] [--seconds 60]

PORT is the instance's control port; with --adb (localhost:PORT of an instance started with --adb) the adb input
paths are checked too. Two rounds: the timer left alone, then input from each path (control tap, touch, key; adb
input tap and keyevent), each of which must count as a user interaction, then left alone again. Each round's timer
must finish after SECONDS of wall time (within 10%), with elapsedRealtime agreeing. Takes about 2 x SECONDS.
Exits non-zero with the reason on the first failure. adb comes from PATH, or $ADB.
"""
import argparse
import os
import re
import socket
import subprocess
import sys
import time

ADB = os.environ.get("ADB", "adb")


def fail(why):
    print(f"clock check FAILED: {why}", file=sys.stderr)
    sys.exit(1)


class Control:
    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=60)
        self.f = self.s.makefile("r", encoding="utf-8")

    def __call__(self, cmd):
        self.s.sendall((cmd + "\n").encode())
        return self.f.readline().strip()


def state(ctl):
    nodes = ctl("nodes")
    timer = re.search(r'text="(timer: [^"]*)"', nodes)
    count = re.search(r'text="interactions: (\d+)"', nodes)
    if not timer or not count:
        fail(f"the clock screen is not showing: {nodes[:400]}")
    return timer.group(1), int(count.group(1))


def wait_finished(ctl, seconds):
    start = time.time()
    while time.time() - start < seconds * 1.5 + 10:
        timer, _ = state(ctl)
        if timer.startswith("timer: finished"):
            m = re.fullmatch(r"timer: finished after ([\d.]+) s wall, ([\d.]+) s elapsed", timer)
            wall, elapsed = float(m.group(1)), float(m.group(2))
            if abs(wall - seconds) > seconds * 0.1:
                fail(f"a {seconds} s timer finished after {wall} s of wall time ({elapsed} s of elapsedRealtime)")
            if abs(elapsed - wall) > max(1.0, seconds * 0.05):
                fail(f"elapsedRealtime moved {elapsed} s while {wall} s of wall time passed")
            return wall
        time.sleep(0.5)
    fail(f"a {seconds} s timer had not finished after {time.time() - start:.0f} s: {timer}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("port", type=int)
    p.add_argument("--adb", metavar="SERIAL")
    p.add_argument("--seconds", type=int, default=60)
    a = p.parse_args()
    ctl = Control(a.port)

    print(f"idle: a {a.seconds} s timer, a thread polling with SystemClock.sleep(100)")
    if not ctl(f"open apksim://clock?seconds={a.seconds}&sleeper=1").startswith("opening"):
        fail("could not open apksim://clock (is this the test app?)")
    time.sleep(1)
    print(f"  finished after {wait_finished(ctl, a.seconds):.1f} s")

    # The screen is a TextView in the middle of a LinearLayout: a tap anywhere is on the activity's window.
    paths = [("control tap", lambda: ctl("tap 400 1200")),
             ("control touch", lambda: (ctl("touch down 400 1200"), ctl("touch up 400 1200"))),
             ("control key", lambda: ctl("key SPACE"))]
    if a.adb:
        subprocess.run([ADB, "connect", a.adb], capture_output=True, timeout=30)
        paths += [("adb input tap", lambda: subprocess.run([ADB, "-s", a.adb, "shell", "input", "tap", "400", "1200"], check=True, timeout=60)),
                  ("adb input keyevent", lambda: subprocess.run([ADB, "-s", a.adb, "shell", "input", "keyevent", "SPACE"], check=True, timeout=60))]
    print("input: each path must reach onUserInteraction")
    if not ctl(f"open apksim://clock?seconds={a.seconds}&sleeper=1").startswith("opening"):
        fail("could not open apksim://clock again")
    time.sleep(1)
    for name, send in paths:
        _, before = state(ctl)
        send()
        deadline = time.time() + 3
        while (after := state(ctl)[1]) == before and time.time() < deadline:
            time.sleep(0.1)
        if after == before:
            fail(f"{name} did not reach Activity.onUserInteraction (interactions stayed {before})")
        print(f"  {name}: interactions {before} -> {after}")
    print(f"  then left alone: finished after {wait_finished(ctl, a.seconds):.1f} s")
    ctl("back")
    ctl("back")
    print("clock check passed: timers keep wall time with SystemClock.sleep polling, input reaches onUserInteraction")


if __name__ == "__main__":
    main()
