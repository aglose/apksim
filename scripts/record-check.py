#!/usr/bin/env python3
"""Checks per-test screen recording in test runs (`instrument.py --record`, issue 132) with the test app.

Usage: record-check.py APP.apk TEST.apk [OUT]

Runs FailureBundleTest (three tests that fail on purpose with `-e failOnPurpose true`, one that passes) with
`--record failed --isolate`, then `--record all` in one process, and checks: a movie for each failed test and none for
the passing one (failed), one for every test that showed a window (all), each a well-formed Motion-JPEG AVI (RIFF
header, MJPG, an index with as many entries as the header's frame count, at least one frame) no bigger than
--record-size and at the --record-fps rate in its header, the JUnit XML's property and attachment, and that a movie
left by an earlier run is removed first. OUT (default work/record-check) is emptied first.
"""
import argparse, os, re, shutil, struct, subprocess, sys, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
CLS = "dev.apksim.composeapp.FailureBundleTest"
problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)


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


def movies(rec):
    return {f: os.path.join(d, f) for d, _, fs in os.walk(rec) for f in fs if f.endswith(".avi")}


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

    print("no --record: nothing written:")
    r, xml, rec = run(a.out, "none")
    check(not movies(rec), "no movies")
    check("recording" not in open(xml).read(), "no links")

    print("--record without --record-dir is refused:")
    r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/instrument.py"), a.app, a.test, "--record", "all"],
                       capture_output=True, text=True)
    check(r.returncode == 2, f"exit {r.returncode}")

    print("FAILED: " + "; ".join(problems) if problems else "all checks passed")
    sys.exit(1 if problems else 0)


main()
