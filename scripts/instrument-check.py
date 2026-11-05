#!/usr/bin/env python3
"""Checks instrument.py's handling of a test process that dies mid-run, against a stand-in for apksim.

Usage: instrument-check.py. No apksim, APK or JVM needed: instrument.py runs with `--apksim "instrument-check.py
--fake"`, and this script then plays apksim --instrument, writing `am instrument -r` output for a made-up suite
(classes p.C000.., methods m0..) and dying where the scenario (CHECK_SCENARIO) says. It honours -e class, notClass
log true and numShards/shardIndex as apksim does, and counts its calls in CHECK_DIR. Each scenario checks the exit status, the processes
started, and the JUnit XML (one entry per test, the failures expected). The real thing is ProcessDeathTest, run with
`-e crashOnPurpose true` (CI's linux-smoke job).
"""
import gzip, json, math, os, re, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(sys.argv[0]))


def selectors(s):
    """-e class/notClass as Instrument.selectorParts splits it (`\\,` and `\\\\` escapes), trimmed, blanks dropped."""
    out, part, i = [], "", 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s) and s[i + 1] in ",\\":
            part, i = part + s[i + 1], i + 1
        elif s[i] == ",":
            out, part = out + [part], ""
        else:
            part += s[i]
        i += 1
    return [p.strip() for p in out + [part] if p.strip()]


def java_hash(s):
    """Java's String.hashCode, which JUnit's Description.hashCode (its display name's) and so a test's shard use."""
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= 1 << 31 else h


def fake():
    args, e, i = sys.argv[2:], {}, 0
    while i < len(args):
        if args[i] == "-e":
            e[args[i + 1]] = args[i + 2]
            i += 3
        else:
            i += 1
    sc, state = os.environ["CHECK_SCENARIO"], os.environ["CHECK_DIR"]
    # The longest argument in UTF-8 bytes (Linux's limit, 131,072, counts bytes), each -e as the launcher passes it to
    # the JVM: one -Dapksim.instrument.KEY=VALUE.
    longest = max([len(f"-Dapksim.instrument.{k}={v}".encode("utf-8")) for k, v in e.items()]
                  + [len(a.encode("utf-8")) for a in args])
    with open(os.path.join(state, "calls"), "a") as f:
        f.write(f"{'list' if e.get('log') == 'true' else 'run'} {longest}\n")
    calls = sum(1 for line in open(os.path.join(state, "calls")) if line.startswith("run"))
    big = sc in ("big", "non-ascii", "balanced-big")
    pre = "com.example.averylongpackagenamefortheargumentlimit.feature." if big else "p."
    if sc == "non-ascii":  # 200 CJK characters a class name: ~60k characters for 299 classes, but ~180k bytes
        pre = "p." + "測試" * 100
    universe = [(f"{pre}C{c:03d}", f"m{m}") for c in range(300 if big else 3) for m in range(10 if big else 3)]
    if sc == "parameterized":  # JUnit Parameterized names: a comma (and here a backslash) in them
        universe = [("p.C000", m) for m in ("m0", "test[0: a, b]", "test[1: c\\d, e]", "m3")] + universe[3:]
    keep, drop = selectors(e.get("class", "")), selectors(e.get("notClass", ""))
    tests = [(c, m) for c, m in universe
             if c not in drop and f"{c}#{m}" not in drop and (not keep or c in keep or f"{c}#{m}" in keep)]
    if "numShards" in e:  # Instrument's shard: abs(description.hashCode() % numShards), Java's sign-of-dividend remainder
        n, i = int(e["numShards"]), int(e["shardIndex"])
        tests = [(c, m) for c, m in tests if abs(int(math.fmod(java_hash(f"{m}({c})"), n))) == i]

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
        with open(os.path.join(state, "ran"), "a") as f:
            f.write(f"{c}#{m}\n")
        if {"during-test": calls == 1 and n == 1, "rerun-startup": n == 1, "big": calls == 1 and n == 1,
                "non-ascii": calls == 1 and n == 1,
                # Shards run at once, so not by call count: the first time this test runs (in shard 0 of 3, first).
                "sharded-crash": (c, m) == ("p.C001", "m1") and ran_before(state, f"{c}#{m}"),
                "listing-fails": n == 1, "parameterized": calls == 1 and n == 0, "before-class": (c, m) == ("p.C002", "m1")}.get(sc, False):
            print("dies in a test", file=sys.stderr)
            sys.exit(134)
        block(c, m, n + 1, 0)
    print("INSTRUMENTATION_CODE: -1")
    if sc == "at-exit":
        sys.exit(134)


def ran_before(state, test):
    """Whether [test] ran only once so far (it was just recorded): a crash that a rerun must not repeat."""
    return open(os.path.join(state, "ran")).read().splitlines().count(test) == 1


