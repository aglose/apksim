#!/usr/bin/env python3
"""Observer gate: turning every observer of a test run on must not change what the run reports.

Usage: observer-gate.py APP.apk TEST.apk [OUT] [--apksim CMD] [--local PATH] [--time-factor F] [--min-tests N] [--no-rec] [--no-coverage]
                        [--record-format avi|mp4] [-- instrument.py options]

Runs the suite twice through instrument.py, once plain and once with every observer a test run has: failure bundles
for every test (--bundles --bundles-on all), a movie of every test (--record all), the flight recorder's net and db
channels (--rec net,db: Main starts them in a test run too) and --coverage counters. Then compares the two JUnit XMLs
test by test: the same tests, the same outcome (passed, failed, skipped) for each, and the observed run's wall time at
most F times the plain run's (default 3: CI is noisy; --time-factor 0 prints it only). Tests whose own times differ
most are listed and never fail the gate. Exit 0 when the runs agree, 1 with the differences, 2 when a run did not
produce its XML or the plain run has fewer than N tests (default 1) or a "(run)" failure.

Six bugs were an observer changing what it observed (failure bundles: issue 73, test movies: 144, 197 and 360, a
descriptor a listener leaked: 115); this is the check that finds the next one. Everything after -- goes to
instrument.py for both runs, so give it the suite's usual arguments (-e class ..., -e timezone ..., and its own --
APKSIM_OPTS). OUT (default work/observer-gate) gets plain.xml, observed.xml and the observers'
output. With --apksim (the Linux image), OUT is as the container sees it (a folder mounted into it) and --local is
that folder as seen here (as bundle-check.py); the XMLs are written by instrument.py on this side.
Only plain.xml, observed.xml, bundles/, movies/ and coverage/ in it are removed first.
"""
import argparse, os, shutil, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."


def outcomes(path):
    """{(class, name): (outcome, seconds)} of a JUnit XML. A test listed twice (a rerun) keeps its last entry."""
    out = {}
    for t in ET.parse(path).iter("testcase"):
        if t.find("failure") is not None or t.find("error") is not None:
            o = "failed"
        elif t.find("skipped") is not None:
            o = "skipped"
        else:
            o = "passed"
        out[(t.get("classname", ""), t.get("name", ""))] = (o, float(t.get("time") or 0))
    return out


def run(cmd, label):
    t0 = time.time()
    r = subprocess.run(cmd)
    s = time.time() - t0
    print(f"observer-gate: {label} run exited {r.returncode} after {s:.1f} s", flush=True)
    return r.returncode, s


def compare(plain, seen, t_plain, t_seen, factor):
    """The report: (lines, failed)."""
    lines, failed = [], False
    for what, a, b in (("only in the plain run", plain, seen), ("only in the observed run", seen, plain)):
        extra = sorted(set(a) - set(b))
        if extra:
            failed = True
            lines.append(f"{what} ({len(extra)}):")
            lines += [f"  {c}#{n} [{a[(c, n)][0]}]" for c, n in extra[:40]]
            if len(extra) > 40:
                lines.append(f"  ... and {len(extra) - 40} more")
    diff = [(k, plain[k][0], seen[k][0]) for k in sorted(set(plain) & set(seen)) if plain[k][0] != seen[k][0]]
    if diff:
        failed = True
        lines.append(f"different outcome ({len(diff)}):")
        lines.append(f"  {'plain':8} {'observed':8} test")
        lines += [f"  {p:8} {o:8} {c}#{n}" for (c, n), p, o in diff[:60]]
    ratio = t_seen / t_plain if t_plain else 0
    lines.append(f"wall time: plain {t_plain:.1f} s, observed {t_seen:.1f} s (x{ratio:.2f}, limit "
                 + (f"x{factor:g}" if factor else "none") + ")")
    if factor and ratio > factor:
        failed = True
        lines.append(f"  the observed run took {ratio:.2f} times as long as the plain one (more than {factor:g})")
    slow = sorted(((seen[k][1] - plain[k][1], k) for k in set(plain) & set(seen)
                   if seen[k][1] > 2 * plain[k][1] and seen[k][1] - plain[k][1] > 1.0), reverse=True)
    if slow:
        lines.append(f"tests at least twice as slow observed, by at least 1 s (listed only; {len(slow)}):")
        lines += [f"  {plain[k][1]:7.2f} s -> {seen[k][1]:7.2f} s  {k[0]}#{k[1]}" for _, k in slow[:15]]
    return lines, failed


