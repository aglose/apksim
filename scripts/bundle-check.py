#!/usr/bin/env python3
"""Checks failure bundles (apksim --bundles, docs/plan.md phase 5) with the test app's FailureBundle*Test classes.

Usage: bundle-check.py APP.apk TEST.apk [OUT] [--apksim CMD]

Runs the tests that fail on purpose (-e failOnPurpose true) through instrument.py --bundles, then the one whose
timeout the watchdog ends (-e failOnPurpose watchdog), and checks every bundle: the files its capture point writes,
a screen that is not blank, the tree holding the screen's text, a log with only its own test's lines, no bundle for
the tests that pass, the JUnit XML's links, index.html, and (not with --apksim) that repro.sh fails the same test
alone. Then that a passing run prints the same with and without --bundles. OUT (default work/bundle-check) is
emptied first. With --apksim (the Linux image, as in CI), APP, TEST and OUT are relative to the folder mounted
into the container, given as --local.
"""
import argparse, os, re, shutil, subprocess, sys, xml.etree.ElementTree as ET, zlib

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
PKG = "dev.apksim.composeapp."
sys.path.insert(0, os.path.join(ROOT, "scripts"))
FAIL = [PKG + c for c in ("FailureBundleTest", "FailureBundleEspressoTest", "FailureBundleAfterTest", "FailureBundleParameterizedTest",
                          "FailureBundleCustomHandlerTest")]
problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)


def folder(c, m):
    import hashlib
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", m)[:60]
    return os.path.join(c, f"{safe}-{hashlib.sha256(f'{c}#{m}'.encode()).hexdigest()[:8]}")


def not_blank(png):
    """A PNG's pixel data, unfiltered enough to tell a blank image (a few byte values) from a screen."""
    data = open(png, "rb").read()
    idat = b"".join(data[i + 8:i + 8 + int.from_bytes(data[i:i + 4], "big")]
                    for i in [m.start() - 4 for m in re.finditer(b"IDAT", data)])
    return len(set(zlib.decompress(idat))) > 32


def instrument(a, xml, bundles_dir, local, *extra):
    cmd = [sys.executable, os.path.join(ROOT, "scripts/instrument.py"), a.app, a.test, "--xml", xml, "--bundles", bundles_dir]
    if a.apksim:
        cmd += ["--apksim", a.apksim, "--bundles-local", local]
    r = subprocess.run(cmd + list(extra), capture_output=True, text=True)
    sys.stderr.write(r.stderr[-3000:] if r.returncode not in (0, 1) else "")
    return r


