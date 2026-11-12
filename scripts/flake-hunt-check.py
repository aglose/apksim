#!/usr/bin/env python3
"""Checks scripts/flake-hunt.py against the test app's flaky-on-purpose tests (docs/flake-hunt.md).

Usage: flake-hunt-check.py APP.apk TEST.apk [-n N] [--perturb LIST] [--apksim CMD --local DIR]

Hunts in FlakyOrderTest, FlakyClassOrderTest, FlakyStateTest, FlakyTimingTest and AndroidIdTest with `-e flakyOnPurpose
true` and checks: FlakyOrderTest#expectsAFreshStatic, and FlakyClassOrderTest's two tests (whose class fails in its
@BeforeClass, so the run reports only FlakyClassOrderTest#null), are each found order-dependent with
FlakyStateTest#leavesAStaticSet, and only it, as the culprit, and the printed command reproduces each failure; FlakyTimingTest#aDelayedTaskRunsWithoutWaiting fails with
--realtime-clock and is called timing-dependent, not order-dependent; every other test is stable; the JUnit XML and the HTML summary are
written. -n and --perturb go to flake-hunt.py (CI: -n 1 --perturb suite,shuffle,isolate,realtime: the first shuffled
run is the suite's order reversed, which is enough here). With --apksim (the Linux image), APP and TEST are paths apksim sees and --local the folder they are in here.
"""
import argparse, json, os, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "dev.apksim.composeapp."
VICTIM = PKG + "FlakyOrderTest#expectsAFreshStatic"
CLASS_VICTIM = PKG + "FlakyClassOrderTest#runsAfterItsClassSetUp"  # (its class's @BeforeClass is what fails)
CLASS_VICTIM_2 = PKG + "FlakyClassOrderTest#alsoRunsAfterItsClassSetUp"  # (the same class: one reduction for both)
VICTIMS = (VICTIM, CLASS_VICTIM, CLASS_VICTIM_2)
CULPRIT = PKG + "FlakyStateTest#leavesAStaticSet"
TIMING = PKG + "FlakyTimingTest#aDelayedTaskRunsWithoutWaiting"


def main():
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("-n", type=int, default=3)
    p.add_argument("--perturb")
    p.add_argument("--apksim")
    p.add_argument("--local")
    a = p.parse_args()
    out = tempfile.mkdtemp(prefix="flake-hunt-check-")
    cmd = [sys.executable, os.path.join(HERE, "flake-hunt.py"), a.app, a.test, "-n", str(a.n), "--out", out,
           "-e", "flakyOnPurpose", "true", "-e", "class",
           ",".join(PKG + c for c in ("FlakyOrderTest", "FlakyClassOrderTest", "FlakyStateTest", "FlakyTimingTest", "AndroidIdTest"))]
    if a.apksim:
        cmd += ["--apksim", a.apksim]
    if a.perturb:
        cmd += ["--perturb", a.perturb]
    print("$ " + " ".join(cmd), file=sys.stderr)
    hunt = subprocess.run(cmd, capture_output=True, text=True, cwd=a.local or None)
    with open(os.path.join(out, "hunt.log"), "w") as f:
        f.write(hunt.stdout + hunt.stderr)
    print(hunt.stdout)
    problems = []
    try:
        v = json.load(open(os.path.join(out, "verdicts.json")))
    except OSError:
        print(hunt.stderr[-3000:])
        sys.exit(f"FAIL no verdicts.json (exit {hunt.returncode}); see {out}")
    timing = v.get(TIMING, {})
    for name in VICTIMS:
        victim = v.get(name, {})
        if victim.get("verdict") != "order-dependent":
            problems.append(f"{name}: {victim.get('verdict')} ({victim.get('rates')}), expected order-dependent")
        if victim.get("culprits") != [CULPRIT]:
            problems.append(f"{name}: culprits {victim.get('culprits')}, expected [{CULPRIT}]")
    if timing.get("verdict") != "timing-dependent":
        problems.append(f"{TIMING}: {timing.get('verdict')} ({timing.get('rates')}), expected timing-dependent")
    if "realtime" not in timing.get("failedIn", []):
        problems.append(f"{TIMING}: did not fail with --realtime-clock ({timing.get('rates')})")
    for t, x in v.items():
        if t not in VICTIMS + (TIMING,) and x["verdict"] != "stable":
            problems.append(f"{t}: {x['verdict']} ({x['rates']}), expected stable")
    for f in ("flake-hunt.xml", "index.html"):
        if not os.path.exists(os.path.join(out, f)):
            problems.append(f"no {f}")
    if hunt.returncode != 1:
        problems.append(f"flake-hunt.py exited {hunt.returncode}, expected 1 (flaky tests found)")
    # The command it printed must make the victim fail (its class, for a @BeforeClass), and nothing else.
    for i, name in enumerate(VICTIMS):
        victim = v.get(name, {})
        if not victim.get("reproduce"):
            continue
        xml = os.path.join(out, f"reproduce-{i}.xml")
        argv = victim["reproduceArgv"]
        subprocess.run(argv[:4] + ["--xml", xml] + argv[4:], capture_output=True, text=True, cwd=a.local or None)
        failed = [f"{c.get('classname')}#{c.get('name')}" for c in ET.parse(xml).iter("testcase")
                  if c.find("failure") is not None] if os.path.exists(xml) else None
        want = [name] if name == VICTIM else [name, name.split("#")[0] + "#null"]
        if not failed or len(failed) != 1 or failed[0] not in want:
            problems.append(f"the printed command for {name} failed {failed}, expected one of {want}")
    for line in problems:
        print("FAIL " + line)
    if problems:
        print(f"logs in {out}")
        sys.exit(1)
    print(f"ok: {VICTIM} and FlakyClassOrderTest's two tests blamed on {CULPRIT}, reproduced; {TIMING} {timing['verdict']} {timing['rates']}")
    shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    main()
