#!/usr/bin/env python3
"""Checks --coverage (docs/coverage.md) with the test app.

Usage: coverage-check.py APP.apk TEST.apk [OUT] [--apksim CMD --local DIR]

Runs a few of the test app's classes through instrument.py --coverage and checks that
  - a named test hits the named methods and lines of the class it exercises (NullBindingTest -> NullBindingService),
    and a run of another test reads 0 for the same methods and lines (the class is in the report, not missing);
  - a method nothing ran reads 0, in jacoco.xml and in lcov.info;
  - one process, two shards and one process per test merge to the same covered methods and lines;
  - the run leaves the classes' behaviour alone: the same tests pass with and without --coverage;
  - jacoco.xml follows JaCoCo's report.dtd structure, its counters add up, and lcov.info is well formed.
OUT (default work/coverage-check) is emptied first. With --apksim (the Linux image, as in CI) APP, TEST and OUT are
relative to the mounted folder, given as --local.
"""
import argparse, glob, json, os, re, shutil, subprocess, sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."
PKG = "dev.apksim.composeapp."
SUITE = ["NullBindingTest", "BluetoothTest", "ThermalTest"]
problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)


# JaCoCo's report.dtd: element -> (attributes {name: required}, children in order [(name, repeated)]).
DTD = {
    "report": ({"name": True}, ["sessioninfo", "group|package", "counter"]),
    "sessioninfo": ({"id": True, "start": True, "dump": True}, []),
    "group": ({"name": True}, ["group|package", "counter"]),
    "package": ({"name": True}, ["class", "sourcefile", "counter"]),
    "class": ({"name": True, "sourcefilename": False}, ["method", "counter"]),
    "method": ({"name": True, "desc": True, "line": False}, ["counter"]),
    "sourcefile": ({"name": True}, ["line", "counter"]),
    "line": ({"nr": True, "mi": False, "ci": False, "mb": False, "cb": False}, []),
    "counter": ({"type": True, "missed": True, "covered": True}, []),
}
COUNTER_TYPES = {"INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS"}


def validate(el, path="report"):
    """Structural errors of an element against the DTD: attributes, child order, counter types."""
    errs = []
    attrs, kids = DTD[el.tag]
    for a, req in attrs.items():
        if req and a not in el.attrib:
            errs.append(f"{path}: missing {a}")
    for a in el.attrib:
        if a not in attrs:
            errs.append(f"{path}: unknown attribute {a}")
    pos = 0
    for ch in el:
        if ch.tag not in DTD:
            errs.append(f"{path}: unknown element {ch.tag}")
            continue
        while pos < len(kids) and ch.tag not in kids[pos].split("|"):
            pos += 1
        if pos == len(kids):
            errs.append(f"{path}: {ch.tag} not allowed here (or out of order)")
            continue
        if ch.tag == "counter" and ch.get("type") not in COUNTER_TYPES:
            errs.append(f"{path}: counter type {ch.get('type')}")
        errs += validate(ch, f"{path}/{ch.tag}[{ch.get('name') or ch.get('nr') or ch.get('type') or ''}]")
    return errs


def counters(el):
    return {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in el.findall("counter")}


def run(args, a, name, extra, classes=SUITE, fail=True):
    out = os.path.join(a.out, name)
    local = os.path.join(a.local, out) if a.local else out
    cmd = [sys.executable, os.path.join(ROOT, "scripts/instrument.py"), a.app, a.test, "-e", "class",
           ",".join(PKG + c for c in classes)] + extra
    if a.apksim:
        cmd += ["--apksim", a.apksim]
    r = subprocess.run(cmd + ["--coverage", out] + (["--coverage-local", local] if a.local else []),
                       capture_output=True, text=True, env={**os.environ, "APKSIM_STANDBY": "0"})
    if fail and r.returncode != 0:
        print(r.stdout[-2000:], r.stderr[-2000:])
    return local, r


