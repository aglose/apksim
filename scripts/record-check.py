#!/usr/bin/env python3
"""Checks per-test screen recording in test runs (`instrument.py --record`, issue 132) with the test app.

Usage: record-check.py APP.apk TEST.apk [OUT]

Runs FailureBundleTest (three tests that fail on purpose with `-e failOnPurpose true`, one that passes) with
`--record failed --isolate`, then `--record all` in one process, and checks: a movie for each failed test and none for
the passing one (failed), one for every test that showed a window (all), each a well-formed Motion-JPEG AVI (RIFF
header, MJPG, an index with as many entries as the header's frame count, at least one frame) no bigger than
--record-size and at the --record-fps rate in its header, the JUnit XML's property and attachment, and that a movie
left by an earlier run is removed first. Then one camera test with every capture slowed to 150 ms (issue 360):
it must pass and the captures must leave the main thread most of its time. OUT (default work/record-check) is emptied first.
`--record-format mp4` (issue 454) is checked too: H.264 MP4s with moov before mdat, a sidecar .json with the wall-clock
start, and the XML property; where no encoder opens it expects the documented AVI fallback.
"""
import argparse, json, os, re, shutil, signal, struct, subprocess, sys, time, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
CLS = "dev.apksim.composeapp.FailureBundleTest"
problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)
    return ok


def avi(path):
    """(width, height, fps, frames, index entries) of a Motion-JPEG AVI as MjpegRecorder writes it, or a problem string."""
    d = open(path, "rb").read()
    if d[:4] != b"RIFF" or d[8:12] != b"AVI " or struct.unpack("<I", d[4:8])[0] != len(d) - 8:
        return "bad RIFF header or size"
    i = d.index(b"avih")
    usec, _, _, _, frames, _, _, _, w, h = struct.unpack("<10I", d[i + 8:i + 48])
    if b"MJPG" not in d[:300]:
        return "not MJPG"
    i = d.index(b"idx1")
    n = struct.unpack("<I", d[i + 4:i + 8])[0] // 16
    return w, h, round(1_000_000 / usec), frames, n


def run(out, name, *extra):
    xml = os.path.join(out, name + ".xml")
    rec = os.path.join(out, name)
    cmd = [sys.executable, os.path.join(ROOT, "scripts/instrument.py"), a.app, a.test, "--xml", xml,
           "-e", "failOnPurpose", "true", "-e", "class", CLS] + (["--record-dir", rec] if "--record" in extra else []) \
        + list(extra)
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r, xml, rec


def movies(rec, ext=".avi"):
    return {f: os.path.join(d, f) for d, _, fs in os.walk(rec) for f in fs if f.endswith(ext)}


def mp4(path):
    """(top-level box names, has avc1, frame count from stsz) of an MP4 as Mp4Recorder writes it, or a problem string."""
    d = open(path, "rb").read()
    boxes, i = [], 0
    while i < len(d):
        if i + 8 > len(d):
            return "truncated box header"
        size, name = struct.unpack(">I4s", d[i:i + 8])
        if size < 8 or i + size > len(d):
            return f"bad size for {name!r}"
        boxes.append(name.decode("latin1"))
        i += size
    k = d.find(b"stsz")
    frames = struct.unpack(">I", d[k + 12:k + 16])[0] if k >= 0 else 0
    return boxes, b"avc1" in d and b"avcC" in d, frames