def main():
    argv = sys.argv[1:]
    rest = []
    if "--" in argv:
        rest = argv[argv.index("--") + 1:]
        argv = argv[:argv.index("--")]
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app", nargs="?")
    p.add_argument("test", nargs="?")
    p.add_argument("out", nargs="?", default="work/observer-gate")
    p.add_argument("--apksim")
    p.add_argument("--local", help="with --apksim: OUT as seen here (the folder mounted into the container)")
    p.add_argument("--time-factor", type=float, default=3.0)
    p.add_argument("--min-tests", type=int, default=1, help="fail (exit 2) when the plain run has fewer tests than this")
    p.add_argument("--no-rec", action="store_true", help="leave the flight recorder out")
    p.add_argument("--no-coverage", action="store_true", help="leave --coverage out")
    p.add_argument("--record-format", choices=["avi", "mp4"], default="avi",
                   help="the movies' format (mp4 needs an H.264 encoder: the Mac's, or libopenh264)")
    p.add_argument("--compare", nargs=4, metavar=("PLAIN.xml", "OBSERVED.xml", "PLAIN_S", "OBSERVED_S"),
                   help="only compare two JUnit XMLs and the wall times of their runs (the comparison's own check)")
    a = p.parse_args(argv)
    if a.compare:
        lines, failed = compare(outcomes(a.compare[0]), outcomes(a.compare[1]), float(a.compare[2]),
                                float(a.compare[3]), a.time_factor)
        print("\n".join(lines))
        sys.exit(1 if failed else 0)
    if not a.app or not a.test:
        p.error("APP.apk and TEST.apk are needed")
    if a.apksim and not a.local:
        p.error("--apksim needs --local: OUT as seen from here")
    here = a.local if a.apksim else a.out
    os.makedirs(here, exist_ok=True)
    for name in ("plain.xml", "observed.xml"):  # only what this script writes: OUT may be a folder with other things in it
        if os.path.exists(os.path.join(here, name)):
            os.remove(os.path.join(here, name))
    for name in ("bundles", "movies", "coverage"):
        shutil.rmtree(os.path.join(here, name), ignore_errors=True)
    inst = [sys.executable, os.path.join(ROOT, "scripts/instrument.py"), a.app, a.test]
    if a.apksim:
        inst += ["--apksim", a.apksim]
    # instrument.py's own arguments, then its -- (options for apksim) when the caller gave one.
    own, passthrough = (rest[:rest.index("--")], rest[rest.index("--") + 1:]) if "--" in rest else (rest, [])
    d = lambda name: os.path.join(a.out, name)       # as apksim sees it
    l = lambda name: os.path.join(here, name)        # as this script does
    plain_cmd = inst + own + ["--xml", l("plain.xml")] + (["--"] + passthrough if passthrough else [])
    obs = ["--xml", l("observed.xml"), "--bundles", d("bundles"), "--record", "all", "--record-dir", d("movies"),
           "--record-format", a.record_format]
    if a.apksim:
        obs += ["--bundles-local", l("bundles"), "--record-local", l("movies")]
    if not a.no_coverage:
        obs += ["--coverage", d("coverage")]
        if a.apksim:
            obs += ["--coverage-local", l("coverage")]
    jvm = ["--bundles-on", "all"] + ([] if a.no_rec else ["--rec", "net,db"])
    observed_cmd = inst + own + obs + ["--"] + passthrough + jvm
    print("observer-gate: observers on: bundles (all tests), movies (" + a.record_format + ")"
          + ("" if a.no_rec else ", flight recorder net+db") + ("" if a.no_coverage else ", coverage"), flush=True)
    rc_plain, t_plain = run(plain_cmd, "plain")
    rc_seen, t_seen = run(observed_cmd, "observed")
    for name in ("plain.xml", "observed.xml"):
        if not os.path.exists(l(name)):
            print(f"observer-gate: no {name}: that run did not finish", file=sys.stderr)
            sys.exit(2)
    plain, seen = outcomes(l("plain.xml")), outcomes(l("observed.xml"))
    count = lambda m: ", ".join(f"{sum(1 for v in m.values() if v[0] == o)} {o}" for o in ("passed", "failed", "skipped"))
    print(f"observer-gate: plain {len(plain)} tests ({count(plain)}); observed {len(seen)} tests ({count(seen)})")
    if len(plain) < a.min_tests or any(n == "(run)" for _, n in plain):
        print(f"observer-gate: the plain run has {len(plain)} tests (at least {a.min_tests} wanted, no '(run)' failure): "
              "a gate over nothing proves nothing", file=sys.stderr)
        sys.exit(2)
    lines, failed = compare(plain, seen, t_plain, t_seen, a.time_factor)
    if rc_plain != rc_seen:
        failed = True
        lines.append(f"exit status: plain {rc_plain}, observed {rc_seen}")
    print("\n".join(lines))
    print("observer-gate: " + ("FAILED: the observers changed the run" if failed else "ok: the observers changed nothing"))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
