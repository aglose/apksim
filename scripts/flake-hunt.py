#!/usr/bin/env python3
"""Hunts flaky tests: runs chosen tests N times under several perturbations and reports each test's pass rate in each.

Usage: flake-hunt.py APP.apk TEST.apk [-n N] [--perturb LIST] [--out DIR] [-j J] [--seed S] [--apksim CMD]
                     [-e KEY VALUE]... [--affected-by FILE (--changed PATH... | --diff REV) [--ignore GLOB]...]
                     [-- APKSIM_OPTION...]

Which tests: what `-e class/notClass/package` (or --affected-by with --changed/--diff, as instrument.py takes them)
names; a listing (-e log true: nothing runs) finds them, and each run names them. A class-level failure (@BeforeClass,
Class#null) counts against each of its tests. Every run goes through
instrument.py, so a process that crashes or hangs is handled as there (--test-timeout, reruns in a new process).
Perturbations (--perturb, comma-separated; default all):
  suite          one process, the suite's order: the baseline
  shuffle        one process, the order shuffled (classes, and methods within each class, as JUnit runs them): the
                 first run is the suite's order reversed, so every pair of tests runs both ways round once
                 (with "suite"), the others shuffled from --seed. App and test statics carry over within a process,
                 so a test that depends on what ran before it fails here.
  isolate        a process per test (instrument.py --isolate -j J, its pre-warmed pool on)
  oversubscribe  one process, the suite's order, with 2 busy processes per CPU running beside it
  realtime       one process, the suite's order, with --realtime-clock (the simulated clock follows real time)
A test that failed in a shuffled run and passes alone is checked for order dependence: the tests before it in that run
are run again with it, in the same order, and reduced by delta debugging (ddmin) to the smallest set that still makes it
fail; the culprits and a command that reproduces the failure are printed. A failure that order does not explain is
reported as timing-dependent or nondeterministic, with the perturbations it showed under.
--out DIR (default flake-hunt): flake-hunt.xml (JUnit: an entry per test and perturbation, failed when any run failed,
"passed K of N" in the message; an entry per test that is flaky), index.html (the table, the culprits, the
commands), runs/ (each run's JUnit XML and log). Exits 1 when a test is flaky or failing, or a run failed as a whole
(a "(run)" entry: a listener, a process that ended without accounting for its tests) or a class failed at class
level (@BeforeClass/@AfterClass), else 0.
"""
import argparse, html, json, os, random, shlex, signal, subprocess, sys, time
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
INSTRUMENT = os.path.join(HERE, "instrument.py")
PERTURBATIONS = ("suite", "shuffle", "isolate", "oversubscribe", "realtime")


def escape_selector(name):
    """As instrument.py's: a comma in a name is `\\,`, a backslash `\\\\` (apksim's -e class reads them)."""
    return name.replace("\\", "\\\\").replace(",", "\\,")


def names(tests):
    return ",".join(f"{escape_selector(c)}#{escape_selector(m)}" for c, m in tests)


