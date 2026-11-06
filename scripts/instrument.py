#!/usr/bin/env python3
"""Runs an app's androidTest APK on apksim, as `am instrument` (with the AndroidX Test Orchestrator, --isolate).

Usage: instrument.py APP.apk TEST.apk [--isolate] [-j N] [--shards N [--timings XML]... [--shard I]] [--xml FILE]
                     [--record-deps FILE | --affected-by FILE (--changed PATH... | --diff REV) [--ignore GLOB]...]
                     [-e KEY VALUE]... [--apksim CMD] [--bundles DIR [--bundles-local PATH]] [--test-timeout SEC]
                     [-- APKSIM_OPTION...]

Without --isolate every test runs in one process (Robolectric resets the framework and app data between tests, but
the app's own statics carry over). If that process ends before every test has a result (a native crash or abort, or
apksim's Timeout watchdog ending a test that ignores interrupts past its timeout), the test that was running is
reported failed, with the exit status and the tail of the process's stderr, and the tests with no result yet run in a
new process, again until each has one. Which tests those are comes from listing the suite (`-e log true`, with the
same -e arguments) once the first process has died, so a run that does not crash pays nothing for it; the rest is
passed as `-e class` (whole classes where none of their tests ran, else Class#method), the other -e arguments kept,
in chunks short enough for one argument. A comma in a name (Parameterized's `test[0: x, y]`) is written `\\,` and a
backslash `\\\\`: apksim's -e class/notClass read those escapes (a device's runner has none; --isolate uses them too). No test with a result runs again. A new process that adds no result
(a crash at startup) ends the run: the tests still without one are reported failed as not run.
With --isolate each test runs in a process of its own, N at a time, as the Orchestrator with clearPackageData does.
With --shards N the suite is split over N processes running at once, each given AndroidJUnitRunner's
`-e numShards N -e shardIndex I` (a test's shard comes from its name's hash) and each recovering from a crash as one
process does; their results make one report. A shard's tests share its process (statics carry over within it), so a
suite that passes in one process passes sharded unless its tests depend on running after one another.
--test-timeout SEC (default 900, 0 for none): a process that goes SEC seconds without a test starting or ending (a
test hung past its own JUnit timeout: in teardown, or ignoring interrupts where apksim's watchdog cannot see it) is
killed and handled as one that died: the running test fails "timed out after SEC s" with the stderr tail and the
stacks of the threads running JUnit code (a SIGQUIT thread dump taken just before), and the rest run in a new process.
The kill takes the process group (each process gets one of its own) and, when --apksim is `docker|podman run`, the
container too (`docker rm -f`, by a --cidfile this script adds): killing the client leaves a container running.
-e passes am instrument arguments (class, notClass, package, notPackage, and any others the tests read).
Options after -- go to apksim, e.g. `-- --device tablet`. Exits 1 if any test failed, or if a process did not account
for its tests (no INSTRUMENTATION_CODE, fewer results than numtests, or a failing exit with no failed test) and no
test was running to put that on (it died between tests, at startup or at exit): the report then lists a failed
"incomplete" entry for that process, even when the tests left all pass in the next one.
--apksim runs another apksim, such as the Linux image: `--apksim "docker run --rm -v $PWD/out:/work apksim:ci"`
with APP and TEST given relative to the mounted folder (the image's working directory).
--bundles DIR: failure bundles (apksim's --bundles, docs/plan.md phase 5) in DIR, which every process gets: each
failing test's screen, tree, log, threads and repro.sh in DIR/<class>/<method>-<hash>/. A test whose process died
gets failure.txt (if apksim wrote none) and process.txt (exit status, stderr tail). The JUnit XML links each bundle
(a property, and screen.png as a [[ATTACHMENT|...]] in system-out, relative to the XML), and DIR/index.html shows
them all. With --apksim, DIR is the path that apksim sees (relative to the mounted folder) and --bundles-local the
same folder here. apksim's other bundle options (--bundles-on all, --bundles-max N, --bundles-redact REGEX) go after --
(the redactions apply to process.txt too). An earlier run's bundles in DIR are removed first.
Test impact and balanced shards (docs/affected-tests.md):
--record-deps FILE records which of the app's and the tests' classes each test runs code of, into FILE.
--affected-by FILE with --changed PATH... (or @LIST, a file of paths) or --diff REV (`git diff --name-only REV`, e.g.
`origin/main...HEAD`; --repo DIR for another checkout) runs only the tests that reached a class compiled from a changed
Kotlin or Java file, plus tests FILE has no record of; any other change (resources, manifests, build files, assets, a
source file of no class the recording knows) runs everything. --ignore GLOB leaves out changed paths that cannot affect
the tests (docs, JVM unit tests: `--ignore '*.md' --ignore '*/src/test/*'`).
--timings XML (an earlier run's --xml; repeatable) makes --shards split the tests by their measured times, longest first,
each shard given an `-e class` list, instead of by hash. --shard I runs only shard I (0-based) of the N, for CI jobs
on separate machines; every job must be given the same suite, timings and change.
"""
import argparse, concurrent.futures, hashlib, html, os, re, shlex, shutil, signal, subprocess, sys, tempfile, threading
import time
import affected
from xml.sax.saxutils import escape, quoteattr

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
CODES = {0: "passed", -2: "failed", -3: "ignored", -4: "skipped"}


