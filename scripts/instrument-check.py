#!/usr/bin/env python3
"""Checks instrument.py's handling of a test process that dies mid-run, against a stand-in for apksim.

Usage: instrument-check.py. No apksim, APK or JVM needed: instrument.py runs with `--apksim "instrument-check.py
--fake"`, and this script then plays apksim --instrument, writing `am instrument -r` output for a made-up suite
(classes p.C000.., methods m0..) and dying where the scenario (CHECK_SCENARIO) says. It honours -e class, notClass,
log true and numShards/shardIndex as apksim does, and counts its calls in CHECK_DIR. Each scenario checks the exit status, the processes
started, and the JUnit XML (one entry per test, the failures expected). The real thing is ProcessDeathTest, run with
`-e crashOnPurpose true` (CI's linux-smoke job). The hang-* scenarios check --test-timeout: the stand-in stops writing
and stays alive (with a child, and a JVM-style thread dump on SIGQUIT), and every process it started must be gone at
the end; *-docker ones run it through a stand-in `docker run` whose "container" is outside instrument.py's process
group, as a real one is, and so ends only by `docker rm -f`; interrupt-* send instrument.py a SIGINT (Ctrl-C) instead;
init-error-* list a class's initializationError, which --isolate and list-based shards leave out (--keep-class-errors).
The real thing is ProcessDeathTest with `-e hangOnPurpose true` and a --test-timeout (CI's linux-smoke job).
"""
import fcntl, gzip, json, math, os, re, signal, subprocess, sys, tempfile, threading, time
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
    if "--pooled" in args:  # a process of --prewarm's pool: boots, then reads its test's -e pairs on stdin
        with open(os.path.join(state, "pooled"), "a+") as f:  # (locked: each process its own ordinal)
            fcntl.flock(f, fcntl.LOCK_EX)
            f.write(f"{os.getpid()}\n")
            f.flush()
            f.seek(0)
            n = len(f.read().split())
            fcntl.flock(f, fcntl.LOCK_UN)
        if sc == "pool-dies-waiting" and n == 2:
            print("dies while waiting for its test", file=sys.stderr)
            sys.exit(134)
        if sc == "pool-hangs-waiting" and n == 2:
            hang(state, "p.Pool", "boot")
        data = sys.stdin.buffer.read()
        if not data:  # not needed: gone without a word, as TestPool does
            with open(os.path.join(state, "pool-unused"), "a") as f:
                f.write(f"{os.getpid()}\n")
            return
        parts = data.decode("utf-8").split("\0")
        e.update(zip(parts[0:-1:2], parts[1::2]))
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
    if sc.startswith(("init-error-only", "init-error-incomplete")):  # nothing but p.Empty's initializationError
        universe = []
    keep, drop = selectors(e.get("class", "")), selectors(e.get("notClass", ""))
    # init-error-*: p.Empty, a Parameterized class with no parameters, lists as one initializationError and runs nothing.
    empty = ("p.Empty", "initializationError")
    lists_empty = sc.startswith("init-error") and (not keep or empty[0] in keep or "#".join(empty) in keep)
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
        for n, (c, m) in enumerate(tests + ([empty] if lists_empty else [])):
            if (c, m) in IGNORED_TESTS.get(sc, ()) and e.get("apksimListIgnored") == "true":
                block(c, m, n + 1, 1)
                block(c, m, n + 1, -3)  # as Instrument's listing reports an @Ignore'd test when asked
                continue
            block(c, m, n + 1, 1)
            block(c, m, n + 1, 0)
        if sc.startswith("init-error-incomplete"):  # the listing dies before its end
            sys.exit(3)
        print("INSTRUMENTATION_CODE: -1")
        return
    if sc.startswith("init-error") and keep == ["#".join(empty)]:  # no method matches: no result at all
        print("No tests found matching class [p.Empty]", file=sys.stderr)
        sys.exit(1)
    if sc == "startup" or (sc == "rerun-startup" and calls > 1):
        print("dies at startup", file=sys.stderr)
        sys.exit(1)
    if sc == "hang-startup":
        hang(state, "p.Startup", "boot")
    for n, (c, m) in enumerate(tests):
        if sc in ("before-class", "before-class-only") and c == "p.C001":
            if m == "m0":
                block(c, "null", n + 1, 1)
                block(c, "null", n + 1, -2, "java.lang.IllegalStateException: @BeforeClass failed")
            continue
        if sc == "between-tests" and calls == 1 and n == 2:
            print("dies between tests", file=sys.stderr)
            sys.exit(134)
        block(c, m, n + 1, 1)
        if (c, m) in IGNORED_TESTS.get(sc, ()):
            with open(os.path.join(state, "ignored-ran"), "a") as f:
                f.write(f"{c}#{m}\n")
            block(c, m, n + 1, -3)
            continue
        with open(os.path.join(state, "ran"), "a") as f:
            f.write(f"{c}#{m}\n")
        if sc.startswith(("hang", "interrupt", "docker", "cidfile-hang")) and (c, m) == ("p.C000", "m1") and ran_before(state, f"{c}#{m}"):
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
                "non-ascii": calls == 1 and n == 1, "pool-dies-in-test": (c, m) == ("p.C000", "m1"),
                # Shards run at once, so not by call count: the first time this test runs (in shard 0 of 3, first).
                "sharded-crash": (c, m) == ("p.C001", "m1") and ran_before(state, f"{c}#{m}"),
                "listing-fails": n == 1, "parameterized": calls == 1 and n == 0, "before-class": (c, m) == ("p.C002", "m1")}.get(sc, False):
            print("dies in a test", file=sys.stderr)
            sys.exit(134)
        block(c, m, n + 1, 0)
    print("INSTRUMENTATION_CODE: -1")
    if sc == "reap-early-hang":  # reports its status as apksim does (-Dapksim.exitMarks), then a teardown that never ends
        print("APKSIM_EXIT: 0", flush=True)
        child = subprocess.Popen(["sleep", "300"])  # (holds the pipes too, as a child of the launcher could)
        with open(os.path.join(state, "pids"), "a") as f:
            f.write(f"{os.getpid()}\n{child.pid}\n")
        time.sleep(300)
    if sc == "at-exit":
        sys.exit(134)


