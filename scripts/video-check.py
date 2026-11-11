#!/usr/bin/env python3
"""Recorded video in a running instance (issue 229): MediaCodec's H.264 decoder, and with --hevc the HEVC one (the
Linux image has it only when built with VIDEO_DECODERS=h264,hevc), over --control.
  video-check.py PORT [--clips DIR] [--seconds N] [--min-rendered N] [--hevc]
1. `selftest DIR/clip.h264` (and `DIR/clip.h265`; DIR as the app sees it): every unit drawn and the picture right.
2. `open apksim://video` (a 1080p60 H.264 clip looping in a TextureView): `video` twice, N s apart: `rendered` in the
   mediacodec part goes up by at least --min-rendered (30; not 60 a second, a shared runner is slower) and
   `decodeErrors` stays 0.
3. With --hevc: the test app's Settings tab, "Play clip" (ExoPlayer, HEVC + AAC): its `clip:` line reads "ended at
   3.0 s" and "video video/hevc" (no AAC decoder on Linux: the audio is not checked). Without it: no HEVC decoder is
   listed (`selftest DIR/clip.h265` finds none).
Exit 1 with the reason on the first failure."""
import argparse, re, socket, sys, time

ap = argparse.ArgumentParser()
ap.add_argument("port", type=int)
ap.add_argument("--clips", default="/work")
ap.add_argument("--seconds", type=float, default=3)
ap.add_argument("--min-rendered", type=int, default=30)
ap.add_argument("--hevc", action="store_true", help="the instance has an HEVC decoder too")
args = ap.parse_args()

sock = socket.create_connection(("127.0.0.1", args.port), timeout=60)
io = sock.makefile("rw")


def cmd(c):
    io.write(c + "\n"); io.flush()
    return io.readline().strip()


def fail(msg):
    print(msg, file=sys.stderr); sys.exit(1)


def stats():
    out = cmd("video")
    m = re.search(r"\| mediacodec (.*?) \|", out)
    if not m:
        fail(f"no mediacodec part in: {out}")
    return {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", m.group(1))}, out


for clip in ("clip.h264", "clip.h265") if args.hevc else ("clip.h264",):
    out = cmd(f"selftest {args.clips}/{clip}")
    m = re.search(r"codec \S+: \d+ of \d+ units drawn.*", out)
    print(f"selftest {clip}: {m.group(0) if m else out}")
    if not (m and "60 of 60 units drawn" in m.group(0) and m.group(0).endswith(" ok")):
        fail(f"{clip}: not every unit drawn or the picture wrong: {out}")

cmd("open apksim://video")
time.sleep(2)  # the player's first pictures
a, out_a = stats()
time.sleep(args.seconds)
b, out_b = stats()
rendered, dropped = b["rendered"] - a["rendered"], b["dropped"] - a["dropped"]
print(f"apksim://video over {args.seconds:g} s: rendered +{rendered}, dropped +{dropped}, decodeErrors {b['decodeErrors']}, "
      f"maxDecodeMs {b['maxDecodeMs']}")
if rendered < args.min_rendered:
    fail(f"only {rendered} pictures drawn in {args.seconds:g} s (want {args.min_rendered}): {out_a}\n{out_b}")
if b["decodeErrors"] != 0:
    fail(f"decodeErrors={b['decodeErrors']}: {out_b}")
cmd("back")
time.sleep(1)

if not args.hevc:
    out = cmd(f"selftest {args.clips}/clip.h265")
    print(f"selftest clip.h265 (no HEVC decoder expected): {out[-120:]}")
    if "units drawn" in out:
        fail("an HEVC decoder is listed, but this image should have H.264 only (or pass --hevc): " + out)
    sys.exit(0)

# The Settings tab (bottom bar, phone), scrolled down to the clip player's button.
cmd("tap 515 1690"); time.sleep(1.5)
tapped = ""
for _ in range(6):
    tapped = cmd("tap-text Play clip")
    if tapped.startswith("tapped"):
        break
    cmd("swipe 400 1400 400 400 300"); time.sleep(0.8)
if not tapped.startswith("tapped"):
    fail("no 'Play clip' button on the Settings tab: " + tapped)
line = ""
for _ in range(60):
    time.sleep(1)
    nodes = cmd("nodes")
    m = re.search(r'text="(clip: [^"]*)"', nodes)
    line = m.group(1) if m else ""
    if "ended" in line:
        break
print(line)
if "ended at 3.0 s" not in line or "video video/hevc" not in line:
    fail("the clip did not play through as HEVC: " + line)