LAUNCHER = [ROOT + "/apksim"]
RECORDED = []  # --record-deps: each process's dependency lines, taken out of its output


TEST_TIMEOUT = 900  # --test-timeout: seconds a process may go without a test starting or ending (0: no limit)
LIVE = {}  # the processes running now: {Popen: its kill()}, for an interrupt (they are not in our process group)
# Held while a process starts and is entered in LIVE, and while stopping takes LIVE: so every process is either in
# what stopping kills or never starts. Reentrant: the signal handler runs on the main thread, which may hold it.
LIVE_LOCK = threading.RLock()
STOPPING = []  # the signal stopping the run, once one has: no process starts after it
MAIN_SPAWNING = [False]  # the main thread is between starting a process and entering it in LIVE
SPAWN_DELAY = float(os.environ.get("INSTRUMENT_CHECK_SPAWN_DELAY", 0))  # instrument-check.py: widen that window
CONTAINER_TOOLS = ("docker", "podman", "nerdctl")


def container_run(cmd):
    """Where `run` is when [cmd] starts a container (`docker [OPTIONS] run ...`): its index, else None."""
    for i, w in enumerate(cmd):
        if os.path.basename(w) in CONTAINER_TOOLS:
            return next((j for j in range(i + 1, len(cmd)) if cmd[j] == "run"), None)
    return None


def thread_dump_tests(dump):
    """From a JVM thread dump, the stacks of the threads in JUnit code (the hung test's, a timeout's watchdog), else all."""
    threads = [t for t in "\n".join(dump).split("\n\n") if t.lstrip().startswith('"')]
    picked = [t.strip("\n") for t in threads if "org.junit." in t] or [t.strip("\n") for t in threads]
    text = "\n\n".join(picked)
    return text if len(text) <= 30_000 else text[:30_000] + f"\n... ({len(text) - 30_000} more characters)"


def apksim(app, test, extra_args, apksim_opts):
    """Runs apksim --instrument; its output as subprocess.run's, plus .seconds: {(class, method): how long the test ran},
    from when each test's start and end blocks arrived (the output is read as it comes), and, when --test-timeout
    killed it, .timed_out (the limit) and .threads (the JUnit threads' stacks from a thread dump taken first)."""
    cmd = LAUNCHER + ["--instrument", test]
    for k, v in extra_args:
        cmd += ["-e", k, v]
    cmd += apksim_opts + [app]
    # A container outlives its client (SIGKILL is not passed on): note its ID so that a kill can end it.
    run, cidfile, cid_dir = container_run(cmd), None, None
    if run is not None:
        given = [cmd[i + 1] if w == "--cidfile" else w.split("=", 1)[1] for i, w in enumerate(cmd[:-1])
                 if w == "--cidfile" or w.startswith("--cidfile=")]
        if given:
            cidfile = given[0]
        else:
            cid_dir = tempfile.mkdtemp(prefix="instrument-cid-")
            cidfile = os.path.join(cid_dir, "cid")
            cmd = cmd[:run + 1] + ["--cidfile", cidfile] + cmd[run + 1:]
    cid, kill_lock = [], threading.Lock()
    tool = next((os.path.basename(w) for w in cmd[:run or 0] if os.path.basename(w) in CONTAINER_TOOLS), "docker")

    def container_id():
        """The container's ID, once docker has written it (it creates the file empty, then writes the ID between
        creating the container and starting it). OSError if the file cannot be read."""
        if not cid:
            with open(cidfile) as f:
                c = f.read().strip()
            if c:
                cid.append(c)
        return cid[0] if cid else None

    def remove_container():
        try:
            c = container_id()
        except OSError as e:
            sys.stderr.write(f"instrument.py: could not read the container ID from {cidfile} ({e}): a container "
                             f"{tool} started for this process may still be running\n")
            return
        if c:  # (`rm -f`: it ends a container whether it started or not; one already gone is an error, ignored)
            try:
                subprocess.run(cmd[:run] + ["rm", "-f", c], capture_output=True, timeout=60)
            except (OSError, subprocess.SubprocessError) as e:
                sys.stderr.write(f"instrument.py: `{tool} rm -f {c}` failed ({e}): the container may still be "
                                 "running\n")

    def kill():
        with kill_lock:
            if cidfile:
                try:
                    container_id()  # before the client ends, while its file is sure to be there
                except OSError:
                    pass
            # Then the client, so that it starts no container after the ID is read, then the container.
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            if cidfile:
                remove_container()

    main = threading.current_thread() is threading.main_thread()
    with LIVE_LOCK:
        if STOPPING:
            raise RuntimeError("instrument.py is stopping: no new apksim process")
        MAIN_SPAWNING[0] = main
        try:
            # Its own process group (session), so a kill ends whatever it started too, and nothing but this script's.
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            if SPAWN_DELAY:
                time.sleep(SPAWN_DELAY)
            LIVE[p] = kill
        finally:
            MAIN_SPAWNING[0] = False
    if main and STOPPING:  # a signal came while this thread started it: its handler left the stopping to this
        stop(STOPPING[0])
    err = []
    reader = threading.Thread(target=lambda: err.append(p.stderr.read()))
    reader.start()
    lines, seconds, started, block, deps = [], {}, {}, {}, []
    # --test-timeout: when the process started or a test last started or ended (a hung test held the run for as long
    # as the CI job allowed: 67 minutes once). Past the limit: SIGQUIT (the JVM prints every thread's stack on its
    # stdout, read below), up to 5 s for that, then the kill.
    progress, timed_out, dump, in_dump = [time.monotonic()], [], [], False
    quit_sent, dumped, done = threading.Event(), threading.Event(), threading.Event()

    def watchdog():
        while not done.wait(min(1.0, TEST_TIMEOUT / 4)):
            if time.monotonic() - progress[0] > TEST_TIMEOUT:
                timed_out.append(TEST_TIMEOUT)
                quit_sent.set()
                try:
                    p.send_signal(signal.SIGQUIT)  # (with docker, the client passes it on to the container's JVM)
                except ProcessLookupError:
                    pass
                dumped.wait(5)
                kill()
                return

    dog = threading.Thread(target=watchdog, daemon=True) if TEST_TIMEOUT > 0 else None
    if dog:
        dog.start()
    for line in p.stdout:
        if quit_sent.is_set() and (in_dump or line.startswith("Full thread dump")):
            in_dump = True
            dump.append(line.rstrip("\n"))
            if line.startswith("JNI global refs"):
                in_dump = False
                dumped.set()
            continue
        if line.startswith(affected.PREFIX):
            deps.append(line)
            continue
        lines.append(line)
        m = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if m:
            block[m[1]] = m[2].rstrip("\n")
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            progress[0] = time.monotonic()
            t = (block.get("class"), block.get("test"))
            if line.split(": ")[1].strip() == "1":
                started[t] = time.time()
            elif t in started:
                seconds[t] = time.time() - started.pop(t)
            block = {}
    dumped.set()
    p.wait()
    done.set()
    if dog:
        dog.join()  # (a kill under way reads the cidfile: the client's end, closing its output, comes first)
    reader.join()
    if cidfile and p.returncode < 0 and not timed_out:
        with kill_lock:  # the client died of a signal, not of its container's end: the container may live on
            remove_container()
    with LIVE_LOCK:
        LIVE.pop(p, None)
        stopping = bool(STOPPING)
    if cid_dir and not stopping:  # (a stop's kill may still read it; this script ends with it anyway)
        shutil.rmtree(cid_dir, ignore_errors=True)
    if deps:
        RECORDED.append(deps)
    r = subprocess.CompletedProcess(p.args, p.returncode, "".join(lines), err[0] if err else "")
    r.seconds = seconds
    r.timed_out = timed_out[0] if timed_out else None
    r.threads = thread_dump_tests(dump) if dump else ""
    return r


