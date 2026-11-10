#!/usr/bin/env python3
"""Runs an app's androidTest APK on apksim, as `am instrument` (with the AndroidX Test Orchestrator, --isolate).

Usage: instrument.py APP.apk TEST.apk [--isolate] [-j N] [--shards N [--timings XML]... [--shard I]] [--xml FILE]
                     [--record-deps FILE | --affected-by FILE (--changed PATH... | --diff REV) [--ignore GLOB]...]
                     [-e KEY VALUE]... [--apksim CMD] [--bundles DIR [--bundles-local PATH]] [--test-timeout SEC]
                     [--record failed|all --record-dir DIR [--record-local PATH] [--record-size WxH] [--record-fps N]]
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
With --isolate each test runs in a process of its own, N at a time, as the Orchestrator with clearPackageData does. Like the Orchestrator's runner side, it leaves a listed
`initializationError` (a Parameterized class with no parameters) out, with a warning naming the class; `--keep-class-errors` keeps it.
--reap-early SEC: the slot is free once a process has printed its result and exit status (apksim prints the status
ahead of its teardown: class cache save, scratch removal), and the process ends in the background, killed after SEC s;
nothing it writes after that counts, so it is for outputs written before the result (bundles, recordings, test outputs).
--exit-marks shows where a process's exit time went. Each test's line on stderr, and its JUnit entry (properties `apksim.startup_seconds`: spawn to the
test's start, `apksim.exit_seconds`: its end to the process exiting), show where its process's time went.
With --shards N the suite is split over N processes running at once, each given AndroidJUnitRunner's
`-e numShards N -e shardIndex I` (a test's shard comes from its name's hash) and each recovering from a crash as one
process does; their results make one report. A shard's tests share its process (statics carry over within it), so a
suite that passes in one process passes sharded unless its tests depend on running after one another.
--test-timeout SEC (default 900, 0 for none): a process that goes SEC seconds without a test starting or ending (a
test hung past its own JUnit timeout: in teardown, or ignoring interrupts where apksim's watchdog cannot see it) is
killed and handled as one that died: the running test fails "timed out after SEC s" with the stacks of the threads
running JUnit code (a SIGQUIT thread dump taken just before; each from its header down, cut at the bottom) and then
the stderr tail (a stack it would start inside of is kept from its header too), and the rest run in a new process.
The kill takes the process group (each process gets one of its own) and, when --apksim is `docker|podman run`, the
container too (`docker rm -f`, by a --cidfile this script adds): killing the client leaves a container running. Every
process gets its own cidfile (docker refuses one that exists): a --cidfile in --apksim is taken out, and the ID of the
last container to end is written there.
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
--record failed|all --record-dir DIR: a movie of each test (apksim's TestRecorder, issue 132), headless: Motion-JPEG
DIR/<class>/<method>-<hash>.avi (the bundle folder's name), kept for a failed test, or for every test with `all`, and
linked from the JUnit XML (a property, and a [[ATTACHMENT|...]] in system-out, relative to the XML). It works with or
without --isolate and --bundles (the movie may share the bundles folder). A test run has no display loop: a frame is
drawn after a window traversal, at most --record-fps (default 15, up to 30) times a second of real time, fitted into
--record-size WxH (default 1280x720, never enlarged); a still screen repeats its last frame, so the movie runs in real
time, and a test that never shows a window has none. The movie of a test whose process died or was killed is
unfinished (no index). With --apksim, DIR is the path apksim sees and --record-local the same folder here. An earlier
run's movies in DIR are removed first.
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
With --isolate, --shards N --shard I (required: the processes are the tests, so there is no "all shards at once") runs
shard I's tests, balanced by the same rule (by count without timings), each in its own process, -j at a time. With
--timings --isolate orders the starts by --order: `interleave` (default) alternates the longest and the shortest test, so
long tests start early but not together (stacked at the start, a machine's heaviest tests can slow each other past a
test's own timeout); `longest-first`; `suite` keeps the suite's order.
"""
import argparse, concurrent.futures, hashlib, html, os, re, shlex, shutil, signal, subprocess, sys, tempfile, threading
import time
import affected
from xml.sax.saxutils import escape, quoteattr

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
CODES = {0: "passed", -2: "failed", -3: "ignored", -4: "skipped"}


LAUNCHER = [ROOT + "/apksim"]
RECORDED = []  # --record-deps: each process's dependency lines, taken out of its output


