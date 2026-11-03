#!/usr/bin/env python3
"""Sends the same input to an apksim instance and an adb emulator, then captures and compares both.
Usage: both.py PORT OUTDIR 'cmd' ...
  tap X Y | swipe X1 Y1 X2 Y2 | drag X1 Y1 X2 Y2 (no fling) | back | type TEXT | wait MS | sim CMD | emu SHELLCMD | shot NAME (saves e-NAME.png, s-NAME.png, c-NAME.png)
Coordinates are device pixels; the emulator must be set to the same size (wm size / wm density)."""
import os, socket, subprocess, sys, time

port, out = int(sys.argv[1]), sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
adb = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
s = socket.create_connection(("127.0.0.1", port), timeout=5)
s.settimeout(60)
f = s.makefile("rw")


def sim(cmd):
    f.write(cmd + "\n")
    f.flush()
    return f.readline().strip()


def emu(*args):
    return subprocess.run([adb, "shell", *args], capture_output=True, text=True).stdout.strip()


for cmd in sys.argv[3:]:
    a = cmd.split()
    if a[0] == "wait":
        time.sleep(int(a[1]) / 1000)
    elif a[0] == "tap":
        emu("input", "tap", a[1], a[2])
        sim(cmd)
    elif a[0] == "swipe":
        emu("input", "swipe", *a[1:5], "120")
        sim(cmd)
    elif a[0] == "drag":
        # A slow drag that stops before lifting, so neither side flings and both land on the same offset.
        x1, y1, x2, y2 = map(int, a[1:5])
        steps = ["input motionevent DOWN %d %d" % (x1, y1)]
        steps += ["input motionevent MOVE %d %d" % (x1 + (x2 - x1) * i // 20, y1 + (y2 - y1) * i // 20) for i in range(1, 21)]
        steps += ["sleep 0.15", "input motionevent UP %d %d" % (x2, y2)]
        emu("; ".join(steps))
        sim("swipe %d %d %d %d 600" % (x1, y1, x2, y2))
    elif a[0] == "back":
        emu("input", "keyevent", "KEYCODE_BACK")
        sim(cmd)
    elif a[0] == "type":
        emu("input", "text", cmd[5:].replace(" ", "%s"))
        sim(cmd)
    elif a[0] == "shot":
        # The emulator lags under load, so retake while the two differ a lot, giving it time to catch up.
        name = a[1]
        e, si, c = (os.path.join(out, p + name + ".png") for p in ("e-", "s-", "c-"))
        for attempt in range(4):
            with open(e, "wb") as fh:
                fh.write(subprocess.run([adb, "exec-out", "screencap", "-p"], capture_output=True).stdout)
            sim("shot " + si)
            r = subprocess.run([os.path.join(out, ".venv/bin/python"), os.path.join(here, "sidebyside.py"), e, si, c],
                               capture_output=True, text=True)
            result = r.stdout.strip() or r.stderr.strip()
            if "mean diff" in result and float(result.split()[2]) < 12:
                break
            time.sleep(4)
        print(name, result, flush=True)
    elif a[0] == "sim":
        print(cmd, "->", sim(cmd[4:]))
    elif a[0] == "emu":
        emu(cmd[4:])
    else:
        print(cmd, "->", sim(cmd))