def interrupted(signum, frame):
    """SIGINT/SIGTERM/SIGHUP: the test processes have process groups of their own, so a Ctrl-C at the terminal no
    longer reaches them: end them (and their containers), then this script, with the signal's status. If the main
    thread was starting a process, that is left to it, once the process is in LIVE."""
    with LIVE_LOCK:
        STOPPING[:] = STOPPING or [signum]
        if MAIN_SPAWNING[0]:
            return
    stop(signum)


def stop(signum):
    with LIVE_LOCK:  # (waits for a worker thread starting a process; none starts after this)
        STOPPING[:] = STOPPING or [signum]
        kills = list(LIVE.values())
    for k in kills:
        k()
    os._exit(128 + signum)


def tail(r):
    """What a failure that this script reports for a process shows of it: the tail of its stderr, and after a
    --test-timeout kill the stacks of the threads running JUnit code when it was killed."""
    t = r.stderr[-4000:]
    if r.threads:
        t = (t.rstrip("\n") + "\n\nthreads in JUnit code when instrument.py killed the process (from the JVM's thread "
             "dump):\n" + r.threads)
    return t


def timeout_note(r):
    return (f"timed out after {r.timed_out:g} s: no test started or ended for that long (--test-timeout), "
            "so instrument.py killed the apksim process")