class Hunt:
    def __init__(self, a, passthrough):
        self.a, self.passthrough, self.n = a, passthrough, 0
        self.runs_dir = os.path.join(a.out, "runs")
        os.makedirs(self.runs_dir, exist_ok=True)
        self.trials = {}  # ddmin: tuple of tests -> whether the victim failed

    def command(self, tests=None, order=None, isolate=False, realtime=False, select=None, xml=None, extra=()):
        """instrument.py's command line for one run: [tests] named with -e class (else [select], the user's
        selection), [order] as -e apksimOrder."""
        cmd = [sys.executable, INSTRUMENT, self.a.app, self.a.test]
        if xml:
            cmd += ["--xml", xml]
        if self.a.apksim:
            cmd += ["--apksim", self.a.apksim]
        if self.a.test_timeout is not None:
            cmd += ["--test-timeout", str(self.a.test_timeout)]
        user = [kv for kv in self.a.e if tests is None or kv[0] not in ("class", "notClass", "package", "notPackage")]
        for k, v in user:
            cmd += ["-e", k, v]
        if tests is not None:
            cmd += ["-e", "class", names(tests)]
        if order:
            cmd += ["-e", "apksimOrder", names(order)]
        for k, v in extra:
            cmd += ["-e", k, v]
        if select:
            cmd += select
        if isolate:
            cmd += ["--isolate", "-j", str(self.a.j)]
        opts = list(self.passthrough) + (["--realtime-clock"] if realtime else [])
        if opts:
            cmd += ["--"] + opts
        return cmd

    def run(self, label, hogs=0, **kw):
        """One instrument.py run: {(class, method): "passed"|"failed"|"skipped"}, its log, how long it took."""
        self.n += 1
        base = os.path.join(self.runs_dir, f"{self.n:03d}-{label}")
        cmd = self.command(xml=base + ".xml", **kw)
        argument = max(len(x.encode("utf-8")) for x in cmd)
        if argument > 100_000:  # (Linux takes at most 128 KB in one argument)
            sys.exit(f"the tests' names make a {argument}-byte argument: hunt in fewer tests at a time")
        for f in (base + ".xml", base + ".log"):  # (an earlier hunt's, in a reused --out, must not be read as this run's)
            if os.path.exists(f):
                os.remove(f)
        ACTIVE.clear()
        hog = start_hogs(hogs)
        t0 = time.time()
        try:
            child = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            ACTIVE.append(child)
            out, err = child.communicate()
            p = subprocess.CompletedProcess(cmd, child.returncode, out, err)
        finally:
            ACTIVE.clear()
            stop_hogs(hog)
        took = time.time() - t0
        with open(base + ".log", "w") as f:
            f.write("$ " + shlex.join(cmd) + "\n" + p.stdout + p.stderr)
        results, ran = {}, []  # ran: the tests in the order the run reported them (its execution order in one process)
        if not os.path.exists(base + ".xml"):
            RUN_FAILURES.append(f"{label}: instrument.py exited {p.returncode} without a report (see {base}.log)")
        else:
            for c in ET.parse(base + ".xml").iter("testcase"):
                t = (c.get("classname"), c.get("name"))
                if t[0].startswith("("):  # instrument.py's "(run)"/"(listing)" entries: the run itself failed
                    results[t] = "failed"
                    RUN_FAILURES.append(f"{label}: {t[0]}#{t[1]} (see {base}.log)")
                    continue
                ran.append(t)
                if t[1] in (None, "null") and c.find("failure") is not None:
                    # (@BeforeClass/@AfterClass: after its tests passed, too, which must not leave the hunt "stable")
                    RUN_FAILURES.append(f"{label}: {t[0]} failed at class level (see {base}.log)")
                f = c.find("failure")
                results[t] = "failed" if f is not None else "skipped" if c.find("skipped") is not None else "passed"
                if f is not None:
                    results.setdefault(("__messages__", None), {})
                    results[("__messages__", None)][t] = (f.get("message") or "")
        messages = results.pop(("__messages__", None), {})
        print(f"  {label}: " + ", ".join(f"{sum(1 for s in results.values() if s == k)} {k}"
                                         for k in ("passed", "failed", "skipped")) + f" ({took:.0f} s)", file=sys.stderr)
        self.ran, self.returncode = ran, p.returncode
        return results, messages, base + ".log"

    def victim_status(self, victim, before):
        """[victim]'s status when [before] run ahead of it, in that order, in one process ("passed", "failed",
        "skipped" or "missing"); a class-level failure of its class counts as its own."""
        key = (victim, tuple(before))
        if key not in self.trials:
            order = list(before) + [victim]
            got, _, _ = self.run("ddmin", tests=order, order=order)
            # (strict: a run that failed before the victim ran is no reproduction, so it does not count as its failure)
            self.trials[key] = status_of(got, victim, strict=True)
        return self.trials[key]

    def victim_fails(self, victim, before):
        """Delta debugging's test: does [victim] fail when [before] run ahead of it?"""
        return self.victim_status(victim, before) == "failed"


