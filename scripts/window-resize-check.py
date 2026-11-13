#!/usr/bin/env python3
"""Checks that the Mac window follows a live drag of its edge and stops when the drag does.

  ./apksim --control 7400 --window background APK &
  scripts/window-resize-check.py 7400

Drags the window's content to another size over 2 s (`window WxH MS`: a step per display refresh, as a user's drag),
then back. A drag posts a resize per pixel moved; when each was applied in turn, the window kept snapping back to
stale sizes for half a minute after a 2 s drag. Passes when the window is at the size asked for within a second of
each drag's end, stays there, and the app has the display's new size. Mac only (needs the window).
"""
import socket
import sys
import time


def cmd(port, line):
    with socket.create_connection(("127.0.0.1", port)) as s:
        s.sendall((line + "\n").encode())
        return s.makefile().readline().strip()


def content(port):
    # "visible, not active, content 390x845 pt, 2.109 px/pt"
    state = cmd(port, "window")
    w, h = state.split("content ")[1].split(" pt")[0].split("x")
    return int(w), int(h)


def drag(port, w, h, ms):
    start = time.time()
    reply = cmd(port, f"window {w}x{h} {ms}")
    if reply != "ok":
        sys.exit(f"FAIL: window {w}x{h} {ms} answered {reply!r}")
    time.sleep(ms / 1000 + 1.0)
    # A whole dp of the display is not a whole point of the window: the end snaps by under 2 points.
    near = lambda size: abs(size[0] - w) <= 2 and abs(size[1] - h) <= 2
    first = content(port)
    if not near(first):
        sys.exit(f"FAIL: 1 s after a {ms} ms drag to {w}x{h} the window is {first[0]}x{first[1]} points")
    for _ in range(20):  # and it stays: no stale size comes after
        time.sleep(0.1)
        now = content(port)
        if now != first:
            sys.exit(f"FAIL: the window moved to {now[0]}x{now[1]} points after the drag to {w}x{h} had ended")
    scale = float(cmd(port, "window").split(", ")[-1].split(" px/pt")[0])
    display = cmd(port, "size")  # "phone 736x526 dp ... (1472x1052 px)"
    pw, ph = display.split("(")[-1].split(" px")[0].split("x")
    if abs(int(pw) - first[0] * scale) > 4 or abs(int(ph) - first[1] * scale) > 4:
        sys.exit(f"FAIL: the display is {pw}x{ph} px under a window of {first[0]}x{first[1]} points at {scale} px/pt")
    print(f"ok: dragged to {w}x{h} points in {ms} ms, display {display} ({time.time() - start:.1f} s)")


def main():
    port = int(sys.argv[1])
    w, h = content(port)
    drag(port, w + 300, max(200, h - 300), 2000)
    drag(port, w, h, 600)


if __name__ == "__main__":
    main()
