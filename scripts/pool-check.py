#!/usr/bin/env python3
"""Checks instrument.py --isolate's pre-warmed test processes (--prewarm, docs/test-pool.md) against the real runtime.

Usage: pool-check.py APP.apk TEST.apk [--apksim CMD] [--local DIR] [-j N]

Runs the same tests with --isolate twice, the pool on and off, and checks: the same result for every test; with the
pool on every test ran in a pooled process (JUnit property apksim.pooled) and with it off none did; a pooled process
honoured the run's -e arguments and apksim options (WallClockTest#timezoneIsTheOneAsked with -e timezone and
--timezone); a failing test's bundle came from its pooled process and its repro.sh names the test and not --pooled
(FailureBundleTest#b_composeAssertionFails with -e failOnPurpose true). With --apksim (the Linux image), APP and TEST
are paths that apksim sees and --local is the folder they are in here (bundles go there). Exits 1 on any problem.
"""
import argparse, os, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "dev.apksim.composeapp."
TESTS = [PKG + "ThermalTest", PKG + "TestApkContextTest", PKG + "WallClockTest#timezoneIsTheOneAsked",
         PKG + "FailureBundleTest#a_passes", PKG + "FailureBundleTest#b_composeAssertionFails"]
FAILS = {(PKG + "FailureBundleTest", "b_composeAssertionFails")}
ZONE = "America/New_York"


def run(a, mode, out_local, out_seen):
    xml = os.path.join(out_local, f"pool-{mode}.xml")
    cmd = [sys.executable, os.path.join(HERE, "instrument.py"), a.app, a.test, "--isolate", "-j", str(a.j),
           "--prewarm", mode, "--xml", xml, "-e", "class", ",".join(TESTS), "-e", "timezone", ZONE,
           "-e", "failOnPurpose", "true", "--bundles", os.path.join(out_seen, f"bundles-{mode}"),
           "--test-timeout", "300"]  # (a regression must not hold a CI runner for the default 900 s a test)
    if a.apksim:
        cmd += ["--apksim", a.apksim, "--bundles-local", os.path.join(out_local, f"bundles-{mode}")]
    cmd += ["--", "--timezone", ZONE]
    p = subprocess.run(cmd, capture_output=True, text=True)
    with open(os.path.join(out_local, f"pool-{mode}.log"), "w") as f:
        f.write(p.stdout + p.stderr)
    cases = {}
    if os.path.exists(xml):
        for c in ET.parse(xml).iter("testcase"):
            props = {q.get("name"): q.get("value") for q in c.iter("property")}
            status = "failed" if c.find("failure") is not None else "skipped" if c.find("skipped") is not None else "passed"
            cases[(c.get("classname"), c.get("name"))] = (status, props.get("apksim.pooled") == "true",
                                                          props.get("apksim.bundle"))
    print(f"--prewarm {mode}: exit {p.returncode}, {len(cases)} tests", file=sys.stderr)
    return p.returncode, cases, os.path.dirname(xml)


def main():
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("--apksim")
    p.add_argument("--local", help="with --apksim: the folder here that apksim sees as its working directory")
    p.add_argument("-j", type=int, default=2)
    a = p.parse_args()
    if a.apksim and not a.local:
        p.error("--apksim needs --local DIR")
    out_local = tempfile.mkdtemp(prefix="pool-check-", dir=a.local) if a.local else tempfile.mkdtemp(prefix="pool-check-")
    out_seen = os.path.basename(out_local) if a.local else out_local
    problems = []
    on_code, on, xml_dir = run(a, "on", out_local, out_seen)
    off_code, off, _ = run(a, "off", out_local, out_seen)
    named = {tuple(t.split("#")) for t in TESTS if "#" in t}
    missing = sorted(f"{c}#{m}" for c, m in named - set(on))
    if missing:  # (a selector that matches nothing runs nothing, and would check nothing)
        problems.append(f"no result for {', '.join(missing)}")
    if not on or set(on) != set(off):
        problems.append(f"different tests: on {sorted(on)}, off {sorted(off)}")
    for t in sorted(set(on) & set(off)):
        if on[t][0] != off[t][0]:
            problems.append(f"{t[0]}#{t[1]}: {on[t][0]} pooled, {off[t][0]} cold")
        want = "failed" if t in FAILS else "passed"
        if on[t][0] != want:
            problems.append(f"{t[0]}#{t[1]}: {on[t][0]}, expected {want}")
        if not on[t][1]:
            problems.append(f"{t[0]}#{t[1]}: not run by a pooled process with the pool on")
        if off[t][1]:
            problems.append(f"{t[0]}#{t[1]}: run by a pooled process with --prewarm off")
    if on_code != 1 or off_code != 1:
        problems.append(f"exit {on_code} on, {off_code} off: expected 1 (one test fails on purpose)")
    for t in FAILS:
        bundle = on.get(t, (None, None, None))[2]
        repro = os.path.join(xml_dir, bundle, "repro.sh") if bundle else None
        if not repro or not os.path.exists(repro):
            problems.append(f"{t[0]}#{t[1]}: no bundle with a repro.sh from its pooled process")
            continue
        text = open(repro).read()
        if "--pooled" in text:
            problems.append(f"{repro} starts a pooled process (--pooled), which would wait for its test on stdin")
        if f"{t[0]}#{t[1]}" not in text:
            problems.append(f"{repro} does not name {t[0]}#{t[1]}")
    for line in problems:
        print("FAIL " + line)
    if not problems:
        print(f"ok: {len(on)} tests, the same results with the pool on and off, every one pooled with it on")
        shutil.rmtree(out_local, ignore_errors=True)
    else:
        print(f"logs in {out_local}")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