def hang(state, c, m):
    """A test that never ends, in a process that stays alive (and has a child) until it is killed. SIGQUIT prints a
    thread dump on stdout, as the JVM does: the test's thread, in JUnit code, and one that is not."""
    child = subprocess.Popen(["sleep", "300"])
    with open(os.path.join(state, "pids"), "a") as f:
        f.write(f"{os.getpid()}\n{child.pid}\n")

    sc = os.environ["CHECK_SCENARIO"]
    deep = sc in ("hang-long-stack", "hang-deep-cause", "hang-long-frame", "hang-long-frame-log",
                  "hang-long-frame-cause", "hang-long-message")
    # hang-long-chars: the first JUnit thread's 90 lines are over 45,000 characters (long qualified names).
    wide = sc == "hang-long-chars"
    if wide:
        deep_c = "".join(f"\tat org.robolectric.{'x' * 500}.frame{k}(Deep.java:{k})\n" for k in range(90))
    # hang-long-stack: stacks deeper than the text kept of them (issue 115), whose tops must survive the cut.
    bottom = "".join(f"\tat org.robolectric.Deep.frame{k}(Deep.java:{k})\n" for k in range(200)) if deep else ""

    def dump(*_):
        if wide:
            print(f'Full thread dump Fake VM:\n\n"main" #1 prio=5\n\tat {c}.{m}({c}.java:1)\n{deep_c}'
                  f'\tat org.junit.runners.ParentRunner.run(ParentRunner.java:1)\n\nJNI global refs: 1', flush=True)
            return
        print(f'Full thread dump Fake VM:\n\n"main" #1 prio=5\n   java.lang.Thread.State: TIMED_WAITING\n'
              f'\tat {c}.{m}({c}.java:1)\n{bottom}\tat org.junit.runners.ParentRunner.run(ParentRunner.java:1)\n\n'
              + (f'"SDK 36 Main Thread" #3 prio=5\n   java.lang.Thread.State: WAITING\n\tat p.Waiter.parked(Waiter.java:1)\n'
                 f'{bottom}\tat org.junit.runners.ParentRunner.run(ParentRunner.java:2)\n\n' if deep else "")
              + f'"Reference Handler" #2 daemon\n   java.lang.Thread.State: RUNNABLE\n\nJNI global refs: 1, weak refs: 0',
              flush=True)
    signal.signal(signal.SIGQUIT, dump)
    if deep:  # a stack on stderr longer than the tail kept of it, then quiet: the tail starts inside it
        if sc == "hang-deep-cause":  # a deep wrapper, then a one-frame cause
            print("noise before\njava.lang.RuntimeException: wrapper\n\tat p.Waiter.wrap(Waiter.java:7)\n" + bottom
                  + "Caused by: java.io.IOException: root cause message\n\tat p.Root.thrown(Root.java:9)\n",
                  file=sys.stderr, end="", flush=True)
        elif sc in ("hang-long-frame", "hang-long-frame-log"):  # the stack ends in one frame longer than the tail
            print("noise before\njava.lang.IllegalStateException: listener waited here\n\tat p.Waiter.stuckHere(Waiter.java:7)\n"
                  + bottom + f"\tat p.{'G' * 5000}.last(Gen.java:1)\n"
                  + ("[apksim] a short line after the stack\n" if sc == "hang-long-frame-log" else ""),
                  file=sys.stderr, end="", flush=True)
        elif sc == "hang-long-frame-cause":  # a deep wrapper ending in an oversized frame, then its cause, whose
            # second frame is oversized too, and a short line after the stack
            print("noise before\njava.lang.RuntimeException: wrapper\n\tat p.Waiter.wrap(Waiter.java:7)\n" + bottom
                  + f"\tat p.{'G' * 5000}.last(Gen.java:1)\n"
                  + "Caused by: java.io.IOException: root cause message\n\tat p.Root.thrown(Root.java:9)\n"
                  + f"\tat p.{'H' * 5000}.cause(Gen.java:2)\n[apksim] a short line after the stack\n",
                  file=sys.stderr, end="", flush=True)
        elif sc == "hang-long-message":  # a message of several lines (a VerifyError's details), then a deep stack
            print("noise before\n" + LONG_MESSAGE + "\tat p.Waiter.stuckHere(Waiter.java:7)\n" + bottom,
                  file=sys.stderr, end="", flush=True)
        else:
            print("noise before\njava.lang.IllegalStateException: listener waited here\n\tat p.Waiter.stuckHere(Waiter.java:7)\n"
                  + bottom, file=sys.stderr, end="", flush=True)
    else:
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
        if os.environ["CHECK_SCENARIO"] == "interrupt-docker-cleanup":
            time.sleep(1)  # a slow stop, for the second SIGINT to land in
        with open(os.path.join(state, "docker-kills"), "a") as f:
            f.write(args[2] + "\n")
        os.killpg(int(args[2]), signal.SIGKILL)  # every process in the container
        return
    i, cidfile = 1, None
    while args[i].startswith("-"):
        if args[i] == "--cidfile":
            cidfile = args[i + 1]
        i += 2 if args[i] in ("--cidfile", "-v") else 1
    # (stdin reaches the container only with -i, as docker's: a pooled process needs it)
    child = subprocess.Popen([sys.executable, os.path.abspath(sys.argv[0]), "--fake"] + args[i + 1:],
                             stdin=None if "-i" in args[:i] else subprocess.DEVNULL,
                             start_new_session=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def relay(src, dst):
        while True:
            b = os.read(src, 65536)
            if not b:
                return
            os.write(dst, b)
            if dst == 2 and b"hangs in a test" in b and os.environ["CHECK_SCENARIO"] in ("docker-client-dies", "interrupt-docker-cleanup"):
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
    # C001's @BeforeClass fails and the run ends: one Class#null for its 3 tests is no shortfall (no "(run)", no listing).
    "before-class-only": (1, 1, 7, ["p.C001#null"]),
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
    # --isolate --shards 3 --shard 0 --timings: the same split, shard 0 (a 5 s test, C002's, listed after the 1 s ones, and two
    # 1 s ones) run a process a test, -j 1, the 5 s test first: a listing, then 3 processes.
    "balanced-isolate": (0, 4, 3, []),
    # The same, with distinct times and --order: the launch order is checked in full (interleave is the default).
    "balanced-isolate-longest": (0, 4, 3, []),
    "balanced-isolate-suite": (0, 4, 3, []),
    "balanced-big": (0, 5, 3000, []),
    # --affected-by: a change to C000.kt runs C000's tests and C002's, which the deps file has no record of.
    "affected": (0, 2, 6, []),
    # The same 6 tests over 8 shards, running shard 7 only: an empty job of a CI matrix passes, after the listing.
    "affected-empty-shard": (0, 1, 0, []),
    # --test-timeout 2: C000#m1 hangs the first time it runs. As during-test, with "timed out" for the reason.
    "hang": (1, 3, 9, ["p.C000#m1"]),
    "hang-startup": (1, 1, 1, ["(run)#incomplete"]),
    # As hang, the stacks on stderr and in the thread dump deeper than what is kept: each kept from its header down.
    "hang-long-stack": (1, 3, 9, ["p.C000#m1"]),
    "hang-long-chars": (1, 3, 9, ["p.C000#m1"]),  # the first JUnit thread alone is over the character limit
    "hang-long-frame": (1, 3, 9, ["p.C000#m1"]),  # the stack's last frame alone is longer than the tail kept
    "hang-deep-cause": (1, 3, 9, ["p.C000#m1"]),  # a wrapper deeper than the lines kept, then a cause of one frame
    "hang-long-frame-log": (1, 3, 9, ["p.C000#m1"]),  # as hang-long-frame, then a short line after the stack
    "hang-long-frame-cause": (1, 3, 9, ["p.C000#m1"]),  # as hang-deep-cause, with oversized frames and a line after
    "hang-long-message": (1, 3, 9, ["p.C000#m1"]),  # an exception message of several lines, then a deep stack
    "hang-sharded": (1, 5, 9, ["p.C000#m1"]),  # C000#m1 is in shard 2 (of 3): it lists, and reruns C002#m2
    # --isolate: every test's JUnit entry says how long its process took to start and to exit.
    "isolate-phases": (0, 10, 9, []),
    # isolate-ignored: p.C001#m1 and p.C002's three tests are @Ignore'd: the listing reports them ignored, and they
    # get no process (a listing and 5 processes).
    "isolate-ignored": (0, 6, 9, []),
    # --run-ignored: each gets its process, as before (a listing and 9 processes), and is reported ignored by it.
    "isolate-ignored-run": (0, 10, 9, []),
    # --reap-early 2: every process reports its status and then hangs in its teardown. The slots free at once, the
    # processes (and their children) are killed after 2 s, and the script waits for that before it ends.
    "reap-early-hang": (0, 10, 9, []),
    "hang-isolate": (1, 10, 9, ["p.C000#m1"]),  # a listing, then a process a test
    "hang-docker": (1, 3, 9, ["p.C000#m1"]),
    # --isolate's pool (--prewarm, on by default with --isolate; isolate-phases and the other --isolate scenarios use it
    # too): the second pooled process to start (which test gets it is a race: "*", any one test) dies before it is handed its test
    # (the test fails as one whose process died at start-up; the dead one never records a call), hangs before reading it
    # (--test-timeout ends it), or dies in the test; pool-off runs isolate-phases cold; pool-docker through `docker run`
    # (which must get -i); interrupt-pool stops a run while pooled processes wait.
    "pool-dies-waiting": (1, 9, 9, ["*"]),
    "pool-hangs-waiting": (1, 9, 9, ["*"]),
    "pool-dies-in-test": (1, 10, 9, ["p.C000#m1"]),
    "pool-off": (0, 10, 9, []),
    "pool-docker": (0, 10, 9, []),
    "pool-deep": (0, 10, 9, []),
    "interrupt-pool": (130, tuple(range(2, 11)), 0, []),
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
    # As docker-client-dies, with INSTRUMENT_CHECK_CLEANUP_DELAY holding the main thread in that container's cleanup
    # (in its kill lock) when the SIGINT comes, whose handler then kills the same process; and a second SIGINT while
    # that stop's `rm -f` runs.
    "interrupt-docker-cleanup": (130, 1, 0, []),
    # A --cidfile of the user's in --apksim (the stand-in docker refuses one that exists, as docker does): over 2 shards
    # at once, and with a hang (--test-timeout 2) whose relaunch must start. The file gets the last container's ID.
    "cidfile-sharded": (0, 2, 9, []),
    "cidfile-hang": (1, 3, 9, ["p.C000#m1"]),
    # init-error-*: the listing also has p.Empty#initializationError (a Parameterized class with no parameters), which
    # the orchestrator's runner side drops. --isolate: a listing, then a process for each of the 9 real tests.
    "init-error-isolate": (0, 10, 9, []),
    # --keep-class-errors: p.Empty#initializationError gets its process too, which ends without a result.
    "init-error-isolate-keep": (1, 11, 10, ["p.Empty#initializationError"]),
    # The listing has nothing else: a run of no tests, which passes; kept, its one process fails.
    "init-error-only-isolate": (0, 1, 0, []),
    "init-error-only-isolate-keep": (1, 2, 1, ["p.Empty#initializationError"]),
    # The same listing dying before its end still fails the run.
    "init-error-incomplete-isolate": (1, 1, 1, ["(listing)#incomplete"]),
    # The same over --shards 3 --timings (shards made from the listing): a listing, then 3 shards.
    "init-error-balanced": (0, 4, 9, []),
    "init-error-balanced-keep": (0, 4, 9, []),  # p.Empty is named in a shard's -e class, and runs nothing there
    "init-error-only-balanced": (0, 1, 0, []),
    "init-error-incomplete-balanced": (1, 1, 0, []),  # "listing the suite failed", and no report
}
IGNORED_TESTS = {sc: {("p.C001", "m1"), ("p.C002", "m0"), ("p.C002", "m1"), ("p.C002", "m2")}
                 for sc in ("isolate-ignored", "isolate-ignored-run")}
