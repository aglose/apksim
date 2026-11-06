#!/usr/bin/env python3
"""Checks instrument.py's handling of a test process that dies mid-run, against a stand-in for apksim.

Usage: instrument-check.py. No apksim, APK or JVM needed: instrument.py runs with `--apksim "instrument-check.py
--fake"`, and this script then plays apksim --instrument, writing `am instrument -r` output for a made-up suite
(classes p.C000.., methods m0..) and dying where the scenario (CHECK_SCENARIO) says. It honours -e class, notClass
log true and numShards/shardIndex as apksim does, and counts its calls in CHECK_DIR. Each scenario checks the exit status, the processes
started, and the JUnit XML (one entry per test, the failures expected). The real thing is ProcessDeathTest, run with
`-e crashOnPurpose true` (CI's linux-smoke job). The hang-* scenarios check --test-timeout: the stand-in stops writing
and stays alive (with a child, and a JVM-style thread dump on SIGQUIT), and every process it started must be gone at
the end; *-docker ones run it through a stand-in `docker run` whose "container" is outside instrument.py's process
group, as a real one is, and so ends only by `docker rm -f`; interrupt-* send instrument.py a SIGINT (Ctrl-C) instead.
The real thing is ProcessDeathTest with `-e hangOnPurpose true` and a --test-timeout (CI's linux-smoke job).
"""
import gzip, json, math, os, re, signal, subprocess, sys, tempfile, threading, time
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

    if sc == "interrupt-spawn":  # slow to start: instrument.py is interrupted while it starts this process
        hang(state, "p.Startup", "boot")
    if e.get("log") == "true":
        if sc == "interrupt-parallel":  # the listing a shard starts after its process died: interrupted as it starts
            hang(state, "p.Listing", "list")
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
    if sc == "hang-startup":
        hang(state, "p.Startup", "boot")
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
        if sc.startswith(("hang", "interrupt", "docker")) and (c, m) == ("p.C000", "m1") and ran_before(state, f"{c}#{m}"):
            hang(state, c, m)
        if sc == "interrupt-parallel":  # shard 0 dies in its first test (C001#m1), and the other two hang in theirs
            if (c, m) == ("p.C001", "m1"):
                print("dies in a test", file=sys.stderr)
                sys.exit(134)
            hang(state, c, m)
        if sc == "slow":  # slower in all than --test-timeout, but each test well within it
            sys.stdout.flush()
            time.sleep(0.6)
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


def hang(state, c, m):
    """A test that never ends, in a process that stays alive (and has a child) until it is killed. SIGQUIT prints a
    thread dump on stdout, as the JVM does: the test's thread, in JUnit code, and one that is not."""
    child = subprocess.Popen(["sleep", "300"])
    with open(os.path.join(state, "pids"), "a") as f:
        f.write(f"{os.getpid()}\n{child.pid}\n")

    def dump(*_):
        print(f'Full thread dump Fake VM:\n\n"main" #1 prio=5\n   java.lang.Thread.State: TIMED_WAITING\n'
              f'\tat {c}.{m}({c}.java:1)\n\tat org.junit.runners.ParentRunner.run(ParentRunner.java:1)\n\n'
              f'"Reference Handler" #2 daemon\n   java.lang.Thread.State: RUNNABLE\n\nJNI global refs: 1, weak refs: 0',
              flush=True)
    signal.signal(signal.SIGQUIT, dump)
    print("hangs in a test", file=sys.stderr, flush=True)
    sys.stdout.flush()
    while True:
        time.sleep(1)


