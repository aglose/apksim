#!/usr/bin/env python3
"""The app's audio as a tab hears it (issue 532): `audiowatch` on a running instance's control port must give PCM
chunks (`u PRESENTED_US TRACK RATE CHANNELS BASE64`) while `selftest CLIP.aac` plays through an AudioTrack, none
after `stream off`, and chunks again after `stream on`.

  scripts/audio-tap-check.py PORT work/media/clip.aac
"""
import base64, socket, sys, time

port, clip = int(sys.argv[1]), sys.argv[2]


def conn():
    s = socket.create_connection(("127.0.0.1", port), timeout=30)
    return s, s.makefile("rw", newline="\n")


w, wf = conn()
wf.write("audiowatch\n"); wf.flush()
assert wf.readline().strip() == "ok audiowatch"
w.settimeout(1.0)
c, cf = conn()


def run_selftest():
    cf.write("selftest " + clip + "\n"); cf.flush()
    return cf.readline().strip()


def drain(seconds):
    got, end, buf = [], time.time() + seconds, b""
    while time.time() < end:
        try:
            d = w.recv(1 << 16)
        except socket.timeout:
            continue
        if not d:
            break
        buf += d
        *lines, buf = buf.split(b"\n")
        got += [l.decode() for l in lines if l.startswith(b"u ")]
    return got


import threading
res = {}
threading.Thread(target=lambda: res.setdefault("r", run_selftest()), daemon=True).start()
first = drain(8)
assert first, "no audio chunks while the clip played (selftest: %r)" % res.get("r")
f = first[0].split(" ")
presented, track, rate, ch, data = int(f[1]), f[2], int(f[3]), int(f[4]), base64.b64decode(f[5])
frames = len(data) // (2 * ch)
assert 0 < frames <= rate // 50 + 1, "chunk of %d frames at %d Hz is over 20 ms" % (frames, rate)
assert abs(presented / 1e6 - time.time()) < 30, "presented is not epoch microseconds: %d" % presented
assert any(b for b in data) or any(any(base64.b64decode(l.split(" ")[5])) for l in first), "all silence"
print("ok: %d chunks, %d Hz, %d ch, %d frames in the first" % (len(first), rate, ch, frames))
print("selftest:", res.get("r", "(running)"))

wf.write("stream off\n"); wf.flush()
drain(1)  # what was in flight
t = threading.Thread(target=lambda: print("selftest again:", run_selftest()), daemon=True); t.start()
off = drain(5)
assert not off, "%d chunks after stream off" % len(off)
wf.write("stream on\n"); wf.flush()
t.join(60)
t = threading.Thread(target=lambda: run_selftest(), daemon=True); t.start()
on = drain(6)
assert on, "no chunks after stream on"
print("ok: off sent none, on sends again (%d)" % len(on))