def incomplete(r, results, test=None):
    """Why a run's output does not account for its tests, or None: a run must end with INSTRUMENTATION_CODE and report
    every test it announced (numtests), or the process crashed or was killed partway and the missing tests would
    otherwise vanish from the report. [test] is the one test an isolated run was started for."""
    announced = [int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", r.stdout)]
    if getattr(r, "timed_out", None):
        return timeout_note(r)
    if "INSTRUMENTATION_CODE: " not in r.stdout:
        return f"apksim exited ({r.returncode}) before the run finished"
    if announced and len(results) < max(announced):
        return f"apksim reported {len(results)} of the {max(announced)} tests it started"
    if r.returncode != 0 and not any(res[2] == "failed" for res in results):
        return f"apksim exited with {r.returncode} although no test failed"
    return None


def parse(raw):
    """am instrument -r output -> [(class, method, status, stack)] for each finished test."""
    return parse_run(raw)[0]


def parse_run(raw):
    """am instrument -r output -> ([(class, method, status, stack)] for each finished test, (class, method) of the test
    that had started and not finished when the output ends, or None)."""
    out, block, key, running = [], {}, None, None
    for line in raw.splitlines():
        m = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        if m:
            key = m[1]
            block[key] = m[2]
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ")[1])
            if code != 1:
                out.append((block.get("class"), block.get("test"), CODES.get(code, f"code {code}"), block.get("stack", "")))
                running = None
            else:
                running = (block.get("class"), block.get("test"))
            block, key = {}, None
        elif line.startswith("INSTRUMENTATION_"):
            key = None
        elif key:
            block[key] += "\n" + line
    return out, running


def escape_selector(name):
    """A class or method name for -e class/notClass, with apksim's escapes: `\\,` for a comma in it (Parameterized's
    `test[0: x, y]`), `\\\\` for a backslash. Instrument.selectorParts reads them; a device's runner has no escapes."""
    return name.replace("\\", "\\\\").replace(",", "\\,")


def selectors(s):
    """-e class/notClass -> its names, split on unescaped commas and unescaped, as Instrument.selectorParts does."""
    out, part, i = [], [], 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s) and s[i + 1] in ",\\":
            part.append(s[i + 1])
            i += 1
        elif s[i] == ",":
            out.append("".join(part))
            part = []
        else:
            part.append(s[i])
        i += 1
    return out + ["".join(part)]


def narrow(args, listing, remaining, budget=100_000):
    """The -e arguments that run the first of [remaining] (of the suite's [listing], in its order): the user's own
    without `class`, plus `class` naming them. A class none of whose listed tests has a result is named whole, unless
    the user's `class` picked methods of it; otherwise its methods are named one by one. The list stops before the
    argument it becomes passes [budget] bytes, as UTF-8 and with the launcher's `-Dapksim.instrument.class=` before it
    (Linux takes at most 131,072 bytes in one argument, E2BIG past that): the rest go in later processes."""
    picked = [c.strip() for c in selectors(dict(args).get("class", "")) if c.strip()]
    left = set(remaining)
    by_class = {}
    for t in listing:
        by_class.setdefault(t[0], []).append(t)
    names = []
    for c, tests in by_class.items():
        todo = [t for t in tests if t in left]
        if len(todo) == len(tests) and (not picked or c in picked):
            names.append(escape_selector(c))
        else:
            names += [f"{escape_selector(c)}#{escape_selector(m)}" for _, m in todo]
    chunk, size = [], len(b"-Dapksim.instrument.class=")  # the one argument the launcher makes of `-e class X`
    for n in names:
        n_bytes = len(n.encode("utf-8")) + 1  # and its comma
        if chunk and size + n_bytes > budget:
            break
        chunk.append(n)
        size += n_bytes
    return [kv for kv in args if kv[0] != "class"] + [("class", ",".join(chunk))]


