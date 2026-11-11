#!/usr/bin/env python3
"""Runs a library's own device tests here and on an emulator, and tables where they differ (docs/conformance.md).

Usage: conformance.py TEST.apk [--app APP.apk] [--out DIR] [--emulator SERIAL [--util APK]... | --emulator-xml FILE]
                      [--here-xml FILE] [--name NAME] [-j N] [--isolate] [--grant-permissions] [-e KEY VALUE]... [-- APKSIM_OPTION...]

A library's androidTest APK instruments itself, so by default it is passed as both APKs (--app for a test APK that
targets an app). Here it runs through scripts/instrument.py (JUnit XML in DIR/here.xml); with --emulator the same APK
is installed on that adb device (`-t`), run with `am instrument -w -r` (DIR/emulator.xml, DIR/emulator.txt for
the raw output) and uninstalled again. It installs only test packages: a package the device already has is refused,
so an emulator another session started keeps its apps, and the instrumentation's target must be one of the packages
it installed (a test APK that targets an app the device already has would run against that app's data). --util installs an androidTestUtil APK there for the run
(the Test Services APK, androidx.test.services, which a device needs for Test Storage), and uninstalls it after. A process that crashes on the device ends `am instrument`;
the tests it did not reach are run again, one class at a time, so one crash costs only its class's tail.
Runtime permissions start denied on both sides (apksim's default, an install without -g); --grant-permissions grants
them on both (apksim's --permissions grant, adb install -g). A run that a crash ends with no test in flight gets a
failed "(run)" entry, as instrument.py gives one here.
--here-xml / --emulator-xml reuse an earlier run's XML instead of running that side again (the emulator's answers
do not change while apksim is being fixed).

DIR/table.md (and stdout) gets the counts and a row per test that is not "passes on both":
  both      passes here and on the emulator
  here      fails here only: apksim's to fix
  neither   fails on both: not ours (or the library's test needs what neither has)
  emu       passes here only (fails on the emulator)
  skipped   ignored or skipped (an assumption) on either side
  missing   ran on one side only
Failures here only are grouped by cause (the exception and its message's first line, numbers and hashes folded), so
the biggest causes come first.
"""
import argparse
import collections
import os
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))


def run_here(a, out):
    xml = os.path.join(out, "here.xml")
    cmd = [sys.executable, os.path.join(HERE, "instrument.py"), a.app or a.test, a.test, "--xml", xml, "-j", str(a.j)]
    if a.isolate:
        cmd.append("--isolate")
    for k, v in a.e:
        cmd += ["-e", k, v]
    opts = (["--permissions", "grant"] if a.grant_permissions else []) + a.apksim_opts
    if opts:
        cmd += ["--"] + opts
    print("here:", " ".join(cmd), file=sys.stderr)
    if os.path.exists(xml):
        os.remove(xml)  # an earlier run's report must not stand in for this one's
    log_path = os.path.join(out, "here.log")
    with open(log_path, "w") as log:
        # A nonzero exit with a report is failing tests; without one, the run itself failed.
        subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT)
    if not os.path.exists(xml):
        raise SystemExit(f"instrument.py wrote no report: see {log_path}")
    return xml


def adb(serial, *args, check=True, timeout=None):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)}: {r.stdout}{r.stderr}")
    return r.stdout


def instrumentations(serial):
    out = {}
    for line in adb(serial, "shell", "pm", "list", "instrumentation").splitlines():
        m = re.match(r"instrumentation:(\S+)/(\S+) \(target=(\S+)\)", line.strip())
        if m:
            out[m.group(1)] = (m.group(2), m.group(3))
    return out


def last_update(serial, pkg):
    """The package's lastUpdateTime on the device (dumpsys package), or None when it is not installed."""
    for line in adb(serial, "shell", "dumpsys", "package", shlex.quote(pkg), check=False).splitlines():
        line = line.strip()
        if line.startswith("lastUpdateTime="):
            return line.split("=", 1)[1]
    return None


def installed(serial):
    return {l.split(":", 1)[1].strip() for l in adb(serial, "shell", "pm", "list", "packages").splitlines() if ":" in l}


CRASHED = "the process crashed while this test ran"