def main():
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("out", nargs="?", default="work/bundle-check")
    p.add_argument("--apksim")
    p.add_argument("--local", help="with --apksim: the mounted folder here")
    a = p.parse_args()
    here = (lambda path: os.path.join(a.local, path)) if a.apksim else (lambda path: path)
    out = here(a.out)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)

    print("tests that fail on purpose, with --bundles:")
    bdir, local = os.path.join(a.out, "bundles"), os.path.join(out, "bundles")
    xml = os.path.join(out, "junit.xml")
    # An earlier run's bundle for a test that passes now: instrument.py must remove it before this run.
    stale = os.path.join(local, folder(PKG + "FailureBundleTest", "a_passes"))
    os.makedirs(stale)
    open(os.path.join(stale, "failure.txt"), "w").write("an earlier run's failure\n")
    r = instrument(a, xml, bdir, local, "-e", "failOnPurpose", "true", "-e", "class", ",".join(FAIL))
    check(r.returncode == 1, f"the run fails (exit {r.returncode})")
    expect = {  # (class, method): (captured by, screen and tree expected, text the tree must hold)
        (PKG + "FailureBundleTest", "b_composeAssertionFails"): ("test body", True, "FAB clicks: 0"),
        (PKG + "FailureBundleTest", "c_failsWithDialogOpen"): ("test body", True, "Bundle dialog"),
        (PKG + "FailureBundleEspressoTest", "noMatchingView"): ("Espresso failure handler", True, "FAB clicks: 0"),
        (PKG + "FailureBundleAfterTest", "afterFails"): ("listener", False, None),
        (PKG + "FailureBundleParameterizedTest", "sameNumbers[1: 1, 2]"): ("listener", False, None),
    }
    markers = {"b_composeAssertionFails": "b_composeAssertionFails", "c_failsWithDialogOpen": "c_failsWithDialogOpen",
               "noMatchingView": "noMatchingView", "afterFails": "afterFails", "sameNumbers[1: 1, 2]": "sameNumbers 1 2"}
    for (c, m), (point, screen, text) in expect.items():
        d = os.path.join(local, folder(c, m))
        name = f"{c.rsplit('.', 1)[1]}#{m}"
        if not os.path.isdir(d):
            check(False, f"{name}: bundle at {d}")
            continue
        files = set(os.listdir(d))
        need = {"failure.txt", "logcat.txt", "threads.txt", "repro.sh", "windows.txt", "nodes.txt", "looper.txt"}
        if screen:
            need.add("screen.png")
        check(need <= files, f"{name}: " + (f"missing {sorted(need - files)}" if need - files else "every file there"))
        failure = open(os.path.join(d, "failure.txt")).read()
        check(f"captured by: {point}" in failure, f"{name}: captured by {point}")
        check("failed:" not in failure.split("parts:")[-1] if screen else True, f"{name}: every part captured")
        if screen and "screen.png" in files:
            check(not_blank(os.path.join(d, "screen.png")), f"{name}: screen.png is not blank")
        if text:
            check(text in open(os.path.join(d, "nodes.txt")).read(), f"{name}: nodes.txt has \"{text}\"")
        log = open(os.path.join(d, "logcat.txt")).read()
        found = re.findall(r"BundleTest: marker (.*)", log)
        check(found == [markers[m]], f"{name}: logcat.txt has only its own marker (found {found})")
    if (PKG + "FailureBundleTest", "c_failsWithDialogOpen") in expect:
        w = open(os.path.join(local, folder(PKG + "FailureBundleTest", "c_failsWithDialogOpen"), "windows.txt")).read()
        check(len(w.strip().splitlines()) == 2, "the dialog test's windows.txt lists two windows")
    for c, m in ((PKG + "FailureBundleTest", "a_passes"), (PKG + "FailureBundleParameterizedTest", "sameNumbers[0: 1, 1]"),
                 (PKG + "FailureBundleCustomHandlerTest", "ownHandlerDecides")):
        check(not os.path.exists(os.path.join(local, folder(c, m))), f"no bundle for passing {m}")
    cases = {(t.get("classname"), t.get("name")): t for t in ET.parse(xml).iter("testcase")}
    own = cases.get((PKG + "FailureBundleCustomHandlerTest", "ownHandlerDecides"))
    check(own is not None and own.find("failure") is None, "a test's own Espresso failure handler still decides (it passed)")
    for key in expect:
        t = cases.get(key)
        prop = t is not None and t.find("properties/property[@name='apksim.bundle']")
        out_ = t is not None and t.findtext("system-out") or ""
        check(prop is not None and prop is not False and "[[ATTACHMENT|" in out_, f"{key[1]}: linked from the XML")
    index = os.path.join(local, "index.html")
    check(os.path.exists(index) and open(index).read().count("<article") == len(expect), "index.html has a card per bundle")

    if not a.apksim:
        print("repro.sh runs the test alone:")
        d = os.path.join(local, folder(PKG + "FailureBundleTest", "b_composeAssertionFails"))
        rr = subprocess.run(["sh", os.path.join(d, "repro.sh")], capture_output=True, text=True)
        results = [t for t in re.findall(r"INSTRUMENTATION_STATUS: test=(\S+)\nINSTRUMENTATION_STATUS_CODE: (-?\d)", rr.stdout)
                   if t[1] != "1"]  # results only, not the start blocks
        check(rr.returncode == 1 and results == [("b_composeAssertionFails", "-2")],
              f"repro.sh: exit {rr.returncode}, results {results}")

    print("--bundles-on all: a body that returned, then an @After that fails:")
    r = instrument(a, os.path.join(out, "junit-all.xml"), os.path.join(a.out, "all"), os.path.join(out, "all"),
                   "-e", "failOnPurpose", "true", "-e", "class", PKG + "FailureBundleAfterTest", "--", "--bundles-on", "all")
    d = os.path.join(out, "all", folder(PKG + "FailureBundleAfterTest", "afterFails"))
    f = open(os.path.join(d, "failure.txt")).read() if os.path.exists(os.path.join(d, "failure.txt")) else ""
    # (afterFails opens no activity: what is kept from the body is its windows.txt and tree, here empty)
    check("status: failed" in f and "@After fails on purpose" in f and "from the end of the body" in f
          and os.path.exists(os.path.join(d, "windows.txt")), "the bundle says failed, with the @After failure, and keeps the body's capture")

    print("the watchdog's test, which ends the process (with --bundles-redact):")
    wxml = os.path.join(out, "junit-watchdog.xml")
    r = instrument(a, wxml, os.path.join(a.out, "watchdog"), os.path.join(out, "watchdog"),
                   "-e", "failOnPurpose", "watchdog", "-e", "class", PKG + "FailureBundleWatchdogTest",
                   "--", "--bundles-redact", "still running")
    d = os.path.join(out, "watchdog", folder(PKG + "FailureBundleWatchdogTest", "ignoresInterrupts"))
    files = set(os.listdir(d)) if os.path.isdir(d) else set()
    check({"failure.txt", "threads.txt", "logcat.txt", "process.txt"} <= files, f"watchdog bundle files: {sorted(files)}")
    if "failure.txt" in files:
        check("captured by: timeout watchdog" in open(os.path.join(d, "failure.txt")).read(), "captured by the watchdog")
        check("ignoresInterrupts" in open(os.path.join(d, "threads.txt")).read(), "threads.txt shows the stuck test")
    if "process.txt" in files:
        p_ = open(os.path.join(d, "process.txt")).read()
        check("[redacted]" in p_ and "still running" not in p_, "process.txt is redacted as apksim's own files are")

    if not a.apksim:
        print("a passing run prints the same with and without --bundles:")
        base = [os.path.join(ROOT, "apksim"), "--instrument", a.test, "-e", "class", PKG + "MainActivityTest"]
        plain = subprocess.run(base + [a.app], capture_output=True, text=True).stdout
        with_b = subprocess.run(base + ["--bundles", os.path.join(out, "passing"), a.app], capture_output=True, text=True).stdout
        # (JVM log lines carry their own timestamps: the AOT cache's warnings after a rebuild, say)
        strip = lambda s: re.sub(r"(?m)^\[\d+\.\d+s\].*\n", "", re.sub(r"Time: [\d.]+", "Time: _", s))
        check(strip(plain) == strip(with_b) and "OK (" in plain, "same output, and it passed")
        check(not os.listdir(os.path.join(out, "passing")), "no bundles for a passing run")

    print(f"{len(problems)} problems" if problems else "failure bundles: all checks pass")
    sys.exit(1 if problems else 0)


main()
