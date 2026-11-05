#!/usr/bin/env python3
"""Checks instrument.py's test-impact selection and balanced shards on the test app (docs/affected-tests.md).

Usage: affected-check.py [--image IMAGE] [--keep]

1. Records the test app's androidTest suite (--record-deps) with JUnit XML for timings.
2. For each case below, breaks one source file (a change that compiles and that some test notices), rebuilds the two
   APKs with Gradle, runs the whole suite on the broken build and then only the tests --affected-by selects for that
   file. Every test that fails on the broken build must be among the selected ones and fail there too, and the
   selection must leave tests out. The file is put back afterwards, whatever happens.
3. Without running anything: the app module's resources, manifest and build file and the root project's files select
   the whole suite; a source or build file of a module outside the APKs and an ignored path select nothing; a test
   the recording has no record of is always selected.
4. --shards 3 --timings: every test runs exactly once, and the measured shard times are reported.

--image runs apksim in that Linux image (as CI does: `--apksim "docker run ..."`), else ./apksim. Work goes to
work/affected-check (APKs, deps file, XML); --keep leaves it there.
"""
import argparse, os, shutil, subprocess, sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(__file__)) + "/..")
sys.path.insert(0, ROOT + "/scripts")
import affected  # noqa: E402

PROJECT = ROOT + "/testapks/composeapp"
SRC = "testapks/composeapp/app/src"
OUT = PROJECT + "/app/build/outputs/apk"
# (file, text in it, what to put instead): each makes some tests fail and compiles.
CASES = [
    (f"{SRC}/main/kotlin/dev/apksim/composeapp/NullBindingService.kt", 'events.add("bind")', 'events.add("bound")'),
    (f"{SRC}/main/kotlin/dev/apksim/composeapp/MainActivity.kt", "onClick = { count++ }", "onClick = { count += 2 }"),
    (f"{SRC}/androidTest/java/dev/apksim/composeapp/SyncCache.java", "return minVersion % 2;", "return minVersion % 2 + 1;"),
]


def cases(xml):
    """{(class, method): failed?} from a JUnit XML file."""
    return {(c.get("classname"), c.get("name")): c.find("failure") is not None for c in ET.parse(xml).iter("testcase")}


def build(into):
    subprocess.run(["./gradlew", "-q", ":app:assembleDebug", ":app:assembleDebugAndroidTest"], cwd=PROJECT, check=True)
    os.makedirs(into, exist_ok=True)
    shutil.copy(OUT + "/debug/app-debug.apk", into + "/app.apk")
    shutil.copy(OUT + "/androidTest/debug/app-debug-androidTest.apk", into + "/test.apk")


def instrument(work, apks, *args):
    """instrument.py from [work] (the folder an image mounts), on [apks] (a folder under it); returns its exit code
    and stderr."""
    cmd = [sys.executable, ROOT + "/scripts/instrument.py"]
    if IMAGE:
        cmd += ["--apksim", f"docker run --rm -v {work}:/work {IMAGE}"]
    cmd += [f"{apks}/app.apk", f"{apks}/test.apk", *args]
    r = subprocess.run(cmd, cwd=work, capture_output=True, text=True)
    return r.returncode, r.stderr