def parse_raw(text):
    """am instrument -r output → {(class, method): (status, stack)}, and whether the run finished.

    A test that started (status 1) is failed until its own result replaces that, so the test a crash cut short keeps
    the failure rather than being run again as if it never started."""
    results, cur = {}, {}
    key = None
    code, crashed = None, False
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            k, _, v = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            cur[k] = v
            key = k
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1])
            c, m = cur.get("class"), cur.get("test")
            if c and m and code == 1:
                results.setdefault((c, m), ("failed", CRASHED))
            elif c and m:
                status = {0: "passed", -1: "failed", -2: "failed", -3: "ignored", -4: "skipped"}.get(code, "failed")
                results[(c, m)] = (status, cur.get("stack", ""))
            cur, key = {}, None
        elif line.startswith("INSTRUMENTATION_RESULT: ") or line.startswith("INSTRUMENTATION_CODE: "):
            # The run's own fields, not a test's output: am instrument reports a crash as the result's shortMsg.
            key = None
            if line.startswith("INSTRUMENTATION_CODE: "):
                code = line.split(":", 1)[1].strip()
            elif line.startswith("INSTRUMENTATION_RESULT: shortMsg=") and "Process crashed" in line:
                crashed = True
        elif key is not None:
            cur[key] += "\n" + line
    return results, code == "-1" and not crashed


RUN = "(run)"


def retry_selector(extra, cls):
    """What to run again for a class after a crash: the methods the run named for it (-e class C#m,...), else the
    whole class (named whole, or no class filter)."""
    named = [sel for k, v in extra if k == "class" for sel in v.split(",")]
    if not named or cls in named:
        return cls
    methods = [sel for sel in named if sel.startswith(cls + "#")]
    return ",".join(methods) or cls


def note_run_crash(results, finished, raw):
    """A run that ended early with no test in flight (a crash in @AfterClass, a listener, between tests) still fails:
    a run-level entry carries it, as instrument.py's "(run)" entry does here."""
    if not finished and not any(v == ("failed", CRASHED) for v in results.values()):
        results[(RUN, "incomplete")] = ("failed", "the instrumentation process crashed outside any test")


def am_instrument(serial, comp, extra, raw):
    cmd = ["am", "instrument", "-w", "-r"]
    for k, v in extra:
        cmd += ["-e", k, v]
    # adb shell hands one command line to the device's shell: quote each word (spaces, `$` of nested classes).
    r = subprocess.run(["adb", "-s", serial, "shell", " ".join(shlex.quote(w) for w in cmd + [comp])],
                       capture_output=True, text=True)
    raw.write(r.stdout)
    if r.returncode != 0 and "INSTRUMENTATION_" not in r.stdout:
        # adb itself failed (the device went away): not a result of the tests, so no table from it.
        raise SystemExit(f"adb shell am instrument on {serial} failed ({r.returncode}): {r.stderr.strip() or r.stdout.strip()}")
    return parse_raw(r.stdout)


