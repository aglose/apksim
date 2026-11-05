#!/usr/bin/env python3
"""Runs an app's androidTest APK on apksim, as `am instrument` (with the AndroidX Test Orchestrator, --isolate).

Usage: instrument.py APP.apk TEST.apk [--isolate] [-j N] [--shards N] [--xml FILE] [-e KEY VALUE]... [--apksim CMD]
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
-e passes am instrument arguments (class, notClass, package, notPackage, and any others the tests read).
Options after -- go to apksim, e.g. `-- --device tablet`. Exits 1 if any test failed, or if a process did not account
for its tests (no INSTRUMENTATION_CODE, fewer results than numtests, or a failing exit with no failed test) and no
test was running to put that on (it died between tests, at startup or at exit): the report then lists a failed
"incomplete" entry for that process, even when the tests left all pass in the next one.
--apksim runs another apksim, such as the Linux image: `--apksim "docker run --rm -v $PWD/out:/work apksim:ci"`
with APP and TEST given relative to the mounted folder (the image's working directory).
"""
import argparse, concurrent.futures, os, re, shlex, subprocess, sys, threading, time
from xml.sax.saxutils import escape, quoteattr

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
CODES = {0: "passed", -2: "failed", -3: "ignored", -4: "skipped"}


LAUNCHER = [ROOT + "/apksim"]


def apksim(app, test, extra_args, apksim_opts):
    """Runs apksim --instrument; its output as subprocess.run's, plus .seconds: {(class, method): how long the test ran},
    from when each test's start and end blocks arrived (the output is read as it comes)."""
    cmd = LAUNCHER + ["--instrument", test]
    for k, v in extra_args:
        cmd += ["-e", k, v]
    p = subprocess.Popen(cmd + apksim_opts + [app], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    err = []
    reader = threading.Thread(target=lambda: err.append(p.stderr.read()))
    reader.start()
    lines, seconds, started, block = [], {}, {}, {}
    for line in p.stdout:
        lines.append(line)
        m = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if m:
            block[m[1]] = m[2].rstrip("\n")
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            t = (block.get("class"), block.get("test"))
            if line.split(": ")[1].strip() == "1":
                started[t] = time.time()
            elif t in started:
                seconds[t] = time.time() - started.pop(t)
            block = {}
    p.wait()
    reader.join()
    r = subprocess.CompletedProcess(p.args, p.returncode, "".join(lines), err[0] if err else "")
    r.seconds = seconds
    return r


def incomplete(r, results, test=None):
    """Why a run's output does not account for its tests, or None: a run must end with INSTRUMENTATION_CODE and report
    every test it announced (numtests), or the process crashed or was killed partway and the missing tests would
    otherwise vanish from the report. [test] is the one test an isolated run was started for."""
    announced = [int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", r.stdout)]
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
        tail = r.stderr[-4000:]
        crashed = None
        if why:
            sys.stderr.write(tail)
            if running and not has_result(running) and (remaining is None or running in set(remaining)):
                crashed = running
                finished = "INSTRUMENTATION_CODE: " in r.stdout  # an odd case: the run ended, this test did not
                record(*running, "failed", f"apksim exited ({r.returncode}) while this test was running"
                                           + (f" ({why})" if finished else "") + ":\n" + tail)
            elif remaining is None or new:  # (a later process with no result: its tests' "not run" says why)
                # Between tests, at startup or at exit: nothing to put it on, but the run must still fail, as on a
                # device. One entry per such process, named apart so the XML keeps one entry per name.
                name = ("incomplete" if processes == 1 else f"incomplete (process {processes})") + label
                results.append(("(run)", name, "failed", why + ":\n" + tail, None))
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
                                               + tail)
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
            print(f"apksim exited ({r.returncode}){label}{at}: {len(listing) - len(remaining)} of {len(listing)} tests have "
                  f"results, running the {len(remaining)} left in a new process", file=sys.stderr)
    if processes > 1:
        print(f"{processes} apksim processes{label}, one of them listing the suite", file=sys.stderr)
    return results


def main():
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
    a = p.parse_args(argv)
    if a.apksim:
        LAUNCHER[:] = shlex.split(a.apksim)

    t0 = time.time()
    results = []  # (class, method, status, stack, seconds)
    if a.shards > 1 and a.isolate:
        p.error("--shards splits a suite over processes that each run many tests; --isolate gives every test its own"
                " process already (use -j for how many at a time)")
    if a.shards > 1:
        if any(k in ("numShards", "shardIndex") for k, _ in a.e):
            p.error("--shards sets -e numShards and shardIndex itself")
        print(f"{a.shards} shards, one process each, all at once", file=sys.stderr)

        def shard(i):
            sa = argparse.Namespace(**vars(a))
            sa.e = a.e + [("numShards", str(a.shards)), ("shardIndex", str(i))]
            start = time.time()
            got = one_process(sa, passthrough, f" (shard {i + 1} of {a.shards})")
            print(f"  shard {i + 1} of {a.shards}: {len(got)} tests in {time.time() - start:.1f} s", file=sys.stderr)
            return got

        with concurrent.futures.ThreadPoolExecutor(a.shards) as pool:
            for got in pool.map(shard, range(a.shards)):
                results += got
    elif not a.isolate:
        results = one_process(a, passthrough)
    else:
        listed = apksim(a.app, a.test, a.e + [("log", "true")], passthrough)
        tests = [(c, m) for c, m, _, _ in parse(listed.stdout)]
        why = incomplete(listed, parse(listed.stdout))
        if why:
            sys.stderr.write(listed.stderr[-4000:])
            results.append(("(listing)", "incomplete", "failed", why + ":\n" + listed.stderr[-4000:], None))
        print(f"{len(tests)} tests, {a.j} at a time, each in its own process", file=sys.stderr)

        def one(t):
            start = time.time()
            others = [kv for kv in a.e if kv[0] not in ("class", "package")]
            r = apksim(a.app, a.test, others + [("class", f"{escape_selector(t[0])}#{escape_selector(t[1])}")], passthrough)
            got = parse(r.stdout)
            why = incomplete(r, got)
            if not got or why:
                return (t[0], t[1], "failed", (why or "apksim exited without a result") + ":\n" + r.stderr[-4000:],
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
    if a.xml:
        with open(a.xml, "w") as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
            f.write(f'<testsuite name="apksim" tests="{len(results)}" failures="{len(failed)}" '
                    f'skipped="{sum(1 for r in results if r[2] in ("ignored", "skipped"))}">\n')
            for c, m, s, st, sec in results:
                f.write(f'  <testcase classname={quoteattr(c or "")} name={quoteattr(m or "")}'
                        + (f' time="{sec:.3f}"' if sec is not None else "") + ">")
                if s == "failed":
                    f.write(f"<failure message={quoteattr(st.splitlines()[0] if st else '')}>{escape(st)}</failure>")
                elif s in ("ignored", "skipped"):
                    f.write("<skipped/>")
                f.write("</testcase>\n")
            f.write("</testsuite>\n")
    sys.exit(1 if failed or not results else 0)


main()