SHARDS = {"sharded": 3, "sharded-crash": 3, "balanced": 3, "balanced-big": 2, "hang-sharded": 3, "interrupt-parallel": 3,
          "cidfile-sharded": 2, "init-error-balanced": 3, "init-error-balanced-keep": 3, "init-error-only-balanced": 3,
          "init-error-incomplete-balanced": 3}
# When an interrupt-* scenario sends its SIGINT: once a process hangs, or, for these, once the process being started
# has noted its call (its first act), well inside the spawn delay.
INTERRUPT_WHEN = {"interrupt-spawn": lambda d: os.path.exists(os.path.join(d, "calls")),
                  "interrupt-parallel": lambda d: "list" in read(os.path.join(d, "calls"))}
# Seconds from ready to the SIGINT, and to a second one (None: none).
INTERRUPT_AFTER = {"interrupt-spawn": (0.1, None), "interrupt-parallel": (0.1, None),
                   "interrupt-docker-cleanup": (1.0, 0.3)}  # (the client dies 0.5 s after the hang; cleanup holds 2 s)
# The failure text expected of a test that timed out: the reason, and the thread dump's JUnit thread (not the other).
TIMED_OUT = ["timed out after 2 s", "at p.C000.m1(p.C000.java:1)", "hangs in a test"]
# hang-long-stack's: the stderr stack's header and top frame, its bottom cut, and the second JUnit thread's top.
LONG_STACK = ["java.lang.IllegalStateException: listener waited here\n\tat p.Waiter.stuckHere(Waiter.java:7)\n",
              "more lines of this stack]", '"SDK 36 Main Thread" #3', "at p.Waiter.parked(Waiter.java:1)"]