MARKS = {}  # --exit-marks: (class, method) -> what apksim reported of its exit
PHASES = {}  # --isolate: (class, method) -> (start-up, test, exit) seconds of its process, for the report
EXIT_MARKS = False  # --exit-marks: apksim reports where its exit time goes (stderr, shown per test)
REAP_EARLY = 0  # --reap-early SEC (--isolate): a process counts as done once it has reported its exit status; its teardown gets SEC s
TEST_TIMEOUT = 900  # --test-timeout: seconds a process may go without a test starting or ending (0: no limit)
REAPERS = []  # --reap-early: the threads finishing processes that reported their result
LIVE = {}  # the processes running now: {Popen: its kill()}, for an interrupt (they are not in our process group)
# Held while a process starts and is entered in LIVE, and while stopping takes LIVE: so every process is either in
# what stopping kills or never starts. Reentrant: the signal handler runs on the main thread, which may hold it.
LIVE_LOCK = threading.RLock()
STOPPING = []  # the signal stopping the run, once one has: no process starts after it
MAIN_SPAWNING = [False]  # the main thread is between starting a process and entering it in LIVE
SPAWN_DELAY = float(os.environ.get("INSTRUMENT_CHECK_SPAWN_DELAY", 0))  # instrument-check.py: widen that window
CLEANUP_DELAY = float(os.environ.get("INSTRUMENT_CHECK_CLEANUP_DELAY", 0))  # and the main thread's container cleanup
IN_STOP = [False]  # stop() is under way: a second signal changes nothing
CONTAINER_TOOLS = ("docker", "podman", "nerdctl")


def container_run(cmd):
    """Where `run` is when [cmd] starts a container (`docker [OPTIONS] run ...`): its index, else None."""
    for i, w in enumerate(cmd):
        if os.path.basename(w) in CONTAINER_TOOLS:
            return next((j for j in range(i + 1, len(cmd)) if cmd[j] == "run"), None)
    return None


def fit_chars(t, limit):
    """[t], a stack's text, cut at the bottom at a line boundary to at most [limit] characters (and the marker)."""
    lines = t.split("\n")
    out, size, n = [], 0, 0
    while n < len(lines) - 1 and size + len(lines[n]) + 1 <= limit - 80:
        out.append(lines[n])
        size += len(lines[n]) + 1
        n += 1
    out = out or [lines[0][:limit - 80]]
    return "\n".join(out) + f"\n\t[... {len(lines) - len(out)} more lines of this stack]"


def thread_dump_tests(dump):
    """From a JVM thread dump, the stacks of the threads in JUnit code (the hung test's, a timeout's watchdog), else all."""
    threads = [t for t in "\n".join(dump).split("\n\n") if t.lstrip().startswith('"')]
    picked = [t.strip("\n") for t in threads if "org.junit." in t] or [t.strip("\n") for t in threads]
    # Each thread whole at the top (its header and top frames: what it is doing), cut at the bottom, so that one deep
    # stack does not push the others' tops out of the text.
    picked = [trimmed_stack([line + "\n" for line in t.split("\n")]).rstrip("\n") for t in picked]
    text, kept = "", 0
    for t in picked:
        if not text and len(t) > 30_000:  # even the first: 100 lines of long names can pass the limit
            t = fit_chars(t, 30_000)
        if text and len(text) + 2 + len(t) > 30_000:
            break
        text, kept = (text + "\n\n" + t if text else t), kept + 1
    return text if kept == len(picked) else text + f"\n\n[... {len(picked) - kept} more threads]"