def status_of(got, t, strict=False):
    """[t]'s status in a run's results: its own, else its class's class-level failure (@BeforeClass: Class#null), else
    (not [strict]) failed when the run failed as a whole, else missing."""
    if t in got:
        return got[t]
    if got.get((t[0], "null")) == "failed" or got.get((t[0], None)) == "failed":
        return "failed"
    return "failed" if not strict and any(k[0].startswith("(") for k in got) else "missing"


RUN_FAILURES = []  # runs that failed as a whole (a "(run)" entry, no report): the hunt fails with them
ACTIVE = []  # the busy processes and the instrument.py child of the run under way, for a signal


def stopped(signum, frame):
    """SIGTERM/SIGHUP/SIGINT: the busy processes are in sessions of their own and would outlive us; instrument.py stops
    its own test processes on SIGTERM."""
    for x in list(ACTIVE):
        try:
            x.kill() if "-c" in x.args else x.terminate()  # (a busy process, or instrument.py: it stops its own)
        except OSError:
            pass
    for x in list(ACTIVE):
        try:
            x.wait(30)
        except subprocess.TimeoutExpired:
            x.kill()
    os._exit(128 + signum)


def ddmin(items, fails):
    """Zeller's ddmin: a 1-minimal subset of [items] (kept in their order) for which fails(subset) holds; fails(items)
    must hold."""
    n = 2
    while len(items) >= 2:
        size = len(items) // n
        chunks = [items[i:i + size] for i in range(0, len(items), size)] if size else [[x] for x in items]
        reduced = False
        for c in chunks:
            if fails(c):
                items, n, reduced = c, 2, True
                break
        if not reduced:
            for c in chunks:
                rest = [x for x in items if x not in c]
                if rest and fails(rest):
                    items, n, reduced = rest, max(n - 1, 2), True
                    break
        if not reduced:
            if n >= len(items):
                break
            n = min(len(items), n * 2)
    return items