def run_emulator(a, out):
    serial = a.emulator
    before = installed(serial)
    have = set(instrumentations(serial))
    apks = [a.test] + ([a.app] if a.app and a.app != a.test else []) + a.util
    pkgs = []
    for apk in apks:
        pkg = apk_package(apk)
        if pkg in before:
            raise SystemExit(f"{serial} already has {pkg}: conformance.py installs only packages the device lacks")
        pkgs.append(pkg)
    # Espresso needs a window with focus: a headless emulator that just booted sits on its lock screen.
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
    adb(serial, "shell", "wm", "dismiss-keyguard", check=False)
    focus = [l for l in adb(serial, "shell", "dumpsys", "window", check=False).splitlines() if "mCurrentFocus" in l]
    if focus and "Not Responding" in focus[0]:
        # A loaded Mac makes the emulator's System UI ANR; its dialog keeps every test's window from the focus.
        raise SystemExit(f"{serial}: an ANR dialog has the focus ({focus[0].strip()}): answer it (Wait) and run again")
    installed_at = {}  # package -> its lastUpdateTime right after this run installed it
    try:
        for apk in reversed(apks):
            pkg = apk_package(apk)
            if pkg in installed(serial):  # again, just before: another session may have installed it meanwhile
                raise SystemExit(f"{serial} now has {pkg} (installed by someone else meanwhile): stopping")
            # Utilities (Test Services) are queryable by every package, as AGP installs them: package visibility
            # otherwise hides their providers from the test (Test Storage: "No content provider registered").
            # Runtime permissions as apksim starts them: denied, unless --grant-permissions grants them on both sides.
            # Utilities are installed as AGP installs them: granted and queryable.
            util = apk in a.util
            adb(serial, "install", "-t", *(["-g"] if util or a.grant_permissions else []),
                *(["--force-queryable"] if util else []), apk)
            installed_at[pkg] = last_update(serial, pkg)
            if apk in a.util:
                # As AGP does for Test Services: it writes the test's output files under /sdcard.
                adb(serial, "shell", "appops", "set", apk_package(apk), "MANAGE_EXTERNAL_STORAGE", "allow", check=False)
        insts = {k: v for k, v in instrumentations(serial).items() if k not in have}
        if pkgs[0] not in insts:
            raise SystemExit(f"{pkgs[0]} declares no instrumentation on {serial}")
        runner, target = insts[pkgs[0]]
        if target not in installed_at:
            # Its tests would run in that app's process and change its data: only an app this run installed may be one.
            raise SystemExit(f"{pkgs[0]} instruments {target}, which this run did not install: pass it with --app")
        comp = f"{pkgs[0]}/{runner}"
        with open(os.path.join(out, "emulator.txt"), "w") as raw:
            results, finished = am_instrument(serial, comp, a.e, raw)
            note_run_crash(results, finished, raw)
            if not finished:
                # A crash ended the run: list the suite, then run each class with tests that never started again,
                # whole (a Parameterized name's commas cannot be named in -e class), keeping only the results of
                # those tests: the ones that ran keep their first result, the one the crash cut short its failure.
                listed, _ = am_instrument(serial, comp, a.e + [("log", "true")], raw)
                left = collections.OrderedDict()
                for (c, m) in listed:
                    if (c, m) not in results:
                        left.setdefault(c, []).append(m)
                for c, ms in left.items():
                    got, done = am_instrument(serial, comp, [kv for kv in a.e if kv[0] != "class"] +
                                              [("class", retry_selector(a.e, c))], raw)
                    if not done and not any(got.get(k) == ("failed", CRASHED) for k in got):
                        results[(RUN, f"incomplete (re-running {c})")] = ("failed", "the process crashed outside any test")
                    for m in ms:
                        results[(c, m)] = got.get((c, m), ("failed", "not run: the process crashed before it"))
    finally:
        # Only what this run installed and nobody has installed over since (same lastUpdateTime): another session's
        # install of the same package in the meantime keeps its app and data.
        for pkg, stamp in installed_at.items():
            if stamp is not None and last_update(serial, pkg) == stamp:
                adb(serial, "uninstall", pkg, check=False)
            else:
                print(f"{serial}: {pkg} changed since this run installed it: left installed", file=sys.stderr)
    xml = os.path.join(out, "emulator.xml")
    write_xml(xml, results)
    return xml


def apk_package(apk):
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted(os.listdir(os.path.join(sdk, "build-tools")), reverse=True)
    for t in tools:
        aapt = os.path.join(sdk, "build-tools", t, "aapt2")
        if os.path.exists(aapt):
            r = subprocess.run([aapt, "dump", "packagename", apk], capture_output=True, text=True)
            if r.returncode == 0:
                return r.stdout.strip()
    raise SystemExit("no aapt2 in the Android SDK's build-tools")