def apksim(app, test, extra_args, apksim_opts):
    """Runs apksim --instrument; its output as subprocess.run's, plus .seconds: {(class, method): how long the test ran},
    .phases: {(class, method): (seconds from spawn to its start, its run, seconds from its end to the process exiting)},
    from when each test's start and end blocks arrived (the output is read as it comes), and, when --test-timeout
    killed it, .timed_out (the limit) and .threads (the JUnit threads' stacks from a thread dump taken first)."""
    launcher, user_cidfile = list(LAUNCHER), None
    # A container outlives its client (SIGKILL is not passed on): each process gets a cidfile of its own, so that a
    # kill can end its container. Docker refuses to run with a cidfile that exists, so a --cidfile in --apksim would
    # fail every process after the first (other shards, -j, a relaunch after a crash): it is taken out, and the last
    # container's ID written there instead, once its process has ended.
    run, cidfile, cid_dir = container_run(launcher), None, None
    if run is not None:
        i = run + 1
        while i < len(launcher):
            if launcher[i] == "--cidfile" and i + 1 < len(launcher):
                user_cidfile = launcher[i + 1]
                del launcher[i:i + 2]
            elif launcher[i].startswith("--cidfile="):
                user_cidfile = launcher[i].split("=", 1)[1]
                del launcher[i]
            else:
                i += 1
        cid_dir = tempfile.mkdtemp(prefix="instrument-cid-")
        cidfile = os.path.join(cid_dir, "cid")
        launcher = launcher[:run + 1] + ["--cidfile", cidfile] + launcher[run + 1:]
    cmd = launcher + ["--instrument", test]
    for k, v in extra_args:
        cmd += ["-e", k, v]
    if REAP_EARLY or EXIT_MARKS:  # the process reports its exit status ahead of its teardown, and what the teardown cost
        apksim_opts = ["--jvm", "-Dapksim.exitMarks=true"] + list(apksim_opts)
    cmd += apksim_opts + [app]
    # Reentrant: the signal handler runs on the main thread, and its stop() kills this process while the main thread
    # may be in kill_lock itself (the cleanup below). Other locks the handler takes: LIVE_LOCK, also reentrant.
    cid, kill_lock = [], threading.RLock()
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
            warn(f"instrument.py: could not read the container ID from {cidfile} ({e}): a container {tool} started "
                 "for this process may still be running")
            return
        if c:  # (`rm -f`: it ends a container whether it started or not; one already gone is an error, ignored)
            try:
                subprocess.run(cmd[:run] + ["rm", "-f", c], capture_output=True, timeout=60)
            except (OSError, subprocess.SubprocessError) as e:
                warn(f"instrument.py: `{tool} rm -f {c}` failed ({e}): the container may still be running")

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
    err = []  # (chunks as they arrive: a process reaped early is still being read)

    def read_err():
        for chunk in p.stderr:
            err.append(chunk)

    reader = threading.Thread(target=read_err, daemon=bool(REAP_EARLY))
    reader.start()
    exit_code = []  # the status the process reported (apksim.exitMarks) ahead of its teardown
    lines, seconds, started, block, deps = [], {}, {}, {}, []
    marks = {}  # (class, method): [wall time its start block arrived, wall time its end block arrived]
    spawned = time.time()
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
        if line.startswith("APKSIM_EXIT: "):  # not part of the run's output
            try:
                exit_code.append(int(line.split(": ")[1]))
            except ValueError:
                pass
            if REAP_EARLY and any(l.startswith("INSTRUMENTATION_CODE: ") for l in reversed(lines)) and not quit_sent.is_set():
                break
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
                marks[t] = [started[t], None]
            elif t in started:
                now = time.time()
                seconds[t] = now - started.pop(t)
                marks[t][1] = now
            block = {}
    dumped.set()
    early = bool(exit_code) and p.poll() is None and REAP_EARLY > 0 and not timed_out
    if not early:
        p.wait()
    exited = time.time()
    done.set()
    if dog:
        dog.join()  # (a kill under way reads the cidfile: the client's end, closing its output, comes first)

    def reap():
        """Early: the result and the outputs are complete, so the slot is free; the process's teardown (class cache
        save, scratch removal) goes on here, for REAP_EARLY s at most, then the process group is killed."""
        drain = threading.Thread(target=lambda: p.stdout.read(), daemon=True)  # (a full pipe must not block its teardown)
        drain.start()
        try:
            p.wait(timeout=REAP_EARLY)
        except subprocess.TimeoutExpired:
            warn(f"instrument.py: a process still tearing down {REAP_EARLY:g} s after its result was killed")
            kill()
            p.wait()
        # The launcher is gone, but a child may hold its pipes: the readers get the deadline too, then the process
        # group is killed (which closes them), then a last short wait; a reader still stuck is a daemon-less leak we
        # leave to the OS (the thread is made a daemon below), not a hang of this script.
        end = time.monotonic() + REAP_EARLY
        for t in (drain, reader):
            t.join(max(0.0, end - time.monotonic()))
        if drain.is_alive() or reader.is_alive():
            warn(f"instrument.py: a process kept its output open {REAP_EARLY:g} s after its result: its group was killed")
            kill()
            for t in (drain, reader):
                t.join(5)
        late = " | ".join(re.findall(r"^\[apksim\] (?:exit|halt) marks: (.*)$", "".join(err), re.M))
        if EXIT_MARKS and late:  # (the test's line is long printed: the slot was not held for these)
            name = "#".join(next(iter(marks), ("?", "?")))
            print(f"      exit (after the result) {name}: {late}", file=sys.stderr)
        after_exit()

    def after_exit():
        if cidfile and p.returncode < 0 and not timed_out:
            with kill_lock:  # the client died of a signal, not of its container's end: the container may live on
                if CLEANUP_DELAY:
                    time.sleep(CLEANUP_DELAY)
                remove_container()
        with LIVE_LOCK:
            LIVE.pop(p, None)
        if user_cidfile:
            try:
                c = container_id()
                if c:  # (beside it, then renamed over it: a run of shards leaves the last one's, whole)
                    tmp = f"{user_cidfile}.{os.getpid()}.{threading.get_ident()}"
                    with open(tmp, "w") as f:
                        f.write(c)
                    os.replace(tmp, user_cidfile)
            except OSError as e:
                warn(f"instrument.py: could not write the container ID to --cidfile {user_cidfile} ({e})")
        if cid_dir and not STOPPING:  # (a stop's kill may still read it; this script ends with it anyway)
            shutil.rmtree(cid_dir, ignore_errors=True)

    if early:
        # Its status is the one it reported. A background thread, not a daemon: this script waits for it at the end
        # (REAPERS), so no process outlives the run.
        t = threading.Thread(target=reap)
        t.start()
        REAPERS.append(t)
        returncode = exit_code[0]
    else:
        reader.join()
        after_exit()
        returncode = p.returncode
    stopping = bool(STOPPING)
    if deps:
        RECORDED.append(deps)
    r = subprocess.CompletedProcess(p.args, returncode, "".join(lines), "".join(err))
    r.exit_marks = " | ".join(re.findall(r"^\[apksim\] (?:exit|halt) marks: (.*)$", r.stderr, re.M))
    r.seconds = seconds
    # Where a process's time went: spawn to the test's start block (JVM, framework, app state), and the test's end
    # block to the process exiting (listeners, shutdown, the launcher's exit).
    r.phases = {t: (m[0] - spawned, m[1] - m[0], exited - m[1]) for t, m in marks.items() if m[1] is not None}
    r.timed_out = timed_out[0] if timed_out else None
    r.threads = thread_dump_tests(dump) if dump else ""
    return r


