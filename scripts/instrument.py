#!/usr/bin/env python3
"""Runs an app's androidTest APK on apksim, as `am instrument` (with the AndroidX Test Orchestrator, --isolate).

Usage: instrument.py APP.apk TEST.apk [--isolate] [-j N] [--xml FILE] [-e KEY VALUE]... [--apksim CMD]
                     [-- APKSIM_OPTION...]

Without --isolate every test runs in one process (Robolectric resets the framework and app data between tests, but
the app's own statics carry over). With --isolate each test runs in a process of its own, N at a time, as the
Orchestrator with clearPackageData does. -e passes am instrument arguments (class, notClass, package, notPackage).
Options after -- go to apksim, e.g. `-- --device tablet`. Exits 1 if any test failed.
--apksim runs another apksim, such as the Linux image: `--apksim "docker run --rm -v $PWD/out:/work apksim:ci"`
with APP and TEST given relative to the mounted folder (the image's working directory).
"""
import argparse, concurrent.futures, os, re, shlex, subprocess, sys, time
from xml.sax.saxutils import escape, quoteattr

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
CODES = {0: "passed", -2: "failed", -3: "ignored", -4: "skipped"}


LAUNCHER = [ROOT + "/apksim"]


def apksim(app, test, extra_args, apksim_opts):
    cmd = LAUNCHER + ["--instrument", test]
    for k, v in extra_args:
        cmd += ["-e", k, v]
    return subprocess.run(cmd + apksim_opts + [app], capture_output=True, text=True)


def parse(raw):
    """am instrument -r output -> [(class, method, status, stack)] for each finished test."""
    out, block, key = [], {}, None
    for line in raw.splitlines():
        m = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        if m:
            key = m[1]
            block[key] = m[2]
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ")[1])
            if code != 1:
                out.append((block.get("class"), block.get("test"), CODES.get(code, f"code {code}"), block.get("stack", "")))
            block, key = {}, None
        elif line.startswith("INSTRUMENTATION_"):
            key = None
        elif key:
            block[key] += "\n" + line
    return out


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
    p.add_argument("--xml")
    p.add_argument("--apksim")
    p.add_argument("-e", nargs=2, action="append", default=[], metavar=("KEY", "VALUE"))
    a = p.parse_args(argv)
    if a.apksim:
        LAUNCHER[:] = shlex.split(a.apksim)

    t0 = time.time()
    results = []  # (class, method, status, stack, seconds)
    if not a.isolate:
        r = apksim(a.app, a.test, a.e, passthrough)
        results = [(c, m, s, st, None) for c, m, s, st in parse(r.stdout)]
        if not results:
            sys.stderr.write(r.stderr[-4000:])
    else:
        listed = apksim(a.app, a.test, a.e + [("log", "true")], passthrough)
        tests = [(c, m) for c, m, _, _ in parse(listed.stdout)]
        if not tests:
            sys.stderr.write(listed.stderr[-4000:])
        print(f"{len(tests)} tests, {a.j} at a time, each in its own process", file=sys.stderr)

        def one(t):
            start = time.time()
            others = [kv for kv in a.e if kv[0] not in ("class", "package")]
            r = apksim(a.app, a.test, others + [("class", f"{t[0]}#{t[1]}")], passthrough)
            got = parse(r.stdout)
            if not got:
                return (t[0], t[1], "failed", "apksim exited without a result:\n" + r.stderr[-4000:], time.time() - start)
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