def load(d):
    return json.load(open(os.path.join(d, "coverage.json")))


def covered(doc):
    methods = {(c["name"], m) for c in doc["classes"] for m, h in c["methods"].items() if h > 0}
    lines = {(f, l) for f, ls in doc["files"].items() for l, h in ls.items() if h > 0}
    return methods, lines


def hits(doc, cls, method=None):
    for c in doc["classes"]:
        if c["name"] == cls:
            return {m: h for m, h in c["methods"].items() if method is None or m.startswith(method)}
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("app")
    p.add_argument("test")
    p.add_argument("out", nargs="?", default=os.path.join(ROOT, "work/coverage-check"))
    p.add_argument("--apksim")
    p.add_argument("--local")
    a = p.parse_args()
    a.here = os.path.join(a.local, a.out) if a.local else a.out  # OUT as seen from here
    shutil.rmtree(a.here, ignore_errors=True)
    os.makedirs(a.here)
    cls = "dev/apksim/composeapp/NullBindingService"
    src = "dev/apksim/composeapp/NullBindingService.kt"

    print("named tests")
    d_nb, r = run(a, a, "nullbinding", [], ["NullBindingTest"])
    d_bt, _ = run(a, a, "bluetooth", [], ["BluetoothTest"])
    nb, bt = load(d_nb), load(d_bt)
    h_nb, h_bt = hits(nb, cls), hits(bt, cls)
    check(h_nb is not None and h_nb.get("onBind(Landroid/content/Intent;)Landroid/os/IBinder;", 0) > 0
          and h_nb.get("onCreate()V", 0) > 0, "NullBindingTest hits NullBindingService.onBind and onCreate")
    check(h_bt is not None and h_bt and all(h == 0 for h in h_bt.values()),
          "BluetoothTest leaves every NullBindingService method at 0 (and the class is in the report)")
    l_nb, l_bt = nb["files"].get(src, {}), bt["files"].get(src, {})
    check(l_nb.get("20", 0) > 0 and l_bt and all(h == 0 for h in l_bt.values()),
          "line 20 of NullBindingService.kt: hit by NullBindingTest, 0 for BluetoothTest")
    zero = [m for c in nb["classes"] for m, h in c["methods"].items() if h == 0]
    check(len(zero) > 0, f"methods no test of the run reached read 0 ({len(zero)} of them)")
    lcov = open(os.path.join(d_nb, "lcov.info")).read()
    check(re.search(r"^FNDA:0,", lcov, re.M) is not None and re.search(r"^DA:\d+,0$", lcov, re.M) is not None,
          "lcov.info lists the unreached methods and lines with 0")
    check(re.search(r"^SF:" + re.escape(src) + r"\n", lcov, re.M) is not None and lcov.count("end_of_record") == len(nb["files"]),
          "lcov.info: one record per source file")

    print("merging")
    d1, r1 = run(a, a, "one", [])
    d2, r2 = run(a, a, "shards", ["--shards", "2"])
    d3, r3 = run(a, a, "isolated", ["--isolate", "-j", "3"])
    check(all(x.returncode == 0 for x in (r1, r2, r3)), "all three runs passed")
    check(len(glob.glob(os.path.join(d2, "cov-*.cov"))) >= 2, "two shards wrote a file each")
    check(len(glob.glob(os.path.join(d3, "cov-*.cov"))) > 5, "isolated tests wrote a file per process")
    c1, c2, c3 = (covered(load(d)) for d in (d1, d2, d3))
    for name, c in (("two shards", c2), ("isolated processes", c3)):
        check(c == c1, f"{name} merge to the covered methods and lines of one process "
                       f"({len(c[0])} methods, {len(c[1])} lines)")
        if c != c1:
            for what, x, y in (("methods", c[0], c1[0]), ("lines", c[1], c1[1])):
                print(f"    {what} only here: {sorted(x - y)[:5]}  only in one process: {sorted(y - x)[:5]}")
    u1 = {(c["name"], m) for c in load(d1)["classes"] for m in c["methods"]}
    u3 = {(c["name"], m) for c in load(d3)["classes"] for m in c["methods"]}
    check(u1 == u3 and len(u1) > 100, f"the same {len(u1)} methods are reported by every run")
    again, _ = run(a, a, "one", [])
    check(covered(load(again)) == c1, "running into the same folder again starts from zero (old counts are removed)")

    print("R8 mapping")
    sys.path.insert(0, os.path.join(ROOT, "scripts"))
    import coverage
    mapping = os.path.join(a.here, "mapping.txt")
    open(mapping, "w").write(
        "com.example.shop.Cart -> dev.apksim.composeapp.NullBindingService:\n"
        "    14:14:void <clinit>():5:5 -> <clinit>\n"
        "    20:22:void restoreTotals():100:102 -> onBind\n"
        "    void drop() -> onDestroy\n")
    mapped = os.path.join(a.here, "mapped")
    coverage.write_reports(d_nb, mapped, mapping=mapping, quiet=True)
    mdoc = load(mapped)
    names = {c["name"]: c for c in mdoc["classes"]}
    check("com/example/shop/Cart" in names and cls not in names, "--mapping reports the original class name")
    ms = names.get("com/example/shop/Cart", {"methods": {}})["methods"]
    check(ms.get("restoreTotals(Landroid/content/Intent;)Landroid/os/IBinder;", 0) > 0 and ms.get("drop()V", 0) > 0, "...and the original method names")
    shop = [f for f in mdoc["files"] if f.startswith("com/example/shop/")]
    check(shop and mdoc["files"][shop[0]].get("100", 0) > 0 and "20" not in mdoc["files"][shop[0]],
          "...and the original line numbers where the mapping has them (20 -> 100)")

    print("jacoco.xml")
    doc = ET.parse(os.path.join(d1, "jacoco.xml")).getroot()
    errs = validate(doc)
    check(not errs, "structure follows report.dtd" + ("" if not errs else ": " + "; ".join(errs[:3])))
    tot = counters(doc)
    meth = sum(int(c.get("covered")) + int(c.get("missed")) for c in doc.iter("method") for c in c.findall("counter"))
    check(tot["METHOD"][0] + tot["METHOD"][1] == meth, "the report's METHOD counter is the number of methods listed")
    pk = [counters(x)["LINE"] for x in doc.findall("package")]
    check(tot["LINE"] == (sum(x[0] for x in pk), sum(x[1] for x in pk)), "package LINE counters add up to the report's")
    sf = [c for c in doc.iter("sourcefile") if c.get("name") == "NullBindingService.kt"]
    check(len(sf) == 1 and any(l.get("nr") == "20" for l in sf[0].findall("line")), "sourcefile NullBindingService.kt lists its lines")
    check(os.path.getsize(os.path.join(d1, "index.html")) > 2000 and "NullBindingService" in open(os.path.join(d1, "index.html")).read(),
          "index.html names the classes")

    print("no change in behaviour")
    base = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/instrument.py"), a.app, a.test, "-e", "class",
                           ",".join(PKG + c for c in SUITE)] + (["--apksim", a.apksim] if a.apksim else []),
                          capture_output=True, text=True, env={**os.environ, "APKSIM_STANDBY": "0"})
    n = lambda t: re.search(r"(\d+) tests in [\d.]+ s: (.*)", t).groups()
    check(n(base.stderr + base.stdout)[1] == n(r1.stderr + r1.stdout)[1] and n(base.stderr + base.stdout)[0] == n(r1.stderr + r1.stdout)[0],
          "the same tests pass without --coverage")
    print()
    if problems:
        print(f"{len(problems)} checks failed")
        sys.exit(1)
    print("coverage ok")


if __name__ == "__main__":
    main()