def interrupted(signum, frame):
    """SIGINT/SIGTERM/SIGHUP: the test processes have process groups of their own, so a Ctrl-C at the terminal no
    longer reaches them: end them (and their containers), then this script, with the signal's status. If the main
    thread was starting a process, that is left to it, once the process is in LIVE; once a stop is under way, another
    signal is ignored (the stop goes on to its exit)."""
    if IN_STOP[0]:
        return
    with LIVE_LOCK:
        STOPPING[:] = STOPPING or [signum]
        if MAIN_SPAWNING[0]:
            return
    stop(signum)


def stop(signum):
    IN_STOP[0] = True
    with LIVE_LOCK:  # (waits for a worker thread starting a process; none starts after this)
        STOPPING[:] = STOPPING or [signum]
        kills = list(LIVE.values())
    for k in kills:
        k()
    os._exit(128 + signum)


def warn(text):
    """A line on stderr without the stream's lock, which a signal handler interrupting a write would find held."""
    os.write(2, (text + "\n").encode(errors="replace"))


# The watchdogs' lines, each followed by the stuck thread's stack: FailOnTimeout's before it ends the process, and
# TestRunnerSetup.ListenerWatch's for a run listener's call that does not return (issue 115).
WATCHDOGS = ("[apksim] test still running ", "[apksim] listener call still running")
# A line inside a stack trace, after its header (an exception's or a thread's line).
FRAME = re.compile(r"\s+at |\s*\.\.\. \d+ more|\s*Caused by: |\s*Suppressed: |\s+- (locked|waiting|parking) |\t\[\.\.\.")
NESTED = re.compile(r"\s*(Caused by|Suppressed): ")
# The first line of a stack's header: a thread dump's thread, an exception (a class name, then its message or
# nothing; "Exception in thread" first when the JVM printed it), or a nested cause. The message may go on over more
# lines, up to the first frame (a VerifyError's "Exception Details:").
HEADER = re.compile(r'"[^"]*" |(Exception in thread "[^"]*" )?([A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(:|\s*$)'
                    r"|\s*(Caused by|Suppressed): ")
# Lines of a stack trace kept from its top when the tail would start inside it (and of a thread in a thread dump).
STACK_LINES = 100
# How far a header's message may reach above its first frame, in lines.
HEADER_LINES = 60


def is_frame(line):
    return bool(FRAME.match(line))


def is_call(line):
    """A frame proper: a line of a stack after its header and message (not a nested cause's header)."""
    return is_frame(line) and not NESTED.match(line)


