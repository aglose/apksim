#!/usr/bin/env python3
"""Checks instrument.py's handling of a test process that dies mid-run, against a stand-in for apksim.

Usage: instrument-check.py. No apksim, APK or JVM needed: instrument.py runs with `--apksim "instrument-check.py
--fake"`, and this script then plays apksim --instrument, writing `am instrument -r` output for a made-up suite
(classes p.C000.., methods m0..) and dying where the scenario (CHECK_SCENARIO) says. It honours -e class, notClass
and log true as apksim does, and counts its calls in CHECK_DIR. Each scenario checks the exit status, the processes
started, and the JUnit XML (one entry per test, the failures expected). The real thing is ProcessDeathTest, run with
`-e crashOnPurpose true` (CI's linux-smoke job).
"""
import os, re, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(sys.argv[0]))


def fake():
    args, e, i = sys.argv[2:], {}, 0
    while i < len(args):
        if args[i] == "-e":
            e[args[i + 1]] = args[i + 2]
            i += 3
        else:
            i += 1
    sc, state = os.environ["CHECK_SCENARIO"], os.environ["CHECK_DIR"]
    with open(os.path.join(state, "calls"), "a") as f:
        f.write(f"{'list' if e.get('log') == 'true' else 'run'} {max(len(a) for a in args)}\n")
    calls = sum(1 for line in open(os.path.join(state, "calls")) if line.startswith("run"))
    big = sc == "big"
    pre = "com.example.averylongpackagenamefortheargumentlimit.feature." if big else "p."
    universe = [(f"{pre}C{c:03d}", f"m{m}") for c in range(300 if big else 3) for m in range(10 if big else 3)]
    keep = [s for s in e.get("class", "").split(",") if s]
    drop = [s for s in e.get("notClass", "").split(",") if s]
    tests = [(c, m) for c, m in universe
             if c not in drop and f"{c}#{m}" not in drop and (not keep or c in keep or f"{c}#{m}" in keep)]

    def block(c, m, n, code, stack=None):
        print(f"INSTRUMENTATION_STATUS: class={c}\nINSTRUMENTATION_STATUS: current={n}\n"
              f"INSTRUMENTATION_STATUS: numtests={len(tests)}")
        if stack:
            print(f"INSTRUMENTATION_STATUS: stack={stack}")
        print(f"INSTRUMENTATION_STATUS: test={m}\nINSTRUMENTATION_STATUS_CODE: {code}")

    if e.get("log") == "true":
        if sc == "listing-fails":
            sys.exit(3)
        for n, (c, m) in enumerate(tests):
            block(c, m, n + 1, 1)
            block(c, m, n + 1, 0)
        print("INSTRUMENTATION_CODE: -1")
        return
    if sc == "startup" or (sc == "rerun-startup" and calls > 1):
        print("dies at startup", file=sys.stderr)
        sys.exit(1)
    for n, (c, m) in enumerate(tests):
        if sc == "before-class" and c == "p.C001":
            if m == "m0":
                block(c, "null", n + 1, 1)
                block(c, "null", n + 1, -2, "java.lang.IllegalStateException: @BeforeClass failed")
            continue
        if sc == "between-tests" and calls == 1 and n == 2:
            print("dies between tests", file=sys.stderr)
            sys.exit(134)
        block(c, m, n + 1, 1)
        if {"during-test": calls == 1 and n == 1, "rerun-startup": n == 1, "big": calls == 1 and n == 1,
                "listing-fails": n == 1, "before-class": (c, m) == ("p.C002", "m1")}.get(sc, False):
            print("dies in a test", file=sys.stderr)
            sys.exit(134)
        block(c, m, n + 1, 0)
    print("INSTRUMENTATION_CODE: -1")
    if sc == "at-exit":
        sys.exit(134)


# scenario: (exit status, apksim processes, entries, failed entries)
SCENARIOS = {
    "startup": (1, 1, 1, ["(run)#incomplete"]),
    "during-test": (1, 3, 9, ["p.C000#m1"]),
    "between-tests": (1, 3, 10, ["(run)#incomplete"]),
    "at-exit": (1, 1, 10, ["(run)#incomplete"]),
    "rerun-startup": (1, 3, 9, ["p.C000#m1"] + [f"p.C00{c}#m{m}" for c in range(3) for m in range(3)][2:]),
    "before-class": (1, 3, 7, ["p.C001#null", "p.C002#m1"]),
    "listing-fails": (1, 2, 3, ["(listing)#incomplete", "p.C000#m1"]),
    "big": (1, 3, 3000, ["com.example.averylongpackagenamefortheargumentlimit.feature.C000#m1"]),
}


def main():
    ok = True
    for sc, (status, processes, entries, failed) in SCENARIOS.items():
        with tempfile.TemporaryDirectory() as d:
            xml = os.path.join(d, "junit.xml")
            env = dict(os.environ, CHECK_SCENARIO=sc, CHECK_DIR=d)
            r = subprocess.run([sys.executable, os.path.join(HERE, "instrument.py"), "--apksim",
                                f"{sys.executable} {os.path.abspath(sys.argv[0])} --fake", "app.apk", "test.apk",
                                "--xml", xml, "-e", "other", "kept"], env=env, capture_output=True, text=True)
            calls = open(os.path.join(d, "calls")).read().split()
            cases = [(c.get("classname"), c.get("name"), c.find("failure") is not None)
                     for c in ET.parse(xml).iter("testcase")] if os.path.exists(xml) else []
            got_failed = sorted(f"{c}#{m}" for c, m, f in cases if f)
            names = [(c, m) for c, m, _ in cases]
            longest = max(int(n) for n in calls[1::2])
            problems = [p for p, bad in [
                (f"exit {r.returncode}, expected {status}", r.returncode != status),
                (f"{len(calls) // 2} processes, expected {processes}", len(calls) // 2 != processes),
                (f"{len(cases)} entries, expected {entries}", len(cases) != entries),
                ("an entry twice", len(set(names)) != len(names)),
                (f"failed {got_failed}, expected {sorted(failed)}", got_failed != sorted(failed)),
                (f"an argument of {longest} characters", longest > 100_000),
            ] if bad]
            print(f"{'ok  ' if not problems else 'FAIL'} {sc}: " + ("; ".join(problems) or r.stdout.strip()))
            if problems:
                ok = False
                sys.stderr.write(r.stderr[-3000:])
    sys.exit(0 if ok else 1)


if len(sys.argv) > 1 and sys.argv[1] == "--fake":
    fake()
else:
    main()