# hang-deep-cause's: the wrapper's header, and the cause's header and frame past the wrapper's cut lines.
DEEP_CAUSE = ["java.lang.RuntimeException: wrapper\n", "more lines of this stack]",
              "Caused by: java.io.IOException: root cause message\n\tat p.Root.thrown(Root.java:9)\n"]
# hang-long-message's exception: its header and message, every line of which must be kept, before the frames.
LONG_MESSAGE = ("java.lang.VerifyError: Bad type on operand stack\nException Details:\n  Location:\n"
                "    p/Waiter.stuckHere()V @5: invokevirtual\n  Reason:\n"
                "    Type 'java/lang/Object' (current frame, stack[0]) is not assignable to 'p/Waiter'\n"
                "  Current Frame:\n    bci: @5\n    flags: { }\n")


def extra_args(sc, d):
    """Scenario arguments that need files: timings XML, a deps file."""
    if sc in ("pool-dies-waiting", "pool-hangs-waiting"):
        return ["--isolate", "-j", "1", "--test-timeout", "2"]
    if sc in ("pool-dies-in-test", "pool-docker", "interrupt-pool"):
        return ["--isolate", "-j", "2"] + (["--test-timeout", "0"] if sc == "interrupt-pool" else [])
    if sc in ("pool-off", "pool-deep"):
        return ["--isolate", "-j", "3", "--prewarm", "off" if sc == "pool-off" else "5"]
    if sc == "isolate-phases":
        return ["--isolate", "-j", "3"]
    if sc.startswith("isolate-ignored"):
        return ["--isolate", "-j", "3"] + (["--run-ignored"] if sc.endswith("-run") else [])
    if sc == "reap-early-hang":
        return ["--isolate", "-j", "3", "--reap-early", "2", "--exit-marks"]
    if sc.startswith("init-error") and "isolate" in sc:
        return ["--isolate", "-j", "3"] + (["--keep-class-errors"] if sc.endswith("-keep") else [])
    if "balanced" in sc:
        xml = os.path.join(d, "timings.xml")
        with open(xml, "w") as f:
            f.write("<testsuite>" + "".join(f'<testcase classname="p.C{c:03d}" name="m{m}" time="{(5 if c == (2 if sc.startswith("balanced-isolate") else 0) else 1) + (0.1 * (3 * c + m) if sc.startswith("balanced-isolate") else 0)}"/>'
                                            for c in range(3) for m in range(3)) + "</testsuite>")
        return (["--timings", xml] + (["--keep-class-errors"] if sc.endswith("-keep") else [])
                + (["--isolate", "-j", "1", "--shards", "3", "--shard", "0"] if sc.startswith("balanced-isolate") else [])
                + (["--order", sc.split("-")[-1] + ("-first" if sc.endswith("longest") else "")] if sc in ("balanced-isolate-longest", "balanced-isolate-suite") else []))
    if sc.startswith("interrupt"):
        return ["--test-timeout", "0"]
    if sc.startswith("hang") or sc in ("slow", "cidfile-hang"):
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
    only = sys.argv[1:]  # scenarios named on the command line run alone
    for sc, (status, processes, entries, failed) in SCENARIOS.items():
        if only and sc not in only:
            continue
        with tempfile.TemporaryDirectory() as d:
            xml = os.path.join(d, "junit.xml")
            env = dict(os.environ, CHECK_SCENARIO=sc, CHECK_DIR=d)
            if sc in INTERRUPT_WHEN:
                env["INSTRUMENT_CHECK_SPAWN_DELAY"] = "1.5"
            if sc == "interrupt-docker-cleanup":
                env["INSTRUMENT_CHECK_CLEANUP_DELAY"] = "2"
            launcher = f"{sys.executable} {os.path.abspath(sys.argv[0])} --fake"
            if "docker" in sc or sc.startswith("cidfile"):  # (pool-docker too)
                os.makedirs(os.path.join(d, "bin"))
                docker = os.path.join(d, "bin", "docker")
                with open(docker, "w") as f:
                    f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.abspath(sys.argv[0])}" --fake-docker "$@"\n')
                os.chmod(docker, 0o755)
                launcher = f"{docker} run --rm -v {d}:/work apksim:check"
                if sc.startswith("cidfile"):
                    launcher = f"{docker} run --cidfile {d}/user.cid --rm -v {d}:/work apksim:check"
            start = time.time()
            xargs = extra_args(sc, d)
            p = subprocess.Popen([sys.executable, os.path.join(HERE, "instrument.py"), "--apksim", launcher,
                                  "app.apk", "test.apk", "--xml", xml, "-e", "other", "kept"]
                                 + (["--shards", str(SHARDS[sc])] if sc in SHARDS else []) + xargs,
                                 env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            if sc.startswith("interrupt"):
                ready = INTERRUPT_WHEN.get(sc, lambda d: os.path.exists(os.path.join(d, "pids")))
                while p.poll() is None and not ready(d) and time.time() - start < 20:
                    time.sleep(0.02)
                first, second = INTERRUPT_AFTER.get(sc, (0.3, None))
                time.sleep(first)
                p.send_signal(signal.SIGINT)
                if second is not None:
                    time.sleep(second)
                    if p.poll() is None:
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
            skipped = sorted(f"{c.get('classname')}#{c.get('name')}" for c in ET.parse(xml).iter("testcase")
                             if c.find("skipped") is not None) if os.path.exists(xml) else []
            cases = [(c.get("classname"), c.get("name"), c.find("failure") is not None,
                      (c.find("failure").text or "") if c.find("failure") is not None else "")
                     for c in ET.parse(xml).iter("testcase")] if os.path.exists(xml) else []
            texts = {f"{c}#{m}": t for c, m, _, t in cases}
            props = {f"{c.get('classname')}#{c.get('name')}": {q.get("name") for q in c.iter("property")}
                     for c in ET.parse(xml).iter("testcase")} if os.path.exists(xml) else {}
            cases = [(c, m, f) for c, m, f, _ in cases]
            got_failed = sorted(f"{c}#{m}" for c, m, f in cases if f)
            if failed == ["*"] and len(got_failed) == 1 and not got_failed[0].startswith("("):
                failed = got_failed  # (one test, whichever)
            hung = ([t for t in ("p.C000#m1", "(run)#incomplete") if t in failed]
                    if sc.startswith(("hang", "cidfile-hang")) else [])
            # (A client that ends at the SIGQUIT passes on no thread dump.)
            wanted = TIMED_OUT if "client-exits" not in sc else [w for w in TIMED_OUT if "java:" not in w]
            if sc == "hang-long-stack":
                wanted = TIMED_OUT[:2] + LONG_STACK
            elif sc == "hang-long-frame":
                wanted = TIMED_OUT[:2] + LONG_STACK[:2]
            elif sc == "hang-long-frame-log":
                wanted = TIMED_OUT[:2] + LONG_STACK[:2] + ["[apksim] a short line after the stack\n"]
            elif sc == "hang-deep-cause":
                wanted = TIMED_OUT[:1] + DEEP_CAUSE
            elif sc == "hang-long-frame-cause":
                wanted = TIMED_OUT[:1] + DEEP_CAUSE + ["\tat p.Waiter.wrap(Waiter.java:7)\n",
                                                       "[apksim] a short line after the stack\n"]
            elif sc == "hang-long-message":
                wanted = TIMED_OUT[:2] + [LONG_MESSAGE + "\tat p.Waiter.stuckHere(Waiter.java:7)\n",
                                          "more lines of this stack]"]
            elif sc == "hang-long-chars":
                wanted = TIMED_OUT[:1] + ["more lines of this stack]"]
            missing = [w for t in hung for w in (wanted if t == "p.C000#m1" else wanted[:1]) if w not in texts.get(t, "")]
            pids_file = os.path.join(d, "pids")
            pids = [int(n) for n in open(pids_file).read().split()] if os.path.exists(pids_file) else []
            pooled = [int(n) for n in read(os.path.join(d, "pooled")).split()]
            unused = read(os.path.join(d, "pool-unused")).split()
            isolated = "--isolate" in xargs and "off" not in xargs
            pids += [n for n in pooled if n not in pids]  # (every pooled process must be gone too, used or not)
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
            expected_order = None
            if sc.startswith("balanced-isolate") and ran:
                tm = {(c, m): float(t) for c, m, t in re.findall(r'classname="([^"]+)" name="([^"]+)" time="([^"]+)"', open(os.path.join(d, "timings.xml")).read())}
                got = [tuple(x.split("#")) for x in ran]
                srt = sorted(got, key=lambda t: -tm[t])  # longest first
                suite = sorted(got)
                if sc.endswith("longest"):
                    expected_order = srt
                elif sc.endswith("suite"):
                    expected_order = suite
                else:  # the default: alternately the longest and the shortest
                    expected_order, lo, hi = [], 0, len(srt) - 1
                    while lo <= hi:
                        expected_order.append(srt[lo]); lo += 1
                        if lo <= hi:
                            expected_order.append(srt[hi]); hi -= 1
                expected_order = ["#".join(t) for t in expected_order]
            problems = [p for p, bad in [
                (f"exit {r.returncode}, expected {status}", r.returncode != status),
                (f"{len(calls) // 2} processes, expected {processes}", len(calls) // 2 not in (processes if isinstance(processes, tuple) else (processes,))),
                (f"{len(cases)} entries, expected {entries}", len(cases) != entries),
                ("an entry twice", len(set(names)) != len(names)),
                ("a test run twice", len(set(ran)) != len(ran)),
                (f"ran {ran}, expected the 5 s test first", sc == "balanced-isolate" and not ran[0].startswith("p.C002#")),
                (f"ran {ran}, expected {expected_order}", bool(expected_order) and ran != expected_order),
                (f"failed {got_failed}, expected {sorted(failed)}", got_failed != sorted(failed)),
                (f"an argument of {longest} bytes", longest > 100_000),
                (f"the failure text lacks {missing}", bool(missing)),
                ("the stderr tail before the thread dump's JUnit threads", sc == "hang-long-stack" and not missing
                 and texts["p.C000#m1"].find("at p.C000.m1(") > texts["p.C000#m1"].find("listener waited here")),
                ("the first thread's text over 30,000 characters", sc == "hang-long-chars"
                 and len(texts["p.C000#m1"].split("\n\nthe end of the process")[0]) > 31_000),
                (f"processes left running: {alive}", bool(alive)),
                ("no process that hung", sc.startswith(("hang", "interrupt", "docker", "cidfile-hang")) and not pids),
                ("no container ID in the user's --cidfile", sc.startswith("cidfile")
                 and not read(os.path.join(d, "user.cid")).strip().isdigit()),
                ("not ended by docker rm -f", ("docker" in sc and sc != "pool-docker" or sc == "cidfile-hang") and not os.path.exists(os.path.join(d, "docker-kills"))),
                ("no process.txt saying it timed out", sc == "hang" and not any(
                    "timed out after 2 s" in open(f).read() for f in process_txt if os.path.exists(f))),
                ("p.Empty's warning " + ("printed though kept" if sc.endswith("-keep") else "missing"),
                 sc.startswith("init-error") and sc != "init-error-incomplete-balanced"  # (that one stops at the listing)
                 and ("leaving out p.Empty#initializationError" in r.stderr) == sc.endswith("-keep")),
                ("an entry for p.Empty", sc.startswith("init-error") and not sc.endswith("isolate-keep")
                 and any(c == "p.Empty" for c, _ in names)),
                ("start-up and exit seconds missing", sc == "isolate-phases" and any(
                    {"apksim.startup_seconds", "apksim.exit_seconds"} - v for v in props.values())),
                (f"skipped {skipped}, expected the 4 @Ignore'd tests", sc.startswith("isolate-ignored")
                 and skipped != sorted(f"{c}#{m}" for c, m in IGNORED_TESTS[sc])),
                ("an @Ignore'd test given a process", sc == "isolate-ignored"
                 and os.path.exists(os.path.join(d, "ignored-ran"))),
                ("no process for each @Ignore'd test", sc == "isolate-ignored-run"
                 and len(read(os.path.join(d, "ignored-ran")).split()) != 4),
                ("no teardown that hung", sc == "reap-early-hang" and len(pids) < 18),
                (f"took {took:.0f} s", took > (30 if sc != "reap-early-hang" else 20)),
                ("a pooled process with --prewarm off", sc == "pool-off" and bool(pooled)),
                (f"{len(pooled) - len(unused)} pooled processes given a test, expected one a test",
                 isolated and not sc.startswith(("interrupt", "pool-dies-waiting", "pool-hangs-waiting", "init-error-incomplete"))
                 and len(pooled) - len(unused) != max(0, len(calls) // 2 - 1)),
                ("no pooled process", isolated and sc != "init-error-incomplete-isolate" and not pooled),
                (f"{len(unused)} pooled processes closed unused, expected 6", sc == "init-error-only-isolate" and len(unused) != 6),
                ("the failure text lacks the dying process's stderr", sc == "pool-dies-waiting"
                 and not any("dies while waiting for its test" in t for t in texts.values())),
                ("the failure text lacks the timeout", sc == "pool-hangs-waiting"
                 and not any("timed out after 2 s" in t for t in texts.values())),
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