def fake_docker():
    """`docker run [--cidfile F] [--rm] [-v X] IMAGE ARGS...` and `docker rm -f ID`: the "container" (the stand-in apksim)
    runs in a session of its own, so it outlives a kill of this client, as a real container does, and its output
    comes through this client's pipes, so instrument.py sees the end of it when the client ends. In
    hang-docker-client-exits the client dies of the SIGQUIT (no sig-proxy), and in docker-client-dies it is killed by
    something else once the container hangs: either way the container goes on running."""
    args, state = sys.argv[2:], os.environ["CHECK_DIR"]
    if args[:2] == ["rm", "-f"]:
        with open(os.path.join(state, "docker-kills"), "a") as f:
            f.write(args[2] + "\n")
        os.killpg(int(args[2]), signal.SIGKILL)  # every process in the container
        return
    i, cidfile = 1, None
    while args[i].startswith("-"):
        if args[i] == "--cidfile":
            cidfile = args[i + 1]
        i += 2 if args[i] in ("--cidfile", "-v") else 1
    child = subprocess.Popen([sys.executable, os.path.abspath(sys.argv[0]), "--fake"] + args[i + 1:],
                             start_new_session=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def relay(src, dst):
        while True:
            b = os.read(src, 65536)
            if not b:
                return
            os.write(dst, b)
            if dst == 2 and b"hangs in a test" in b and os.environ["CHECK_SCENARIO"] == "docker-client-dies":
                threading.Timer(0.5, os.kill, (os.getpid(), signal.SIGKILL)).start()  # (once stdout is relayed too)
    relays = [threading.Thread(target=relay, args=(child.stdout.fileno(), 1), daemon=True),
              threading.Thread(target=relay, args=(child.stderr.fileno(), 2), daemon=True)]
    for t in relays:
        t.start()
    with open(os.path.join(state, "pids"), "a") as f:
        f.write(f"{child.pid}\n")
    if cidfile:
        with open(cidfile, "x") as f:  # (docker refuses a cidfile that exists)
            f.write(str(child.pid))
    if os.environ["CHECK_SCENARIO"] == "hang-docker-client-exits":
        signal.signal(signal.SIGQUIT, signal.SIG_DFL)
    else:
        signal.signal(signal.SIGQUIT, lambda *_: os.kill(child.pid, signal.SIGQUIT))  # docker's sig-proxy
    code = child.wait()
    for t in relays:
        t.join()
    sys.exit(code % 256)


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
    # The same 6 tests over 8 shards, running shard 7 only: an empty job of a CI matrix passes, after the listing.
    "affected-empty-shard": (0, 1, 0, []),
    # --test-timeout 2: C000#m1 hangs the first time it runs. As during-test, with "timed out" for the reason.
    "hang": (1, 3, 9, ["p.C000#m1"]),
    "hang-startup": (1, 1, 1, ["(run)#incomplete"]),
    "hang-sharded": (1, 5, 9, ["p.C000#m1"]),  # C000#m1 is in shard 2 (of 3): it lists, and reruns C002#m2
    "hang-isolate": (1, 10, 9, ["p.C000#m1"]),  # a listing, then a process a test
    "hang-docker": (1, 3, 9, ["p.C000#m1"]),
    # The client dies of the SIGQUIT, before the kill, its container still running: it must be removed all the same.
    "hang-docker-client-exits": (1, 3, 9, ["p.C000#m1"]),
    # The client is killed (not by instrument.py) while its container hangs: the container must not outlive the run.
    "docker-client-dies": (1, 3, 9, ["p.C000#m1"]),
    "slow": (0, 1, 9, []),  # 0.6 s a test, 5.4 s in all: no kill
    # No limit, and a SIGINT (Ctrl-C) once C000#m1 hangs: the processes have sessions of their own, out of the
    # terminal's reach, so instrument.py must end them itself (and the container), then exit 130 with no report.
    "interrupt": (130, 1, 0, []),
    "interrupt-docker": (130, 1, 0, []),
    # INSTRUMENT_CHECK_SPAWN_DELAY holds instrument.py between starting a process and noting it for a stop, and the
    # SIGINT comes then: on the main thread (one process), and while a shard's worker starts its listing (--shards 3).
    "interrupt-spawn": (130, 1, 0, []),
    "interrupt-parallel": (130, (3, 4), 0, []),  # (a shard may be refused its start once the stop has begun)
}
SHARDS = {"sharded": 3, "sharded-crash": 3, "balanced": 3, "balanced-big": 2, "hang-sharded": 3, "interrupt-parallel": 3}
# When an interrupt-* scenario sends its SIGINT: once a process hangs, or, for these, once the process being started
# has noted its call (its first act), well inside the spawn delay.
INTERRUPT_WHEN = {"interrupt-spawn": lambda d: os.path.exists(os.path.join(d, "calls")),
                  "interrupt-parallel": lambda d: "list" in read(os.path.join(d, "calls"))}