def one_process(a, passthrough, label=""):
    """Runs the suite in one process, and the tests it left without a result in new ones (see the module doc).
    [label] names the shard in messages and in the "(run)" entries, which must stay apart across shards."""
    results = []  # (class, method, status, stack, seconds)
    done, done_classes = set(), set()

    def record(c, m, s, st, sec=None):
        results.append((c, m, s, st, sec))
        done.add((c, m))
        if m in (None, "null"):  # a class-level failure (initializationError, @BeforeClass) stands for all its tests
            done_classes.add(c)

    def has_result(t):
        return t in done or t[0] in done_classes

    listing, remaining, args, processes, first_count = None, None, a.e, 0, None
    while True:
        r = apksim(a.app, a.test, args, passthrough)
        processes += 1
        got, running = parse_run(r.stdout)
        if remaining is None:
            new = [g for g in got if not has_result((g[0], g[1]))]
            counts = [int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", r.stdout)]
            first_count = max(counts) if counts else None
        else:  # only what this process was asked for: not, say, JUnit's "No tests found matching" failure
            asked, asked_classes = set(remaining), {c for c, _ in remaining}
            new = [g for g in got if not has_result((g[0], g[1]))
                   and ((g[0], g[1]) in asked or (g[1] in (None, "null") and g[0] in asked_classes))]
        for g in new:
            record(*g, r.seconds.get((g[0], g[1])))
        why = incomplete(r, got)
        out = tail(r)
        crashed = None
        if why:
            sys.stderr.write(out if out.endswith("\n") else out + "\n")
            if running and not has_result(running) and (remaining is None or running in set(remaining)):
                crashed = running
                finished = "INSTRUMENTATION_CODE: " in r.stdout  # an odd case: the run ended, this test did not
                record(*running, "failed", (f"timed out after {r.timed_out:g} s while this test was running: no test "
                                            "started or ended for that long (--test-timeout), so instrument.py killed "
                                            "the apksim process" if r.timed_out else
                                            f"apksim exited ({r.returncode}) while this test was running"
                                            + (f" ({why})" if finished else "")) + ":\n" + out)
            elif remaining is None or new:  # (a later process with no result: its tests' "not run" says why)
                # Between tests, at startup or at exit: nothing to put it on, but the run must still fail, as on a
                # device. One entry per such process, named apart so the XML keeps one entry per name.
                name = ("incomplete" if processes == 1 else f"incomplete (process {processes})") + label
                results.append(("(run)", name, "failed", why + ":\n" + out, None))
                counts = [int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", r.stdout)]
                if remaining is None and "INSTRUMENTATION_CODE: " in r.stdout and counts and len(got) >= max(counts):
                    break  # every test has a result (it failed at exit, say): nothing to list or rerun
        if remaining is None and not why:
            break  # the usual case: every test has a result, and the suite was never listed
        if not new and not crashed:
            # This process got no further than the last one (a crash at startup, say): no new process.
            if remaining is not None:
                for c, m in remaining:
                    if not has_result((c, m)):
                        record(c, m, "failed", f"not run: the apksim process started for the {len(remaining)} tests "
                                               f"left gave none of them a result ({why or f'exit {r.returncode}'}):\n"
                                               + out)
            break
        if listing is None:
            listed = apksim(a.app, a.test, a.e + [("log", "true")], passthrough)
            processes += 1
            lwhy = incomplete(listed, parse(listed.stdout))
            if lwhy:
                results.append(("(listing)", "incomplete" + label, "failed", f"after the run failed ({why}), listing the suite "
                                f"to run the rest failed too ({lwhy}):\n" + listed.stderr[-4000:], None))
                break
            listing = [(c, m) for c, m, _, _ in parse(listed.stdout)]
            if first_count is not None and first_count != len(listing):
                print(f"the suite{label} lists {len(listing)} tests, the run announced {first_count}", file=sys.stderr)
        remaining = [t for t in listing if not has_result(t)]
        if not remaining:
            break
        args = narrow(a.e, listing, remaining)
        if why:
            at = f" while {crashed[0]}#{crashed[1]} was running" if crashed else ""
            ended = f"timed out after {r.timed_out:g} s" if r.timed_out else f"exited ({r.returncode})"
            print(f"apksim {ended}{label}{at}: {len(listing) - len(remaining)} of {len(listing)} tests have "
                  f"results, running the {len(remaining)} left in a new process", file=sys.stderr)
    if processes > 1:
        print(f"{processes} apksim processes{label}, one of them listing the suite", file=sys.stderr)
    return results


def bundle_folder(c, m):
    """A test's bundle folder under DIR, as apksim's Bundles.folder names it: <class>/<method>-<8 hex of SHA-256>."""
    method = None if m in (None, "null") else m
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", method or "class")[:60]
    h = hashlib.sha256(f"{c}#{method or ''}".encode()).hexdigest()[:8]
    return os.path.join(c, f"{safe}-{h}")


def died(st):
    """A failure this script reported, not the test: the process ended without the test's result (see one_process)."""
    return st.startswith(("apksim ", "not run:", "timed out "))


def clear_bundles(local):
    """Removes an earlier run's bundles from DIR, once before this run's processes start, so that a test passing now
    is not linked to its old failure: index.html and the folders that are bundles (they hold failure.txt or
    process.txt), then class folders left empty. Anything else in DIR stays."""
    if not os.path.isdir(local):
        return
    if os.path.exists(os.path.join(local, "index.html")):
        os.remove(os.path.join(local, "index.html"))
    for c in os.listdir(local):
        cdir = os.path.join(local, c)
        if os.path.islink(cdir) or not os.path.isdir(cdir):  # (a link may lead out of DIR: never followed)
            continue
        for b in os.listdir(cdir):
            bdir = os.path.join(cdir, b)
            if not os.path.islink(bdir) and os.path.isdir(bdir) and {"failure.txt", "process.txt"} & set(os.listdir(bdir)):
                shutil.rmtree(bdir)
        if not os.listdir(cdir):
            os.rmdir(cdir)


def java_regex(rx):
    """A --bundles-redact pattern (Java's syntax, which apksim compiles) for Python's re: Java's \\Q...\\E quoting
    becomes re.escape. Other syntax must be what both read; redactions() checks that before the run starts."""
    return re.sub(r"\\Q(.*?)(?:\\E|$)", lambda m: re.escape(m[1]), rx, flags=re.S)


def redactions(passthrough):
    """apksim's --bundles-redact REGEX options (after --): applied here too, to what this script writes (process.txt,
    a fallback failure.txt, index.html). Raises ValueError, before any test runs, for one Python's re cannot read."""
    out = []
    for i, o in enumerate(passthrough[:-1]):
        if o == "--bundles-redact":
            try:
                out.append(re.compile(java_regex(passthrough[i + 1])))
            except re.error as e:
                raise ValueError(f"--bundles-redact {passthrough[i + 1]!r}: {e}. instrument.py applies it with Python's "
                                 f"re as well as apksim with Java's Pattern: use syntax both read (\\Q...\\E is fine)")
    return out


def redact_text(text, redact):
    for r in redact:
        text = r.sub("[redacted]", text)
    return text


def collect_bundles(local, results, redact=()):
    """{(class, method): bundle folder} for the results that have one; writes the notes for tests whose process died
    (redacted as apksim redacts its own text files)."""
    def clean(text):
        return redact_text(text, redact)
    found = {}
    for c, m, s, st, _ in results:
        if c in ("(run)", "(listing)"):
            continue
        path = os.path.join(local, bundle_folder(c, m))
        if s == "failed" and died(st or ""):
            os.makedirs(path, exist_ok=True)
            with open(os.path.join(path, "process.txt"), "w") as f:
                f.write(clean(st))
            if not os.path.exists(os.path.join(path, "failure.txt")):
                with open(os.path.join(path, "failure.txt"), "w") as f:
                    f.write(clean(f"test: {c}#{m}\nstatus: failed\ncaptured by: instrument.py (the process gave no result "
                                  f"for this test)\n\n{st}\n"))
        if os.path.isdir(path):
            found[(c, m)] = path
    return found


def write_index(local, results, bundles, redact=()):
    """DIR/index.html: one card per bundle (screen, the failure's first lines, the files), in one self-contained page.
    The previews are redacted as the bundles' own text files are: the page is uploaded with them."""
    cards = []
    for c, m, s, st, _ in results:
        path = bundles.get((c, m))
        if not path:
            continue
        rel = os.path.relpath(path, local)
        files = sorted(os.listdir(path))
        captured = ""
        try:
            with open(os.path.join(path, "failure.txt")) as f:
                captured = next((line[len("captured by: "):].strip() for line in f if line.startswith("captured by: ")), "")
        except OSError:
            pass
        first = redact_text(next((line for line in (st or "").splitlines() if line.strip()), s), redact)
        img = (f'<a href="{html.escape(rel)}/screen.png"><img loading="lazy" src="{html.escape(rel)}/screen.png" alt=""></a>'
               if "screen.png" in files else '<div class="noshot">no screen</div>')
        links = " ".join(f'<a href="{html.escape(rel)}/{html.escape(n)}">{html.escape(n)}</a>' for n in files if n != "screen.png")
        cards.append(f'<article class="{html.escape(s)}">{img}<div><h2>{html.escape(c.rsplit(".", 1)[-1])}'
                     f'<span>#{html.escape(m or "")}</span></h2><p class="cls">{html.escape(c)}</p>'
                     f'<p class="why">{html.escape(first[:300])}</p><p class="meta">{html.escape(s)}'
                     f'{" · " + html.escape(captured) if captured else ""}</p><p class="files">{links}</p></div></article>')
    failed = sum(1 for r in results if r[2] == "failed")
    page = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Failure bundles</title><style>
:root{{--bg:#fafafa;--card:#fff;--fg:#1d1d1f;--dim:#6e6e73;--line:#e3e3e6;--bad:#c0392b;--ok:#2e7d32}}
@media (prefers-color-scheme: dark){{:root{{--bg:#151517;--card:#1f1f22;--fg:#ececee;--dim:#9a9aa0;--line:#333338;--bad:#ff6b5e;--ok:#7ccf83}}}}
body{{margin:0;padding:24px 16px;background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,system-ui,sans-serif}}
main{{max-width:1100px;margin:0 auto}} h1{{font-size:20px;margin:0 0 4px}} .sum{{color:var(--dim);margin:0 0 20px}}
article{{display:flex;gap:16px;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px;margin:0 0 12px}}
article img,.noshot{{width:120px;flex:none;border-radius:6px;border:1px solid var(--line)}}
.noshot{{height:200px;display:flex;align-items:center;justify-content:center;color:var(--dim);font-size:12px}}
article>div{{min-width:0}} h2{{font-size:16px;margin:0}} h2 span{{color:var(--dim);font-weight:500}}
.cls,.meta{{color:var(--dim);margin:2px 0;font-size:12px;overflow-wrap:anywhere}}
.why{{font-family:ui-monospace,Menlo,monospace;font-size:12px;color:var(--bad);margin:8px 0;overflow-wrap:anywhere}}
.passed .why{{color:var(--ok)}} .files a{{margin-right:10px;color:inherit}}
@media (max-width:560px){{article{{flex-direction:column}} article img,.noshot{{width:100%;max-width:240px}}}}
</style></head><body><main><h1>Failure bundles</h1>
<p class="sum">{len(results)} tests, {failed} failed, {len(cards)} with a bundle</p>
{"".join(cards) or "<p>No bundles.</p>"}</main></body></html>
"""
    with open(os.path.join(local, "index.html"), "w") as f:
        f.write(page)


def list_suite(a, passthrough):
    """The suite as a.e names it, listed by apksim (`-e log true`): [(class, method)], or exits if it cannot be listed."""
    listed = apksim(a.app, a.test, a.e + [("log", "true")], passthrough)
    why = incomplete(listed, parse(listed.stdout))
    if why:
        sys.stderr.write(listed.stderr[-4000:])
        sys.exit(f"listing the suite failed: {why}")
    return [(c, m) for c, m, _, _ in parse(listed.stdout)]


def run_tests(a, passthrough, listing, tests, label=""):
    """Runs exactly [tests] (of the suite's [listing]) as one process does (one_process), naming them with -e class
    (narrow: whole classes where it can), in as many processes one after another as the argument's length limit needs."""
    remaining, results, part = list(tests), [], 0
    while remaining:
        args = narrow(a.e, listing, remaining)
        names = selectors(dict(args)["class"])
        covered = {t for t in remaining if t[0] in names or f"{t[0]}#{t[1]}" in names}
        remaining = [t for t in remaining if t not in covered]
        part += 1
        sa = argparse.Namespace(**vars(a))
        sa.e = args
        results += one_process(sa, passthrough, label + (f" (part {part})" if part > 1 or remaining else ""))
    return results


def main():
    global TEST_TIMEOUT
    argv = sys.argv[1:]
    passthrough = []
    if "--" in argv:
        passthrough = argv[argv.index("--") + 1:]
        argv = argv[:argv.index("--")]
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("--isolate", action="store_true")
    p.add_argument("-j", type=int, default=max(1, min(4, (os.cpu_count() or 2) // 3)))
    p.add_argument("--shards", type=int, default=1)
    p.add_argument("--xml")
    p.add_argument("--apksim")
    p.add_argument("-e", nargs=2, action="append", default=[], metavar=("KEY", "VALUE"))
    p.add_argument("--bundles")
    p.add_argument("--bundles-local")
    p.add_argument("--record-deps", metavar="FILE")
    p.add_argument("--affected-by", metavar="FILE")
    p.add_argument("--changed", nargs="+", action="extend", default=[], metavar="PATH")
    p.add_argument("--diff", metavar="REV")
    p.add_argument("--repo", default=".")
    p.add_argument("--ignore", action="append", default=[], metavar="GLOB")
    p.add_argument("--timings", action="append", default=[], metavar="XML")
    p.add_argument("--shard", type=int, metavar="I")
    p.add_argument("--test-timeout", type=float, default=TEST_TIMEOUT, metavar="SEC")
    a = p.parse_args(argv)
    if a.test_timeout < 0:
        p.error("--test-timeout SEC: 0 for no limit, else the seconds a process may go without a test starting or ending")
    TEST_TIMEOUT = a.test_timeout
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, interrupted)
    redact = []
    if a.bundles:
        try:
            redact = redactions(passthrough)
        except ValueError as e:
            p.error(str(e))
        passthrough = passthrough + ["--bundles", a.bundles]
        clear_bundles(a.bundles_local or a.bundles)
        os.makedirs(a.bundles_local or a.bundles, exist_ok=True)
    if a.apksim:
        LAUNCHER[:] = shlex.split(a.apksim)

    t0 = time.time()
    results = []  # (class, method, status, stack, seconds)
    if a.shards > 1 and a.isolate:
        p.error("--shards splits a suite over processes that each run many tests; --isolate gives every test its own"
                " process already (use -j for how many at a time)")
    if a.shard is not None and not 0 <= a.shard < a.shards:
        p.error("--shard I runs shard I of --shards N: 0 <= I < N")
    if a.record_deps and a.affected_by:
        p.error("--record-deps records the whole suite; run it without --affected-by")
    if (a.changed or a.diff) and not a.affected_by:
        p.error("--changed and --diff need --affected-by DEPS (a file --record-deps wrote)")
    if a.record_deps:
        passthrough = ["--jvm", "-Dapksim.deps=true"] + passthrough

    listing, selected = None, None  # selected: the tests to run, when not every test a.e names
    if a.affected_by:
        doc = affected.read_deps(a.affected_by)
        if not (a.changed or a.diff):
            p.error("--affected-by needs --changed PATH... or --diff REV")
        changed = affected.changed_files(a.changed, a.diff, a.repo)
        try:  # classes the recorded build did not have (new files): from the APKs as they are now
            now = affected.source_map([a.app, a.test], doc["packages"])
        except OSError as e:  # (with --apksim the APKs may be named for another file system)
            print(f"could not read the APKs' classes here ({e}): only the recorded build's are known", file=sys.stderr)
            now = {}
        classes, notes = affected.affected_classes(doc, changed, now, a.ignore)
        if classes is None:
            print(f"running every test: {notes}", file=sys.stderr)
        else:
            for n in notes:
                print("  " + n, file=sys.stderr)
            listing = list_suite(a, passthrough)
            selected, unknown = affected.select(doc, listing, classes)
            print(f"{len(selected)} of {len(listing)} tests affected by {len(changed)} changed files"
                  + (f" ({unknown} of them new or not recorded)" if unknown else ""), file=sys.stderr)
    timings = affected.read_timings(a.timings)
    if a.shards > 1 and (timings or selected is not None):
        # Shards by measured time (or, without timings, by count), each named with -e class.
        if any(k in ("numShards", "shardIndex") for k, _ in a.e):
            p.error("--shards sets which tests each shard runs itself")
        if listing is None:
            listing = list_suite(a, passthrough)
        lists, totals = affected.balance(selected if selected is not None else listing, timings, a.shards)
        which = range(a.shards) if a.shard is None else [a.shard]
        print(f"{a.shards} shards by " + ("measured time" if timings else "count") + ", predicted "
              + ", ".join(f"{totals[i]:.0f} s" for i in range(a.shards))
              + ("" if a.shard is None else f"; running shard {a.shard}"), file=sys.stderr)

        def balanced(i):
            start = time.time()
            got = run_tests(a, passthrough, listing, lists[i], f" (shard {i + 1} of {a.shards})") if lists[i] else []
            print(f"  shard {i + 1} of {a.shards}: {len(got)} tests in {time.time() - start:.1f} s", file=sys.stderr)
            return got

        with concurrent.futures.ThreadPoolExecutor(len(which)) as pool:
            for got in pool.map(balanced, which):
                results += got
    elif a.shards > 1:
        if any(k in ("numShards", "shardIndex") for k, _ in a.e):
            p.error("--shards sets -e numShards and shardIndex itself")
        which = range(a.shards) if a.shard is None else [a.shard]
        print(f"{a.shards} shards, one process each, " + ("all at once" if a.shard is None else f"running shard {a.shard}"),
              file=sys.stderr)

        def shard(i):
            sa = argparse.Namespace(**vars(a))
            sa.e = a.e + [("numShards", str(a.shards)), ("shardIndex", str(i))]
            start = time.time()
            got = one_process(sa, passthrough, f" (shard {i + 1} of {a.shards})")
            print(f"  shard {i + 1} of {a.shards}: {len(got)} tests in {time.time() - start:.1f} s", file=sys.stderr)
            return got

        with concurrent.futures.ThreadPoolExecutor(len(which)) as pool:
            for got in pool.map(shard, which):
                results += got
    elif not a.isolate:
        results = run_tests(a, passthrough, listing, selected) if selected is not None else one_process(a, passthrough)
    elif selected is not None:
        tests = selected
        print(f"{len(tests)} tests, {a.j} at a time, each in its own process", file=sys.stderr)
    else:
        listed = apksim(a.app, a.test, a.e + [("log", "true")], passthrough)
        tests = [(c, m) for c, m, _, _ in parse(listed.stdout)]
        why = incomplete(listed, parse(listed.stdout))
        if why:
            sys.stderr.write(listed.stderr[-4000:])
            results.append(("(listing)", "incomplete", "failed", why + ":\n" + listed.stderr[-4000:], None))
        print(f"{len(tests)} tests, {a.j} at a time, each in its own process", file=sys.stderr)

    if a.isolate:
        def one(t):
            start = time.time()
            others = [kv for kv in a.e if kv[0] not in ("class", "package")]
            r = apksim(a.app, a.test, others + [("class", f"{escape_selector(t[0])}#{escape_selector(t[1])}")], passthrough)
            got = parse(r.stdout)
            why = incomplete(r, got)
            if not got or why:
                return (t[0], t[1], "failed", (why or "apksim exited without a result") + ":\n" + tail(r),
                        time.time() - start)
            c, m, s, st = got[0]
            return (c, m, s, st, time.time() - start)

        with concurrent.futures.ThreadPoolExecutor(a.j) as pool:
            for res in pool.map(one, tests):
                results.append(res)
                print(f"  {res[2]:8} {res[0]}#{res[1]}  {res[4]:.1f} s", file=sys.stderr)

    failed = [r for r in results if r[2] == "failed"]
    for c, m, s, st, _ in failed:
        print(f"\nFAILED {c}#{m}\n{st}", file=sys.stderr)
    counts = {s: sum(1 for r in results if r[2] == s) for s in sorted({r[2] for r in results})}
    print(f"{len(results)} tests in {time.time() - t0:.1f} s: " + ", ".join(f"{n} {s}" for s, n in counts.items()))
    bundles = collect_bundles(a.bundles_local or a.bundles, results, redact) if a.bundles else {}
    if a.bundles:
        write_index(a.bundles_local or a.bundles, results, bundles, redact)
        print(f"{len(bundles)} failure bundles: {os.path.join(a.bundles_local or a.bundles, 'index.html')}", file=sys.stderr)
    if a.xml:
        xml_dir = os.path.dirname(os.path.abspath(a.xml))
        with open(a.xml, "w") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
            f.write(f'<testsuite name="apksim" tests="{len(results)}" failures="{len(failed)}" '
                    f'skipped="{sum(1 for r in results if r[2] in ("ignored", "skipped"))}">\n')
            for c, m, s, st, sec in results:
                f.write(f'  <testcase classname={quoteattr(c or "")} name={quoteattr(m or "")}'
                        + (f' time="{sec:.3f}"' if sec is not None else "") + ">")
                bundle = bundles.get((c, m))
                rel = os.path.relpath(bundle, xml_dir) if bundle else None
                if rel:
                    f.write(f'<properties><property name="apksim.bundle" value={quoteattr(rel)}/></properties>')
                if s == "failed":
                    f.write(f"<failure message={quoteattr(st.splitlines()[0] if st else '')}>{escape(st)}</failure>")
                elif s in ("ignored", "skipped"):
                    f.write("<skipped/>")
                if rel:
                    # GitLab's and Jenkins' JUnit attachments: a screenshot shown with the test.
                    shot = "screen.png" if os.path.exists(os.path.join(bundle, "screen.png")) else "failure.txt"
                    f.write(f"<system-out>{escape(f'[[ATTACHMENT|{os.path.join(rel, shot)}]]')}</system-out>")
                f.write("</testcase>\n")
            f.write("</testsuite>\n")
    if a.record_deps:
        affected.write_deps(a.record_deps, RECORDED, [a.app, a.test])
    if selected is not None and not selected:
        print("no test is affected by the change", file=sys.stderr)
        sys.exit(0)
    # No results at all fails a run (no tests found), except for a shard, which can be empty by design (more shards
    # than tests, a selection smaller than the CI matrix): a process that did not finish has a "(run)" entry anyway.
    sys.exit(1 if failed or (not results and a.shards == 1) else 0)


main()