def write_xml(path, results):
    with open(path, "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="emulator">\n')
        for (c, m), (s, st) in sorted(results.items()):
            f.write(f"  <testcase classname={quoteattr(c)} name={quoteattr(m)}>")
            if s == "failed":
                f.write(f"<failure message={quoteattr(st.splitlines()[0] if st else '')}>{escape(st)}</failure>")
            elif s in ("ignored", "skipped"):
                f.write("<skipped/>")
            f.write("</testcase>\n")
        f.write("</testsuite>\n")


def fold_runs(results):
    """Every run-level entry (instrument.py's "(run)"/"(listing)" ones, labelled per process or shard, and this
    script's) as one "(run)" entry, so a run that failed on both sides is one row that fails on both."""
    runs = {k: v for k, v in results.items() if k[0] in (RUN, "(listing)")}
    if not runs:
        return results
    out = {k: v for k, v in results.items() if k not in runs}
    failed = [f"{c} {m}: {v[1]}" for (c, m), v in runs.items() if v[0] == "failed"]
    out[(RUN, "run")] = ("failed", "\n".join(failed)) if failed else ("passed", "")
    return out


def read_xml(path):
    out = {}
    for tc in ET.parse(path).getroot().iter("testcase"):
        key = (tc.get("classname"), tc.get("name"))
        fail = tc.find("failure")
        if fail is None:
            fail = tc.find("error")
        if fail is not None:
            out[key] = ("failed", fail.text or fail.get("message") or "")
        elif tc.find("skipped") is not None:
            out[key] = ("skipped", "")
        else:
            out[key] = ("passed", "")
    return out


def cause(stack):
    """The exception and the first line of its message, with numbers, hashes and quoted names folded."""
    lines = [l for l in stack.strip().splitlines() if l.strip()]
    head = lines[0] if lines else "(no stack)"
    # The root cause is the last "Caused by" when there is one.
    for l in lines:
        if l.startswith("Caused by: "):
            head = l[len("Caused by: "):]
    head = re.sub(r"@[0-9a-f]{5,}", "@…", head)
    head = re.sub(r"\b0x[0-9a-f]+\b|\b\d+(\.\d+)?\b", "N", head)
    head = re.sub(r"'[^']*'|\"[^\"]*\"", "…", head)
    return head[:160]


def main():
    argv = sys.argv[1:]
    rest = []
    if "--" in argv:
        i = argv.index("--")
        argv, rest = argv[:i], argv[i + 1:]
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("test")
    p.add_argument("--app")
    p.add_argument("--out")
    p.add_argument("--name")
    p.add_argument("--emulator", metavar="SERIAL")
    p.add_argument("--emulator-xml", metavar="FILE")
    p.add_argument("--util", action="append", default=[], metavar="APK")
    p.add_argument("--here-xml", metavar="FILE")
    p.add_argument("-j", type=int, default=3)
    p.add_argument("--isolate", action="store_true")
    p.add_argument("--grant-permissions", action="store_true")
    p.add_argument("-e", nargs=2, action="append", default=[], metavar=("KEY", "VALUE"))
    a = p.parse_args(argv)
    a.e = [tuple(kv) for kv in a.e]
    a.apksim_opts = rest
    name = a.name or re.sub(r"(-debug)?-androidTest\.apk$|\.apk$", "", os.path.basename(a.test))
    out = a.out or os.path.join(HERE, "..", "work", "conformance", name)
    os.makedirs(out, exist_ok=True)

    emu_xml = a.emulator_xml or (run_emulator(a, out) if a.emulator else None)
    here = fold_runs(read_xml(a.here_xml or run_here(a, out)))
    emu = fold_runs(read_xml(emu_xml)) if emu_xml else {}

    rows = collections.defaultdict(list)
    for key in sorted(set(here) | set(emu)):
        h, e = here.get(key), emu.get(key)
        if emu_xml is None:
            cat = {"passed": "both", "failed": "here", "skipped": "skipped"}[h[0]]
        elif h is None or e is None:
            cat = "missing"
        elif "skipped" in (h[0], e[0]):
            cat = "skipped"
        elif h[0] == e[0] == "passed":
            cat = "both"
        elif h[0] == "failed" and e[0] == "passed":
            cat = "here"
        elif h[0] == e[0] == "failed":
            cat = "neither"
        else:
            cat = "emu"
        rows[cat].append((key, h, e))

    lines = [f"# {name}", ""]
    order = ["both", "here", "neither", "emu", "skipped", "missing"]
    label = {"both": "passes on both", "here": "fails here only", "neither": "fails on both", "emu": "passes here only",
             "skipped": "skipped", "missing": "ran on one side only"}
    if emu_xml is None:
        label["both"], label["here"] = "passes here", "fails here (no emulator run)"
    lines.append("| " + " | ".join(label[c] for c in order) + " |")
    lines.append("|" + "---|" * len(order))
    lines.append("| " + " | ".join(str(len(rows[c])) for c in order) + " |")
    causes = collections.Counter(cause(h[1]) for _, h, _ in rows["here"])
    if causes:
        lines += ["", "## Causes here", ""]
        for c, n in causes.most_common():
            lines.append(f"- {n}: `{c}`")
    for c in order[1:]:
        if not rows[c]:
            continue
        lines += ["", f"## {label[c]}", ""]
        for (cl, m), h, e in rows[c]:
            why = cause(h[1]) if h and h[0] == "failed" else (cause(e[1]) if e and e[0] == "failed" else "")
            side = f"here {h[0] if h else '-'}, emulator {e[0] if e else '-'}"
            lines.append(f"- `{cl.rsplit('.', 1)[-1]}#{m}` ({side})" + (f": `{why}`" if why else ""))
    text = "\n".join(lines) + "\n"
    with open(os.path.join(out, "table.md"), "w") as f:
        f.write(text)
    print(text)
    print(f"table: {os.path.join(out, 'table.md')}", file=sys.stderr)


if __name__ == "__main__":
    main()