def header_start(lines, f):
    """Where the header of the stack whose frames start at [f] begins: the nearest header line above it, across its
    message's lines, else the line just above (an unknown header)."""
    for j in range(f - 1, max(f - 1 - HEADER_LINES, -1), -1):
        if HEADER.match(lines[j]):
            return j
        if is_call(lines[j]):
            break
    return max(f - 1, 0)


def stack_head(lines, i):
    """The first line of the outermost stack that line [i] belongs to: back over its frames to its header, and
    from a nested cause's header on to the stack that it is the cause of."""
    while True:
        while i > 0 and is_frame(lines[i - 1]):
            i -= 1
        h = header_start(lines, i) if i > 0 else 0
        if h == 0 or not NESTED.match(lines[h]):
            return h
        i = h


def stack_end(lines, h):
    """Past the last line of the stack that starts at header line [h]: its message, frames and nested causes."""
    j, in_header = h + 1, True
    while j < len(lines):
        if is_frame(lines[j]):
            in_header = bool(NESTED.match(lines[j]))
        elif not in_header:
            break
        j += 1
    return j


def cap_line(line, limit):
    """[line] at most about [limit] characters, its middle cut: a generated class's name can be longer than a tail."""
    if len(line) <= limit:
        return line
    nl = "\n" if line.endswith("\n") else ""
    return line[:limit * 3 // 4] + " [...] " + line[len(line) - len(nl) - limit // 4:len(line) - len(nl)] + nl


def trimmed_stack(lines, keep=STACK_LINES, chars=None):
    """A stack trace's lines (its header first) cut at the bottom to [keep] lines (and, given [chars], about that many
    characters), saying how many went: the top of a stack says what the thread is doing, the bottom is how it got
    there (the runner's frames, the same every time)."""
    # An exception's `Caused by:` and `Suppressed:` each start a stack of their own (a message, a throw site): each is
    # cut by itself, so a deep wrapper does not take the cause's header and frames with it. A header and its message
    # are kept whole, and at least one frame after them.
    starts = [i for i, line in enumerate(lines) if i > 0 and NESTED.match(line)]
    budget = chars // (len(starts) + 1) if chars else None
    out = []
    for a, b in zip([0] + starts, starts + [len(lines)]):
        seg = lines[a:b]
        n = next((k for k, line in enumerate(seg) if is_call(line)), len(seg))  # the header's lines
        size = sum(len(line) for line in seg[:n])
        stop = n
        while stop < len(seg) and (stop == n or (stop < max(keep, n + 1)
                                                 and (budget is None or size + len(seg[stop]) <= budget))):
            size += len(seg[stop])
            stop += 1
        out += seg if stop == len(seg) else seg[:stop] + [f"\t[... {len(seg) - stop} more lines of this stack]\n"]
    return "".join(out)


def stderr_tail(text, n=4000):
    """The end of a process's stderr, about n characters, from the start of a line. From the start of a watchdog's
    line when that is further back: the stuck test's stack after it can be longer than n (one caught inside a
    StackWalker was), which cut the line that says why the process ended; past 4n, the middle of the stack goes. And
    when the tail would start inside a stack trace, it starts at that stack's header instead, the stack cut at the
    bottom: cut at the top (issue 115), it began mid-frame and lost the thread or exception and the frames that showed
    what it waited on."""
    # One rule for every shape of stack: each line is first capped at n/4 characters (its middle cut), so the tail
    # always holds several whole lines, whatever follows the stack. If the first of them is part of a stack (a frame,
    # a nested cause, or a header's message line above its first frame), the tail starts at that stack's outermost
    # header (its first line, the message whole), and the stack is cut at the bottom of each of its segments
    # (trimmed_stack, to 4n characters in all), never at its top.
    limit = max(n // 4, 80)
    lines = [cap_line(line, limit) for line in text.splitlines(keepends=True)]
    text = "".join(lines)
    i = max(text.rfind(w) for w in WATCHDOGS)
    if i >= 0 and i < len(text) - n:
        t = text[text.rfind("\n", 0, i) + 1:]
        return t if len(t) <= 4 * n else t[:3 * n] + "\n\t[...]\n" + t[-n:]
    if len(text) <= n:
        return text
    start, size = len(lines), 0
    while start > 0 and size + len(lines[start - 1]) <= n:
        start -= 1
        size += len(lines[start])
    # Does the tail start inside a stack? At a frame, or above one within a header's reach (in its message).
    f = start
    while f < len(lines) and f - start < HEADER_LINES and not is_frame(lines[f]):
        f += 1
    if f == len(lines) or not is_frame(lines[f]):
        return "".join(lines[start:])
    head = stack_head(lines, f)
    if head >= start:  # the tail holds that stack's header already
        return "".join(lines[start:])
    end = stack_end(lines, head)
    if head > 0 and lines[head - 1].strip() and not is_frame(lines[head - 1]):
        head -= 1  # and the line before it, which may say what the stack is about
        # (CloseGuard's "A resource was acquired at attached stack trace but never released")
    return trimmed_stack(lines[head:end], chars=4 * n) + "".join(lines[end:])


def tail(r):
    """What a failure that this script reports for a process shows of it: the tail of its stderr (stderr_tail), and
    after a --test-timeout kill the stacks of the threads running JUnit code when it was killed, first: they are where
    the process was stuck. A stack at the end of stderr need not be (issue 115's was a CloseGuard report, printed at a
    GC, of a descriptor a listener's executeShellCommand returned and never closed: that call had long returned)."""
    t = stderr_tail(r.stderr)
    if r.threads:
        t = ("threads in JUnit code when instrument.py killed the process (from the JVM's thread dump):\n"
             + r.threads.rstrip("\n") + "\n\nthe end of the process's stderr:\n" + t)
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


def clear_recordings(local):
    """Removes an earlier run's movies from DIR (<class>/<method>-<8 hex>.avi), once before this run starts."""
    if not os.path.isdir(local):
        return
    for c in os.listdir(local):
        cdir = os.path.join(local, c)
        if os.path.islink(cdir) or not os.path.isdir(cdir):
            continue
        for f in os.listdir(cdir):
            if re.search(r"-[0-9a-f]{8}\.avi$", f) and os.path.isfile(os.path.join(cdir, f)):
                os.remove(os.path.join(cdir, f))
        if not os.listdir(cdir):
            os.rmdir(cdir)


def collect_recordings(local, results):
    """{(class, method): path} of the movies this run kept."""
    out = {}
    for c, m, *_ in results:
        f = os.path.join(local, bundle_folder(c, m) + ".avi")
        if c and os.path.isfile(f):
            out[(c, m)] = f
    return out


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


DROPPED_CLASS_ERRORS = set()  # classes whose listed initializationError was left out


def drop_class_errors(a, tests):
    """As AndroidTestOrchestrator: its runner side drops a listed `initializationError` (JUnitValidator), so such a class
    (a Parameterized one with no parameters) is never run or reported. --keep-class-errors keeps them."""
    if a.keep_class_errors:
        return tests
    dropped = sorted({c for c, m in tests if m == "initializationError"})
    DROPPED_CLASS_ERRORS.update(dropped)
    for c in dropped:
        print(f"leaving out {c}#initializationError, as the orchestrator does (--keep-class-errors keeps it)", file=sys.stderr)
    return [t for t in tests if t[1] != "initializationError"]


def list_suite(a, passthrough):
    """The suite as a.e names it, listed by apksim (`-e log true`): [(class, method)], or exits if it cannot be listed."""
    listed = apksim(a.app, a.test, a.e + [("log", "true")], passthrough)
    why = incomplete(listed, parse(listed.stdout))
    if why:
        sys.stderr.write(listed.stderr[-4000:])
        sys.exit(f"listing the suite failed: {why}")
    return drop_class_errors(a, [(c, m) for c, m, _, _ in parse(listed.stdout)])


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
    global TEST_TIMEOUT, REAP_EARLY, EXIT_MARKS
    argv = sys.argv[1:]
    passthrough = []
    if "--" in argv:
        passthrough = argv[argv.index("--") + 1:]
        argv = argv[:argv.index("--")]
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("--isolate", action="store_true")
    p.add_argument("--keep-class-errors", action="store_true",
                   help="keep listed initializationError entries in --isolate's and --shards' suites (the orchestrator drops them)")
    p.add_argument("-j", type=int, default=max(1, min(4, (os.cpu_count() or 2) // 3)))
    p.add_argument("--shards", type=int, default=1)
    p.add_argument("--xml")
    p.add_argument("--apksim")
    p.add_argument("-e", nargs=2, action="append", default=[], metavar=("KEY", "VALUE"))
    p.add_argument("--bundles")
    p.add_argument("--bundles-local")
    p.add_argument("--record", choices=["failed", "all"])
    p.add_argument("--record-dir", metavar="DIR")
    p.add_argument("--record-local", metavar="PATH")
    p.add_argument("--record-size", metavar="WxH")
    p.add_argument("--record-fps", type=int, metavar="N")
    p.add_argument("--record-deps", metavar="FILE")
    p.add_argument("--affected-by", metavar="FILE")
    p.add_argument("--changed", nargs="+", action="extend", default=[], metavar="PATH")
    p.add_argument("--diff", metavar="REV")
    p.add_argument("--repo", default=".")
    p.add_argument("--ignore", action="append", default=[], metavar="GLOB")
    p.add_argument("--timings", action="append", default=[], metavar="XML")
    p.add_argument("--shard", type=int, metavar="I")
    p.add_argument("--exit-marks", action="store_true",
                   help="--isolate: show, per test, where its process's exit time went (apksim's profile, class cache, "
                        "scratch removal, cache sweep, and the threads still alive)")
    p.add_argument("--reap-early", type=float, default=0, metavar="SEC",
                   help="--isolate: a process counts as done, and its slot free, once it has printed its result and "
                        "exit status; its teardown goes on in the background for SEC s at most, then it is killed (0: off)")
    p.add_argument("--order", choices=("interleave", "longest-first", "suite"), default="interleave",
                   help="--isolate --timings: the order tests start in (default interleave: alternately the longest and shortest)")
    p.add_argument("--test-timeout", type=float, default=TEST_TIMEOUT, metavar="SEC")
    a = p.parse_args(argv)
    if a.test_timeout < 0:
        p.error("--test-timeout SEC: 0 for no limit, else the seconds a process may go without a test starting or ending")
    TEST_TIMEOUT = a.test_timeout
    REAP_EARLY, EXIT_MARKS = (a.reap_early if a.isolate else 0), a.exit_marks
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
    if a.record:
        if not a.record_dir:
            p.error("--record needs --record-dir DIR")
        if a.record_size and not re.fullmatch(r"\d+x\d+", a.record_size):
            p.error("--record-size WxH, e.g. 1280x720")
        if a.record_fps is not None and not 1 <= a.record_fps <= 30:
            p.error("--record-fps N: 1 to 30")
        passthrough = passthrough + ["--record", a.record, "--record-dir", a.record_dir]
        if a.record_size:
            passthrough += ["--record-size", a.record_size]
        if a.record_fps:
            passthrough += ["--record-fps", str(a.record_fps)]
        os.makedirs(a.record_local or a.record_dir, exist_ok=True)
        clear_recordings(a.record_local or a.record_dir)
    elif a.record_dir or a.record_local or a.record_size or a.record_fps:
        p.error("--record-dir, --record-local, --record-size and --record-fps need --record failed|all")
    if a.apksim:
        LAUNCHER[:] = shlex.split(a.apksim)

    t0 = time.time()
    results = []  # (class, method, status, stack, seconds)
    if a.shards > 1 and a.isolate and a.shard is None:
        p.error("--shards with --isolate splits the tests over machines, and each runs its own: give --shard I")
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
    if a.shards > 1 and not a.isolate and (timings or selected is not None):
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
    elif a.shards > 1 and not a.isolate:
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
        tests = drop_class_errors(a, [(c, m) for c, m, _, _ in parse(listed.stdout)])
        why = incomplete(listed, parse(listed.stdout))
        if why:
            sys.stderr.write(listed.stderr[-4000:])
            results.append(("(listing)", "incomplete", "failed", why + ":\n" + listed.stderr[-4000:], None))
        print(f"{len(tests)} tests, {a.j} at a time, each in its own process", file=sys.stderr)

    if a.isolate:
        if a.shards > 1:  # this machine's share: balanced by measured time (by count without timings)
            if any(k in ("numShards", "shardIndex") for k, _ in a.e):
                p.error("--shards sets which tests each shard runs itself")
            lists, totals = affected.balance(tests, timings, a.shards)
            print(f"{a.shards} shards by " + ("measured time" if timings else "count") + ", predicted "
                  + ", ".join(f"{t:.0f} s" for t in totals) + f"; running shard {a.shard}", file=sys.stderr)
            tests = lists[a.shard]
        suite_order = {t: i for i, t in enumerate(tests)}
        order = a.order if timings else "suite"
        if order == "longest-first":
            tests = affected.longest_first(tests, timings)
        elif order == "interleave":
            tests = affected.interleaved(tests, timings)
        print(f"{len(tests)} tests to run" + ("" if order == "suite" else f", {order.replace('-', ' ')}"), file=sys.stderr)

        def one(t):
            start = time.time()
            others = [kv for kv in a.e if kv[0] not in ("class", "package")]
            r = apksim(a.app, a.test, others + [("class", f"{escape_selector(t[0])}#{escape_selector(t[1])}")], passthrough)
            got = parse(r.stdout)
            why = incomplete(r, got)
            if r.exit_marks:
                MARKS[t] = r.exit_marks
            if t in r.phases:  # (kept even when the process then died or hung at exit: that is when it matters)
                PHASES[t] = r.phases[t]
            if not got or why:
                return (t[0], t[1], "failed", (why or "apksim exited without a result") + ":\n" + tail(r),
                        time.time() - start)
            c, m, s, st = got[0]
            return (c, m, s, st, time.time() - start)

        with concurrent.futures.ThreadPoolExecutor(a.j) as pool:
            for res in pool.map(one, tests):
                results.append(res)
                ph = PHASES.get((res[0], res[1]))
                print(f"  {res[2]:8} {res[0]}#{res[1]}  {res[4]:.1f} s"
                      + (f" (start-up {ph[0]:.1f} s, test {ph[1]:.1f} s, exit {ph[2]:.1f} s)" if ph else ""),
                      file=sys.stderr)
                if EXIT_MARKS and MARKS.get((res[0], res[1])):
                    print(f"      exit: {MARKS[(res[0], res[1])]}", file=sys.stderr)
        results.sort(key=lambda r: suite_order.get((r[0], r[1]), len(suite_order)))  # (reported in the suite's order)

    failed = [r for r in results if r[2] == "failed"]
    for c, m, s, st, _ in failed:
        print(f"\nFAILED {c}#{m}\n{st}", file=sys.stderr)
    counts = {s: sum(1 for r in results if r[2] == s) for s in sorted({r[2] for r in results})}
    print(f"{len(results)} tests in {time.time() - t0:.1f} s: " + ", ".join(f"{n} {s}" for s, n in counts.items()))
    bundles = collect_bundles(a.bundles_local or a.bundles, results, redact) if a.bundles else {}
    if a.bundles:
        write_index(a.bundles_local or a.bundles, results, bundles, redact)
        print(f"{len(bundles)} failure bundles: {os.path.join(a.bundles_local or a.bundles, 'index.html')}", file=sys.stderr)
    recordings = collect_recordings(a.record_local or a.record_dir, results) if a.record else {}
    if a.record:
        print(f"{len(recordings)} recordings in {a.record_local or a.record_dir}", file=sys.stderr)
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
                movie = recordings.get((c, m))
                mrel = os.path.relpath(movie, xml_dir) if movie else None
                ph = PHASES.get((c, m))
                if rel or mrel or ph:
                    f.write("<properties>"
                            + (f'<property name="apksim.startup_seconds" value="{ph[0]:.3f}"/>'
                               f'<property name="apksim.exit_seconds" value="{ph[2]:.3f}"/>' if ph else "")
                            + (f'<property name="apksim.bundle" value={quoteattr(rel)}/>' if rel else "")
                            + (f'<property name="apksim.recording" value={quoteattr(mrel)}/>' if mrel else "")
                            + "</properties>")
                if s == "failed":
                    f.write(f"<failure message={quoteattr(st.splitlines()[0] if st else '')}>{escape(st)}</failure>")
                elif s in ("ignored", "skipped"):
                    f.write("<skipped/>")
                if rel or mrel:
                    # GitLab's and Jenkins' JUnit attachments: a screenshot, and the movie, shown with the test.
                    att = []
                    if rel:
                        shot = "screen.png" if os.path.exists(os.path.join(bundle, "screen.png")) else "failure.txt"
                        att.append(f"[[ATTACHMENT|{os.path.join(rel, shot)}]]")
                    if mrel:
                        att.append(f"[[ATTACHMENT|{mrel}]]")
                    f.write(f"<system-out>{escape(chr(10).join(att))}</system-out>")
                f.write("</testcase>\n")
            f.write("</testsuite>\n")
    if a.record_deps:
        affected.write_deps(a.record_deps, RECORDED, [a.app, a.test])
    if selected is not None and not selected:
        print("no test is affected by the change", file=sys.stderr)
        sys.exit(0)
    # No results at all fails a run (no tests found), except for a shard, which can be empty by design (more shards
    # than tests, a selection smaller than the CI matrix): a process that did not finish has a "(run)" entry anyway.
    # A suite that listed and held nothing but left-out initializationError entries is a run of no tests, as the
    # orchestrator's is.
    if not results and DROPPED_CLASS_ERRORS:
        print("no tests left to run after leaving out initializationError entries", file=sys.stderr)
        sys.exit(0)
    sys.exit(1 if failed or (not results and a.shards == 1) else 0)


main()