# The failure text expected of a test that timed out: the reason, and the thread dump's JUnit thread (not the other).
TIMED_OUT = ["timed out after 2 s", "at p.C000.m1(p.C000.java:1)", "hangs in a test"]


def extra_args(sc, d):
    """Scenario arguments that need files: timings XML, a deps file."""
    if sc.startswith("balanced"):
        xml = os.path.join(d, "timings.xml")
        with open(xml, "w") as f:
            f.write("<testsuite>" + "".join(f'<testcase classname="p.C{c:03d}" name="m{m}" time="{5 if c == 0 else 1}"/>'
                                            for c in range(3) for m in range(3)) + "</testsuite>")
        return ["--timings", xml]
    if sc.startswith("interrupt"):
        return ["--test-timeout", "0"]
    if sc.startswith("hang") or sc == "slow":
        return (["--test-timeout", "2"] + (["--isolate", "-j", "3"] if sc == "hang-isolate" else [])
                + (["--bundles", os.path.join(d, "bundles")] if sc == "hang" else []))
    if sc.startswith("affected"):
        deps = os.path.join(d, "deps.json.gz")
        doc = {"version": 1, "apks": [], "packages": ["p/"], "named": 1.0, "classes": ["p/C000", "p/C001"],
               "sources": {"p/C000.kt": [0], "p/C001.kt": [1]},
               "tests": {f"p.C00{c}#m{m}": [c] for c in range(2) for m in range(3)}}
        with gzip.open(deps, "wt") as f:
            json.dump(doc, f)
        return (["--affected-by", deps, "--changed", "app/src/main/kotlin/p/C000.kt"]
                + (["--shards", "8", "--shard", "7"] if sc == "affected-empty-shard" else []))
    return []