# scenario: (exit status, apksim processes, entries, failed entries)
SCENARIOS = {
    "startup": (1, 1, 1, ["(run)#incomplete"]),
    "during-test": (1, 3, 9, ["p.C000#m1"]),
    "between-tests": (1, 3, 10, ["(run)#incomplete"]),
    "at-exit": (1, 1, 10, ["(run)#incomplete"]),
    "rerun-startup": (1, 3, 9, ["p.C000#m1"] + [f"p.C00{c}#m{m}" for c in range(3) for m in range(3)][2:]),
    "before-class": (1, 3, 7, ["p.C001#null", "p.C002#m1"]),
    "listing-fails": (1, 2, 3, ["(listing)#incomplete", "p.C000#m1"]),
    "parameterized": (1, 3, 10, ["p.C000#m0"]),
    "big": (1, 3, 3000, ["com.example.averylongpackagenamefortheargumentlimit.feature.C000#m1"]),
    "non-ascii": (1, 4, 3000, ["p." + "測試" * 100 + "C000#m1"]),
    # --shards 3 (SHARDS): shard 0 is C001#m1, C001#m2, C002#m0; 1 is C000#m2, C001#m0, C002#m1; 2 the rest.
    "sharded": (0, 3, 9, []),
    "sharded-crash": (1, 5, 9, ["p.C001#m1"]),  # shard 0 dies in its first test: it lists, and reruns the other two
    # --timings: a listing, then 3 shards of 7 s each (C000's three 5 s tests apart, the 1 s ones two to a shard).
    "balanced": (0, 4, 9, []),
    # 3,000 tests of long names over 2 shards by time: each shard's -e class list is two arguments' worth.
    "balanced-big": (0, 5, 3000, []),
    # --affected-by: a change to C000.kt runs C000's tests and C002's, which the deps file has no record of.
    "affected": (0, 2, 6, []),
}
SHARDS = {"sharded": 3, "sharded-crash": 3, "balanced": 3, "balanced-big": 2}


def extra_args(sc, d):
    """Scenario arguments that need files: timings XML, a deps file."""
    if sc.startswith("balanced"):
        xml = os.path.join(d, "timings.xml")
        with open(xml, "w") as f:
            f.write("<testsuite>" + "".join(f'<testcase classname="p.C{c:03d}" name="m{m}" time="{5 if c == 0 else 1}"/>'
                                            for c in range(3) for m in range(3)) + "</testsuite>")
        return ["--timings", xml]
    if sc == "affected":
        deps = os.path.join(d, "deps.json.gz")
        doc = {"version": 1, "apks": [], "packages": ["p/"], "named": 1.0, "classes": ["p/C000", "p/C001"],
               "sources": {"p/C000.kt": [0], "p/C001.kt": [1]},
               "tests": {f"p.C00{c}#m{m}": [c] for c in range(2) for m in range(3)}}
        with gzip.open(deps, "wt") as f:
            json.dump(doc, f)
        return ["--affected-by", deps, "--changed", "app/src/main/kotlin/p/C000.kt"]
    return []


def main():
    ok = True
    for sc, (status, processes, entries, failed) in SCENARIOS.items():
        with tempfile.TemporaryDirectory() as d:
            xml = os.path.join(d, "junit.xml")
            env = dict(os.environ, CHECK_SCENARIO=sc, CHECK_DIR=d)
            r = subprocess.run([sys.executable, os.path.join(HERE, "instrument.py"), "--apksim",
                                f"{sys.executable} {os.path.abspath(sys.argv[0])} --fake", "app.apk", "test.apk",
                                "--xml", xml, "-e", "other", "kept"]
                               + (["--shards", str(SHARDS[sc])] if sc in SHARDS else []) + extra_args(sc, d),
                               env=env, capture_output=True, text=True)
            calls = open(os.path.join(d, "calls")).read().split()
            cases = [(c.get("classname"), c.get("name"), c.find("failure") is not None)
                     for c in ET.parse(xml).iter("testcase")] if os.path.exists(xml) else []
            got_failed = sorted(f"{c}#{m}" for c, m, f in cases if f)
            names = [(c, m) for c, m, _ in cases]
            longest = max(int(n) for n in calls[1::2])
            ran_log = os.path.join(d, "ran")
            ran = open(ran_log).read().splitlines() if os.path.exists(ran_log) else []
            problems = [p for p, bad in [
                (f"exit {r.returncode}, expected {status}", r.returncode != status),
                (f"{len(calls) // 2} processes, expected {processes}", len(calls) // 2 != processes),
                (f"{len(cases)} entries, expected {entries}", len(cases) != entries),
                ("an entry twice", len(set(names)) != len(names)),
                ("a test run twice", len(set(ran)) != len(ran)),
                (f"failed {got_failed}, expected {sorted(failed)}", got_failed != sorted(failed)),
                (f"an argument of {longest} bytes", longest > 100_000),
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