def start_hogs(n):
    """[n] processes spinning a CPU each (the oversubscribed CPU), in their own process groups."""
    hogs = []
    try:
        for _ in range(n):
            hogs.append(subprocess.Popen([sys.executable, "-c", "while True: pass"], start_new_session=True,
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            ACTIVE.append(hogs[-1])  # (at once: a signal while the rest start must find this one)
    except OSError:  # (a process limit: the ones started must not outlive the hunt)
        stop_hogs(hogs)
        raise
    return hogs


def stop_hogs(hogs):
    for h in hogs:
        h.kill()
    for h in hogs:
        h.wait()


def shuffled(tests, rng, reverse=False):
    """[tests] in an order a run can take: classes in some order, each class's methods together (JUnit runs a class's
    tests as one block), in some order."""
    by_class = {}
    for c, m in tests:
        by_class.setdefault(c, []).append((c, m))
    classes = list(by_class)
    if reverse:
        return [t for c in reversed(classes) for t in reversed(by_class[c])]
    rng.shuffle(classes)
    out = []
    for c in classes:
        ms = list(by_class[c])
        rng.shuffle(ms)
        out += ms
    return out


def main():
    argv = sys.argv[1:]
    passthrough = []
    if "--" in argv:
        passthrough, argv = argv[argv.index("--") + 1:], argv[:argv.index("--")]
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("-n", type=int, default=5, help="runs per perturbation (default 5)")
    p.add_argument("--perturb", default=",".join(PERTURBATIONS))
    p.add_argument("--out", default="flake-hunt")
    p.add_argument("-j", type=int, default=max(1, min(4, (os.cpu_count() or 2) // 3)))
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--apksim")
    p.add_argument("--test-timeout", type=float)
    p.add_argument("--hogs", type=int, default=2 * (os.cpu_count() or 2), help="oversubscribe: busy processes (default 2 a CPU)")
    p.add_argument("-e", nargs=2, action="append", default=[], metavar=("KEY", "VALUE"))
    p.add_argument("--affected-by")
    p.add_argument("--changed", nargs="+", action="extend", default=[])
    p.add_argument("--diff")
    p.add_argument("--repo")
    p.add_argument("--ignore", action="append", default=[])
    a = p.parse_args(argv)
    for sig in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
        signal.signal(sig, stopped)
    perturbs = [x.strip() for x in a.perturb.split(",") if x.strip()]
    bad = [x for x in perturbs if x not in PERTURBATIONS]
    if bad or not perturbs or a.n < 1:
        p.error(f"--perturb takes {', '.join(PERTURBATIONS)}; -n at least 1")
    select = []
    if a.affected_by:
        select = ["--affected-by", a.affected_by] + (["--changed"] + a.changed if a.changed else []) \
            + (["--diff", a.diff] if a.diff else []) + (["--repo", a.repo] if a.repo else []) \
            + [x for g in a.ignore for x in ("--ignore", g)]
    os.makedirs(a.out, exist_ok=True)
    hunt = Hunt(a, passthrough)
    t0 = time.time()
    rng = random.Random(a.seed)

    # The tests, from a listing (-e log true: nothing runs, so a failing @BeforeClass cannot hide its class's methods).
    print("listing the tests:", file=sys.stderr)
    listed, _, _ = hunt.run("list", select=select, extra=[("log", "true")])
    tests = [t for t in listed if not t[0].startswith("(") and t[1] not in (None, "null")]
    if not tests and hunt.returncode == 0 and not RUN_FAILURES:
        # (instrument.py's report of no tests: --affected-by found none the change reaches)
        print("no tests selected: nothing to hunt in", file=sys.stderr)
        write_xml(os.path.join(a.out, "flake-hunt.xml"), [], perturbs, {}, {}, {}, [])
        write_html(os.path.join(a.out, "index.html"), [], perturbs, {}, {}, [], hunt.n, time.time() - t0)
        with open(os.path.join(a.out, "verdicts.json"), "w") as f:
            f.write("{}\n")
        sys.exit(0)
    if not tests:
        sys.exit("no tests to hunt in: the listing reported none (see " + hunt.runs_dir + ")")
    hunt.n = 0  # (the listing is not one of the runs counted)
    print(f"{len(tests)} tests, {a.n} runs each of: {', '.join(perturbs)}", file=sys.stderr)
    # results[perturbation][test] = list of statuses; failures: (perturbation, test, order of that run or None, message)
    results = {x: {t: [] for t in tests} for x in perturbs}
    failures = []

    def note(x, got, msgs, order=None):
        for t in tests:
            s = status_of(got, t)
            results[x][t].append(s)
            if s == "failed":
                failures.append((x, t, order, msgs.get(t, msgs.get((t[0], "null"), ""))))

    for x in perturbs:
        print(f"{x}:", file=sys.stderr)
        for i in range(a.n):
            if x == "suite":
                got, msgs, _ = hunt.run(x, tests=tests)
                ran = [t for t in hunt.ran if t in got]
                note(x, got, msgs, ran or None)  # (a suite-order dependency is reduced as a shuffled one is)
            elif x == "shuffle":
                order = shuffled(tests, rng, reverse=(i == 0))
                got, msgs, _ = hunt.run(x, tests=tests, order=order)
                # (what ran, in its order: a class with @FixMethodOrder or Parameterized sets may keep their own)
                note(x, got, msgs, [t for t in hunt.ran if t in got] or order)
            elif x == "isolate":
                got, msgs, _ = hunt.run(x, tests=tests, isolate=True)
                note(x, got, msgs)
            elif x == "oversubscribe":
                got, msgs, _ = hunt.run(x, tests=tests, hogs=a.hogs)
                note(x, got, msgs)
            elif x == "realtime":
                got, msgs, _ = hunt.run(x, tests=tests, realtime=True)
                note(x, got, msgs)

    # Order dependence: a test that failed in a shuffled run (or the suite's order) and passed alone.
    alone_ok = {t: all(s == "passed" for s in results["isolate"][t]) for t in tests} if "isolate" in results else {}
    culprits = {}  # victim -> (culprits, order of the minimal run, command)
    for x, t, order, _ in failures:
        if t in culprits or order is None or t not in order:
            continue
        if "isolate" in results and not alone_ok[t]:
            continue  # fails alone too: not (only) order
        before = order[:order.index(t)]
        print(f"order: {t[0]}#{t[1]} failed after {len(before)} tests; reproducing", file=sys.stderr)
        if not before or not hunt.victim_fails(t, before):
            print("  not reproduced with the same tests before it: not an order dependence", file=sys.stderr)
            continue
        alone = hunt.victim_status(t, [])
        if alone != "passed":  # (an order dependence needs a pass seen alone, not a skip or no result)
            print(f"  {alone} alone: not an order dependence", file=sys.stderr)
            continue
        minimal = ddmin(before, lambda s: hunt.victim_fails(t, s))
        run = minimal + [t]
        cmd = hunt.command(tests=run, order=run)
        culprits[t] = (minimal, run, cmd)
        print(f"  culprit{'s' if len(minimal) > 1 else ''}: " + ", ".join(f"{c}#{m}" for c, m in minimal), file=sys.stderr)
        print("  reproduce: " + shlex.join(cmd), file=sys.stderr)

    # Each test's verdict.
    verdicts = {}
    for t in tests:
        rates = {x: (results[x][t].count("passed"), sum(1 for s in results[x][t] if s != "skipped")) for x in perturbs}
        failed_in = [x for x in perturbs if any(s in ("failed", "missing") for s in results[x][t])]
        passed_in = [x for x in perturbs if "passed" in results[x][t]]
        if t in culprits:
            verdict = "order-dependent"
        elif failed_in and not passed_in:
            verdict = "failing"
        elif failed_in:
            verdict = "timing-dependent" if set(failed_in) <= {"oversubscribe", "realtime"} else "nondeterministic"
        else:
            verdict = "stable"
        verdicts[t] = (verdict, rates, failed_in)

    write_xml(os.path.join(a.out, "flake-hunt.xml"), tests, perturbs, results, verdicts, culprits, failures)
    write_html(os.path.join(a.out, "index.html"), tests, perturbs, verdicts, culprits, failures, hunt.n, time.time() - t0)
    with open(os.path.join(a.out, "verdicts.json"), "w") as f:
        json.dump({f"{c}#{m}": {"verdict": v[0], "rates": {x: list(r) for x, r in v[1].items()}, "failedIn": v[2],
                                "culprits": [f"{cc}#{mm}" for cc, mm in culprits[(c, m)][0]] if (c, m) in culprits else [],
                                "reproduce": shlex.join(culprits[(c, m)][2]) if (c, m) in culprits else None,
                                "reproduceArgv": culprits[(c, m)][2] if (c, m) in culprits else None}
                   for (c, m), v in verdicts.items()}, f, indent=1)

    print(f"\n{len(tests)} tests, {hunt.n} runs, {time.time() - t0:.0f} s")
    for f in RUN_FAILURES:
        print(f"run failed: {f}")
    width = max(len(f"{c}#{m}") for c, m in tests)
    print(f"{'test':{width}}  " + "  ".join(f"{x:>13}" for x in perturbs) + "  verdict")
    for t in tests:
        v, rates, _ = verdicts[t]
        print(f"{t[0] + '#' + t[1]:{width}}  " + "  ".join(f"{r[0]:>6}/{r[1]:<6}" for r in (rates[x] for x in perturbs))
              + f"  {v}")
    for t, (minimal, run, cmd) in culprits.items():
        print(f"\n{t[0]}#{t[1]} fails after " + ", ".join(f"{c}#{m}" for c, m in minimal) + " in the same process:")
        print("  " + shlex.join(cmd))
    print(f"\n{os.path.join(a.out, 'index.html')}")
    sys.exit(1 if RUN_FAILURES or any(v[0] != "stable" for v in verdicts.values()) else 0)


def write_xml(path, tests, perturbs, results, verdicts, culprits, failures):
    with open(path, "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        rows = [(t, x) for t in tests for x in perturbs]
        bad = sum(1 for t, x in rows if any(s in ("failed", "missing") for s in results[x][t]))
        f.write(f'<testsuite name="flake-hunt" tests="{len(rows)}" failures="{bad}">\n')
        for t, x in rows:
            st = results[x][t]
            ok, ran = st.count("passed"), sum(1 for s in st if s != "skipped")
            f.write(f"  <testcase classname={quoteattr(t[0])} name={quoteattr(f'{t[1]} [{x}]')}>")
            f.write(f'<properties><property name="flakehunt.passed" value="{ok}"/><property name="flakehunt.runs" value="{ran}"/>'
                    f'<property name="flakehunt.verdict" value="{verdicts[t][0]}"/></properties>')
            if ok < ran:
                msgs = [m for xx, tt, _, m in failures if xx == x and tt == t]
                text = f"passed {ok} of {ran} runs ({verdicts[t][0]})"
                if t in culprits:
                    text += "; fails after " + ", ".join(f"{c}#{m}" for c, m in culprits[t][0]) + ": " + shlex.join(culprits[t][2])
                f.write(f"<failure message={quoteattr(text)}>{escape(chr(10).join(msgs[:3]))}</failure>")
            elif ran == 0:
                f.write("<skipped/>")
            f.write("</testcase>\n")
        f.write("</testsuite>\n")


def write_html(path, tests, perturbs, verdicts, culprits, failures, runs, took):
    colour = {"stable": "#1a7f37", "order-dependent": "#bc4c00", "timing-dependent": "#9a6700",
              "nondeterministic": "#8250df", "failing": "#cf222e"}
    rows = []
    for t in tests:
        v, rates, _ = verdicts[t]
        cells = "".join(f'<td class="{"bad" if r[0] < r[1] else "ok" if r[1] else "skip"}">{r[0]}/{r[1]}</td>'
                        for r in (rates[x] for x in perturbs))
        rows.append(f'<tr><td class="name">{html.escape(t[0])}#{html.escape(t[1])}</td>{cells}'
                    f'<td style="color:{colour[v]};font-weight:600">{v}</td></tr>')
    order = "".join(
        f'<li><b>{html.escape(t[0])}#{html.escape(t[1])}</b> fails after '
        + ", ".join(f"<code>{html.escape(c)}#{html.escape(m)}</code>" for c, m in minimal)
        + f' in the same process.<pre>{html.escape(shlex.join(cmd))}</pre></li>'
        for t, (minimal, run, cmd) in culprits.items())
    first = {}
    for x, t, _, m in failures:
        first.setdefault(t, (x, m))
    msgs = "".join(f"<li><b>{html.escape(t[0])}#{html.escape(t[1])}</b> ({x}): <code>{html.escape(m[:300])}</code></li>"
                   for t, (x, m) in first.items() if m)
    with open(path, "w") as f:
        f.write(f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Flake hunt</title><style>
:root{{--bg:#fff;--fg:#1f2328;--line:#d0d7de;--ok:#dafbe1;--bad:#ffebe9;--skip:#f6f8fa}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0d1117;--fg:#e6edf3;--line:#30363d;--ok:#12261e;--bad:#3c1618;--skip:#161b22}}}}
body{{background:var(--bg);color:var(--fg);font:14px/1.45 -apple-system,system-ui,sans-serif;margin:24px 16px;max-width:1100px}}
table{{border-collapse:collapse;width:100%;overflow-x:auto;display:block}}td,th{{border:1px solid var(--line);padding:4px 8px;text-align:center}}
td.name{{text-align:left;font-family:ui-monospace,monospace;font-size:12px}}td.ok{{background:var(--ok)}}td.bad{{background:var(--bad)}}
td.skip{{background:var(--skip)}}pre{{white-space:pre-wrap;word-break:break-all;font-size:12px}}</style></head><body>
<h1>Flake hunt</h1><p>{len(tests)} tests, {runs} runs, {took:.0f} s. Each cell: runs passed of runs that ran the test.</p>
<table><tr><th>test</th>{''.join(f'<th>{x}</th>' for x in perturbs)}<th>verdict</th></tr>{''.join(rows)}</table>
<h2>Order dependence</h2>{f'<ul>{order}</ul>' if order else '<p>None found.</p>'}
<h2>First failure of each test</h2>{f'<ul>{msgs}</ul>' if msgs else '<p>None.</p>'}
</body></html>""")


if __name__ == "__main__":
    main()