def main():
    ok = True
    for sc, (status, processes, entries, failed) in SCENARIOS.items():
        with tempfile.TemporaryDirectory() as d:
            xml = os.path.join(d, "junit.xml")
            env = dict(os.environ, CHECK_SCENARIO=sc, CHECK_DIR=d)
            if sc in INTERRUPT_WHEN:
                env["INSTRUMENT_CHECK_SPAWN_DELAY"] = "1.5"
            launcher = f"{sys.executable} {os.path.abspath(sys.argv[0])} --fake"
            if "docker" in sc:
                os.makedirs(os.path.join(d, "bin"))
                docker = os.path.join(d, "bin", "docker")
                with open(docker, "w") as f:
                    f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.abspath(sys.argv[0])}" --fake-docker "$@"\n')
                os.chmod(docker, 0o755)
                launcher = f"{docker} run --rm -v {d}:/work apksim:check"
            start = time.time()
            p = subprocess.Popen([sys.executable, os.path.join(HERE, "instrument.py"), "--apksim", launcher,
                                  "app.apk", "test.apk", "--xml", xml, "-e", "other", "kept"]
                                 + (["--shards", str(SHARDS[sc])] if sc in SHARDS else []) + extra_args(sc, d),
                                 env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            if sc.startswith("interrupt"):
                ready = INTERRUPT_WHEN.get(sc, lambda d: os.path.exists(os.path.join(d, "pids")))
                while p.poll() is None and not ready(d) and time.time() - start < 20:
                    time.sleep(0.02)
                time.sleep(0.3 if sc not in INTERRUPT_WHEN else 0.1)
                p.send_signal(signal.SIGINT)
            try:
                out, err = p.communicate(timeout=60)
            except subprocess.TimeoutExpired:  # (a hang that was not killed: end what it left, and say so)
                p.kill()
                for n in (open(os.path.join(d, "pids")).read().split() if os.path.exists(os.path.join(d, "pids")) else []):
                    try:
                        os.killpg(int(n), signal.SIGKILL)
                    except OSError:
                        pass
                out, err = p.communicate()
            r = subprocess.CompletedProcess(p.args, p.returncode, out, err)
            took = time.time() - start
            calls = open(os.path.join(d, "calls")).read().split()
            cases = [(c.get("classname"), c.get("name"), c.find("failure") is not None,
                      (c.find("failure").text or "") if c.find("failure") is not None else "")
                     for c in ET.parse(xml).iter("testcase")] if os.path.exists(xml) else []
            texts = {f"{c}#{m}": t for c, m, _, t in cases}
            cases = [(c, m, f) for c, m, f, _ in cases]
            got_failed = sorted(f"{c}#{m}" for c, m, f in cases if f)
            hung = [t for t in ("p.C000#m1", "(run)#incomplete") if t in failed] if sc.startswith("hang") else []
            # (A client that ends at the SIGQUIT passes on no thread dump.)
            wanted = TIMED_OUT if "client-exits" not in sc else [w for w in TIMED_OUT if "java:" not in w]
            missing = [w for t in hung for w in (wanted if t == "p.C000#m1" else wanted[:1]) if w not in texts.get(t, "")]
            pids_file = os.path.join(d, "pids")
            pids = [int(n) for n in open(pids_file).read().split()] if os.path.exists(pids_file) else []
            alive = [n for n in pids if still_alive(n)]
            for n in alive:  # (a failure: end them, here only)
                try:
                    os.kill(n, signal.SIGKILL)
                except OSError:
                    pass
            bundle = os.path.join(d, "bundles", "p.C000")
            process_txt = [os.path.join(bundle, b, "process.txt") for b in (os.listdir(bundle) if os.path.isdir(bundle) else [])]
            names = [(c, m) for c, m, _ in cases]
            longest = max(int(n) for n in calls[1::2])
            ran_log = os.path.join(d, "ran")
            ran = open(ran_log).read().splitlines() if os.path.exists(ran_log) else []
            problems = [p for p, bad in [
                (f"exit {r.returncode}, expected {status}", r.returncode != status),
                (f"{len(calls) // 2} processes, expected {processes}", len(calls) // 2 not in (processes if isinstance(processes, tuple) else (processes,))),
                (f"{len(cases)} entries, expected {entries}", len(cases) != entries),
                ("an entry twice", len(set(names)) != len(names)),
                ("a test run twice", len(set(ran)) != len(ran)),
                (f"failed {got_failed}, expected {sorted(failed)}", got_failed != sorted(failed)),
                (f"an argument of {longest} bytes", longest > 100_000),
                (f"the failure text lacks {missing}", bool(missing)),
                (f"processes left running: {alive}", bool(alive)),
                ("no process that hung", sc.startswith(("hang", "interrupt", "docker")) and not pids),
                ("not ended by docker rm -f", "docker" in sc and not os.path.exists(os.path.join(d, "docker-kills"))),
                ("no process.txt saying it timed out", sc == "hang" and not any(
                    "timed out after 2 s" in open(f).read() for f in process_txt if os.path.exists(f))),
                (f"took {took:.0f} s", took > 30),
            ] if bad]
            print(f"{'ok  ' if not problems else 'FAIL'} {sc}: " + ("; ".join(problems) or r.stdout.strip()))
            if problems:
                ok = False
                sys.stderr.write(r.stderr[-3000:])
    sys.exit(0 if ok else 1)


def read(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""


def still_alive(pid):
    """Whether [pid] runs a second from now (a killed process's parent may take a moment to reap it)."""
    for _ in range(10):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        if subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True).stdout.strip().startswith("Z"):
            return False
        time.sleep(0.1)
    return True


if len(sys.argv) > 1 and sys.argv[1] == "--fake":
    fake()
elif len(sys.argv) > 1 and sys.argv[1] == "--fake-docker":
    fake_docker()
else:
    main()