def main():
    global IMAGE
    p = argparse.ArgumentParser(usage=__doc__)
    p.add_argument("--image")
    p.add_argument("--keep", action="store_true")
    a = p.parse_args()
    IMAGE = a.image
    work = ROOT + "/work/affected-check"
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    problems = []

    def check(ok, what):
        print(("  ok    " if ok else "  FAIL  ") + what)
        if not ok:
            problems.append(what)

    print("recording the suite")
    shutil.copytree(OUT, work + "/base-out")
    os.makedirs(work + "/base")
    shutil.copy(work + "/base-out/debug/app-debug.apk", work + "/base/app.apk")
    shutil.copy(work + "/base-out/androidTest/debug/app-debug-androidTest.apk", work + "/base/test.apk")
    code, err = instrument(work, "base", "--record-deps", "deps.json.gz", "--xml", "base.xml")
    check(code == 0, "the suite passes while recording")
    base = cases(work + "/base.xml")
    doc = affected.read_deps(work + "/deps.json.gz")
    every = sorted(base)
    check(set(doc["tests"]) >= {f"{c}#{m}" for c, m in every}, f"every one of the {len(every)} tests has a record")

    for path, old, new in CASES:
        name = os.path.basename(path)
        print(f"breaking {name}")
        full = ROOT + "/" + path
        original = open(full).read()
        if original.count(old) != 1:
            check(False, f"{name}: '{old}' is not in it exactly once (the test app changed: update CASES)")
            continue
        case = os.path.splitext(name)[0]
        try:
            with open(full, "w") as f:
                f.write(original.replace(old, new))
            build(f"{work}/{case}")
        finally:
            with open(full, "w") as f:
                f.write(original)
        instrument(work, case, "--xml", f"{case}-all.xml")
        code, err = instrument(work, case, "--affected-by", "deps.json.gz", "--changed", full, "--xml", f"{case}-sel.xml")
        whole, chosen = cases(f"{work}/{case}-all.xml"), cases(f"{work}/{case}-sel.xml")
        broke = {t for t, failed in whole.items() if failed}
        check(bool(broke), f"{name}: the change makes {len(broke)} tests fail on the whole suite")
        check(broke <= set(chosen), f"{name}: every one of them is selected ({len(chosen)} of {len(whole)} run)"
              + ("" if broke <= set(chosen) else f"; missed {sorted(broke - set(chosen))}"))
        check({t for t, failed in chosen.items() if failed} == broke, f"{name}: the selected run fails exactly those")
        check(len(chosen) < len(whole), f"{name}: {len(whole) - len(chosen)} unaffected tests are left out")
    build(work + "/rebuilt")  # leaves the test app's outputs as they were

    print("changes the class map cannot see, and tests it has no record of")
    for path, why in [(f"{SRC}/main/res/values/strings.xml", "a resource of the app"),
                      ("testapks/composeapp/app/build.gradle.kts", "the app's build file"),
                      (f"{SRC}/main/AndroidManifest.xml", "the app's manifest"),
                      ("testapks/composeapp/settings.gradle.kts", "the root project's settings"),
                      ("testapks/composeapp/gradle/libs.versions.toml", "the version catalog")]:
        classes, note = affected.affected_classes(doc, [(path, ROOT + "/" + path)])
        check(classes is None, f"{why} runs the whole suite ({note})")
    for path, why in [("testapks/composeapp/uiserver/src/main/java/dev/apksim/uiserver/UiServer.java",
                       "a source file of another APK's module"),
                      ("testapks/composeapp/uiserver/build.gradle.kts", "that module's build file")]:
        classes, notes = affected.affected_classes(doc, [(path, ROOT + "/" + path)])
        check(classes == set(), f"{why} selects nothing ({notes})")
    classes, notes = affected.affected_classes(doc, [("docs/x.md", None), ("README.md", None)], ignore=["*.md"])
    check(classes == set(), "ignored paths select nothing")
    unrecorded = dict(doc, tests={t: v for t, v in doc["tests"].items() if not t.startswith("dev.apksim.composeapp.GlesTest#")})
    chosen, unknown = affected.select(unrecorded, every, set())
    check(chosen and all(c.endswith("GlesTest") for c, _ in chosen) and unknown == len(chosen),
          f"tests with no record (GlesTest's {unknown}) are selected even when nothing they reached changed")

    print("balanced shards")
    code, err = instrument(work, "base", "--shards", "3", "--timings", "base.xml", "--xml", "shards.xml")
    ran = cases(work + "/shards.xml")
    check(code == 0 and sorted(ran) == every, f"--shards 3 --timings runs each of the {len(every)} tests once")
    for line in err.splitlines():
        if "shard" in line:
            print("        " + line.strip())

    if not a.keep:
        shutil.rmtree(work, ignore_errors=True)
    if problems:
        sys.exit(f"{len(problems)} checks failed")
    print("all checks passed")


IMAGE = None
main()