def main():
    global a
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("out", nargs="?", default="work/record-check")
    a = p.parse_args()
    shutil.rmtree(a.out, ignore_errors=True)
    os.makedirs(a.out)

    print("--record failed --isolate:")
    stale = os.path.join(a.out, "failed", CLS)
    os.makedirs(stale)
    open(os.path.join(stale, "a_passes-00000000.avi"), "w").write("an earlier run's movie\n")
    r, xml, rec = run(a.out, "failed", "--isolate", "-j", "2", "--record", "failed", "--record-size", "320x320",
                      "--record-fps", "10")
    check(r.returncode == 1, f"the run fails (exit {r.returncode})")
    got = movies(rec)
    names = sorted(re.sub(r"-[0-9a-f]{8}\.avi$", "", f) for f in got)
    print("        movies:", names)
    check("a_passes" not in names, "no movie for the passing test (the earlier run's is removed)")
    check(len(names) >= 2, "a movie for the failed tests that showed a window")
    for f, path in got.items():
        v = avi(path)
        if isinstance(v, str):
            check(False, f"{f}: {v}")
            continue
        w, h, fps, frames, n = v
        check(frames >= 1 and frames == n, f"{f}: {frames} frames, index {n}")
        check(w <= 320 and h <= 320 and w % 2 == 0 and h % 2 == 0 and fps == 10, f"{f}: {w}x{h} at {fps} fps")
    root = ET.parse(xml).getroot()
    linked = {t.get("name"): [x.get("value") for x in t.iter("property") if x.get("name") == "apksim.recording"]
              for t in root.iter("testcase")}
    check(all(v and os.path.isfile(os.path.join(os.path.dirname(xml), v[0])) for k, v in linked.items() if v), "links resolve")
    check(sum(1 for v in linked.values() if v) == len(got), "the XML links each movie, and only those")
    check(all("[[ATTACHMENT|" in (t.findtext("system-out") or "") for t in root.iter("testcase") if linked[t.get("name")]),
          "each linked test has an attachment line")

    print("--record all, one process:")
    r, xml, rec = run(a.out, "all", "--record", "all")
    got = movies(rec)
    names = sorted(re.sub(r"-[0-9a-f]{8}\.avi$", "", f) for f in got)
    print("        movies:", names)
    check("a_passes" in names, "a movie for the passing test too")
    check(all(not isinstance(avi(p_), str) and avi(p_)[3] >= 1 for p_ in got.values()), "every movie is well formed")

    print("--record all --record-format mp4 (issue 454):")
    r, xml, rec = run(a.out, "mp4", "--record", "all", "--record-size", "320x320", "--record-fps", "10",
                      "--record-format", "mp4")
    if "recording AVI instead" in r.stderr:
        print("  (no H.264 encoder here: apksim fell back to AVI, as documented)")
        check(bool(movies(rec)) and not movies(rec, ".mp4"), "the AVI fallback wrote movies")
    else:
        mv = movies(rec, ".mp4")
        check(len(mv) >= 3 and not movies(rec), f"{len(mv)} .mp4 movies and no .avi")
        for name, path in sorted(mv.items()):
            v = mp4(path)
            if check(isinstance(v, tuple), f"{name} is well-formed: {v}"):
                boxes, avc, frames = v
                check(boxes[:3] == ["ftyp", "moov", "mdat"], f"{name}: moov before mdat ({boxes})")
                check(avc and frames >= 1, f"{name}: avc1 with avcC, {frames} frames")
            side = os.path.splitext(path)[0] + ".json"
            if check(os.path.isfile(side), f"{name}: sidecar json"):
                j = json.load(open(side))
                check(abs(j["start_epoch_ms"] - time.time() * 1000) < 3_600_000 and j["fps"] == 10
                      and j["width"] <= 320 and j["height"] <= 320 and j["width"] % 2 == 0 and j["duration_ms"] > 0,
                      f"{name}: sidecar {j}")
        t = open(xml).read()
        check(t.count("apksim.recording.startEpochMs") == len(mv) and ".mp4" in t, "the XML links each .mp4 and its start")

    print("no --record: nothing written:")
    r, xml, rec = run(a.out, "none")
    check(not movies(rec), "no movies")
    check("recording" not in open(xml).read(), "no links")

    print("--record without --record-dir is refused:")
    r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/instrument.py"), a.app, a.test, "--record", "all"],
                       capture_output=True, text=True)
    check(r.returncode == 2, f"exit {r.returncode}")

    # Issue 360: a capture that takes longer than 1/fps (here 150 ms at 15 fps, through the test-only property) while the
    # screen keeps changing in real time (the fake camera's preview) must not keep the main looper busy for good: the
    # next frame is due after the last capture ended, and 4x its cost later. Before, this run never ended.
    print("a slow capture on a live camera preview:")
    rec = os.path.join(a.out, "slow")
    cmd = [os.path.join(ROOT, "apksim"), "--instrument", a.test, "-e", "camera", "fake", "-e", "class",
           "dev.apksim.composeapp.CameraTest#cameraXBindsAPreviewAndAnAnalysisAndGetsFrames", "--record", "all",
           "--record-dir", rec, "--camera", "fake", "--jvm", "-Dapksim.record.captureDelayMs=150", a.app]
    p_ = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
    try:
        text = p_.communicate(timeout=120)[0]
        check("\nOK (1 test)" in text, "the test passes with recording on")
        m = re.search(r"\[apksim\] recording \S+: (\d+) captures, busy (\d+) ms of (\d+) ms", text)
        check(bool(m), "the recorder reports its captures")
        if m:
            n, busy, total = map(int, m.groups())
            check(n >= 2 and busy < 0.7 * total, f"{n} captures, busy {busy} ms of {total} ms (under 70%)")
        check(len(movies(rec)) == 1, "its movie is kept")
    except subprocess.TimeoutExpired:
        os.killpg(p_.pid, signal.SIGKILL)
        p_.communicate()
        check(False, "the test ends within 120 s (captures starved the main looper)")

    print("FAILED: " + "; ".join(problems) if problems else "all checks passed")
    sys.exit(1 if problems else 0)


main()
