#!/usr/bin/env python3
"""App corpus, the offline half: what apksim's translator and native loader would trip on in real APKs, found without
running them. docs/corpus.md.

  scripts/corpus.py [--list scripts/corpus.json] [--cache work/corpus] [--only NAME[,NAME]] [--json OUT]
                    [--fail-on-new BASELINE.json] [--selftest]

For each APK of the list (fetched into CACHE/NAME/SHA256.apk, checked against the list's hash, never run or installed):
  1. every class is translated as apksim does (`scripts/link-check.java`, the same ClassTranslator calls as
     translate-all.java): classes the translator throws on are `translate failures`;
  2. every invoke instruction of the translated output is resolved as HotSpot would at its first run, against the
     app's own hierarchy and the platform's (the android-all jar, apksim.jar, the JDK): `link errors`
     (IncompatibleClassChangeError, NoSuchMethodError, IllegalAccessError, AbstractMethodError: the shapes of issues 275,
     299, 522);
  3. the arm64 libraries' imports that no library of the APK, no table of the shim (native/*.c) and no host library
     provides (`scripts/so-imports.py`'s reader): `missing imports`, which the loader binds to a stub that logs when
     called (elfloader.c `missing_stub`).
Output: a table, and with --json a file. Exit 0 whatever was found (findings are the product), 1 on a tool error (no
build, a fetch or hash failure, the Java tool failing), 3 when --fail-on-new BASELINE.json finds a finding the
baseline lacks. --selftest runs the link check on testapks/translator-fixture, which must pass as built and fail with
the translator's hierarchy knowledge taken away (the check's own proof; no download).
"""
import argparse, ctypes, hashlib, importlib.util, json, os, re, shutil, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = "apk-corpus/1.0"
MAX_APK = 300 << 20  # bytes: the largest of the list is ~125 MB


def java_bin():
    # The JDK build.sh used first (apksim.jar is Java 22+ and JAVA_HOME may be an older one), then JAVA_HOME.
    for home in (open(os.path.join(ROOT, "work/jdk.path")).read().strip() if os.path.exists(os.path.join(ROOT, "work/jdk.path")) else None, os.environ.get("JAVA_HOME")):
        if home and not os.path.isabs(home):
            home = os.path.join(ROOT, home)
        if home and os.path.exists(os.path.join(home, "bin/java")):
            return os.path.join(home, "bin/java")
    return shutil.which("java") or sys.exit("corpus: no java (run scripts/build.sh, or set JAVA_HOME)")


def lib_dir():
    return os.path.join(ROOT, "work/lib")


def classpaths():
    """(tool classpath, platform jars): ASM and apksim.jar run the tool; apksim.jar and android-all are what code calls."""
    jar = os.path.join(ROOT, "work/apksim.jar")
    if not os.path.exists(jar):
        sys.exit("corpus: work/apksim.jar is missing (scripts/build.sh)")
    libs = sorted(os.listdir(lib_dir())) if os.path.isdir(lib_dir()) else []
    asm = [os.path.join(lib_dir(), f) for f in libs if f.startswith("asm") and f.endswith(".jar")]
    android = [os.path.join(lib_dir(), f) for f in libs if f.startswith("android-all") and f.endswith(".jar")]
    if not asm or not android:
        sys.exit("corpus: work/lib lacks the asm or android-all jars (gradle deps)")
    return os.pathsep.join([jar] + asm), os.pathsep.join([jar] + android[:1])


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def fetch(app, cache):
    """CACHE/NAME/SHA256.apk, downloaded with curl (macOS's Python has no CA bundle) and verified; None and a message on failure."""
    want = app["sha256"].lower()
    d = os.path.join(cache, app["name"])
    path = os.path.join(d, want + ".apk")
    if os.path.exists(path) and sha256(path) == want:
        return path, None
    os.makedirs(d, exist_ok=True)
    tmp = path + ".part"
    errs = []
    # F-Droid moves a superseded build from /repo to /archive: each app lists every URL that has held it, tried in order.
    for url in app.get("urls") or [app["url"]]:
        r = subprocess.run(["curl", "-fsSL", "--proto", "=https", "--proto-redir", "=https", "--max-filesize", str(MAX_APK),
                            "-A", UA, "-m", "900", "-o", tmp, url], capture_output=True, text=True)
        if r.returncode != 0:
            errs.append("%s: %s" % (url, (r.stderr.strip().splitlines() or ["curl " + str(r.returncode)])[-1]))
            if os.path.exists(tmp):
                os.unlink(tmp)
            continue
        got = sha256(tmp)
        if got != want:
            os.unlink(tmp)
            errs.append("%s: sha256 mismatch: got %s, list says %s" % (url, got, want))
            continue
        os.replace(tmp, path)
        return path, None
    return None, "download failed: " + "; ".join(errs)


# ---- native imports

def shim_symbols():
    """The names the loader's shim tables answer (bionic_lookup and its helper tables), for this OS."""
    names = set()
    mac = sys.platform == "darwin"
    files = ["native/bionic.c", "native/bionic_net.c"] if mac else ["native/linux/bionic_linux.c"]
    for f in files:
        text = open(os.path.join(ROOT, f)).read()
        names.update(re.findall(r'\bE\("([^"]+)"', text))
        if f.endswith("bionic_net.c"):
            names.update(re.findall(r'\{"([A-Za-z_0-9]+)",\s*net_', text))
    for f, prefix in (("native/jnigraphics.c", "AndroidBitmap_"), ("native/alooper.c", "ALooper_")):
        text = open(os.path.join(ROOT, f)).read()
        names.update(prefix + n for n in re.findall(r'\{"([A-Za-z_0-9]+)",\s*\(void \*\)', text))
    return names


_hosts = None


def host_has(name):
    """dlsym(RTLD_DEFAULT) after the libraries a JVM has loaded: libc, libm, libz, libc++ where there is one."""
    global _hosts
    if _hosts is None:
        _hosts = [ctypes.CDLL(None)]
        for lib in (("/usr/lib/libc++.1.dylib", "/usr/lib/libc++abi.dylib", "/usr/lib/libz.1.dylib") if sys.platform == "darwin"
                    else ("libz.so.1", "libm.so.6", "libstdc++.so.6", "libdl.so.2")):
            try:
                _hosts.append(ctypes.CDLL(lib))
            except OSError:
                pass
    for h in _hosts:
        try:
            h[name]
            return True
        except (AttributeError, OSError):
            pass
    return False


def so_imports():
    spec = importlib.util.spec_from_file_location("so_imports", os.path.join(ROOT, "scripts/so-imports.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def missing_imports(apk, so, shim):
    """{lib: [symbol]}: undefined, non-weak imports of the APK's arm64 libraries that nothing provides."""
    import zipfile
    libs, errors = {}, []
    with zipfile.ZipFile(apk) as z:
        for e in z.namelist():
            if e.startswith("lib/arm64-v8a/") and e.endswith(".so"):
                try:
                    libs[e.split("/")[-1]] = so.parse(z.read(e))
                except Exception as ex:  # an unreadable library is a finding of its own
                    errors.append("%s: unreadable (%s)" % (e.split("/")[-1], type(ex).__name__))
    provided = set(s for v in libs.values() for s in v[2])
    out = {}
    cache = {}
    for lib, (need, und, defd, weak) in sorted(libs.items()):
        miss = []
        for s in und:
            if s in provided or s in weak:
                continue
            if s not in cache:
                cache[s] = s in shim or host_has(s)
            if not cache[s]:
                miss.append(s)
        if miss:
            out[lib] = sorted(set(miss))
    return len(libs), out, errors


# ---- running

def link_check(apk, out_json, cp, platform, timeout):
    try:
        r = subprocess.run([java_bin(), "-Xmx6g", "-cp", cp, os.path.join(ROOT, "scripts/link-check.java"), apk, out_json, platform],
                           capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None, "link-check failed: timed out after %d s" % timeout
    if r.returncode != 0 or not os.path.exists(out_json):
        return None, "link-check failed (%d): %s" % (r.returncode, (r.stderr or r.stdout).strip()[-600:])
    return json.load(open(out_json)), None


def run_app(app, path, cp, platform, so, shim, tmp):
    row = {"name": app["name"], "stack": app.get("stack", ""), "sha256": app["sha256"], "size": os.path.getsize(path)}
    t0 = time.time()
    rep, err = link_check(path, os.path.join(tmp, app["name"] + ".json"), cp, platform, 3600)
    if err:
        row["error"] = err
        return row
    row.update(dexFiles=rep["dexFiles"], classes=rep["classes"], translateMs=rep["translateMs"], shadowedByPlatform=rep.get("shadowedByPlatform", 0),
               translateFailures=rep["translateFailures"], invokes=rep["invokes"], unresolvedCalls=rep["unresolvedCalls"],
               linkKinds=rep["linkKinds"], linkErrors=rep["linkErrors"], unresolvedOwners=rep["unresolvedOwners"])
    nlibs, miss, errs = missing_imports(path, so, shim)
    row["linkErrorsTruncated"] = len(rep["linkErrors"]) < sum(rep["linkKinds"].values())
    row.update(nativeLibs=nlibs, missingImports=miss, nativeErrors=errs, seconds=round(time.time() - t0, 1))
    return row


def count_missing(row):
    return sum(len(v) for v in row.get("missingImports", {}).values())


def table(rows):
    head = ("APK", "classes", "translate failures", "link errors", "missing imports", "unreadable libs")
    body = []
    for r in rows:
        if "error" in r:
            body.append((r["name"], "-", "-", "-", "ERROR", "-"))
        else:
            kinds = ", ".join("%s %d" % kv for kv in sorted(r["linkKinds"].items()))
            body.append((r["name"], str(r["classes"]), str(len(r["translateFailures"])),
                         str(sum(r["linkKinds"].values())) + (" (%s)" % kinds if kinds else ""),
                         "%d in %d libs" % (count_missing(r), len(r["missingImports"])) if r["nativeLibs"] else "no arm64 libs",
                         str(len(r["nativeErrors"]))))
    w = [max(len(x[i]) for x in [head] + body) for i in range(6)]
    lines = ["  ".join(c.ljust(w[i]) for i, c in enumerate(x)).rstrip() for x in [head] + body]
    lines.insert(1, "  ".join("-" * x for x in w))
    return "\n".join(lines)


def keys(rows):
    """Every finding as a string, per app, for --fail-on-new."""
    out = {}
    for r in rows:
        if "error" in r:
            continue
        k = set()
        k.update("translate %s" % f["class"] for f in r["translateFailures"])
        k.update("link %s %s.%s %s" % (f["kind"], f["class"], f["method"], f["insn"]) for f in r["linkErrors"])
        k.update("unreadable %s" % e for e in r.get("nativeErrors", []))
        k.update("link-errors-truncated" for _ in [0] if r.get("linkErrorsTruncated"))
        k.update("import %s %s" % (lib, s) for lib, syms in r["missingImports"].items() for s in syms)
        out[r["name"]] = k
    return out


def selftest(cp, platform):
    """The fixtures pass as built; with the hierarchy taken away (issues 275, 299, 522 again) the check must fail."""
    fx = os.path.join(ROOT, "testapks/translator-fixture")
    cases = [("fixture.dex", None, 0), ("fixture-d8-debug.dex", None, 0), ("multidex", None, 0),
             ("fixture.dex", "none", 1), ("fixture-d8-debug.dex", "none", 1), ("multidex", "single", 1), ("multidex", "none", 1)]
    bad = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, mode, want_errors in cases:
            out = os.path.join(tmp, "o.json")
            cmd = [java_bin()] + (["-Dlinkcheck.hierarchy=" + mode] if mode else []) + ["-Xmx2g", "-cp", cp, os.path.join(ROOT, "scripts/link-check.java"), os.path.join(fx, name), out, platform]
            r = subprocess.run(cmd, capture_output=True, text=True)
            if r.returncode != 0:
                print("selftest: tool error on %s: %s" % (name, r.stderr.strip()[-300:]))
                return 1
            errs = json.load(open(out))["linkErrors"]
            n = len(errs)
            # The broken-hierarchy cases must show the very shape of issues 275, 299 and 522, not just any error.
            ok = (n == 0) if want_errors == 0 else any(e["kind"] == "icce-indirect-super" for e in errs)
            print("selftest %-22s hierarchy=%-6s link errors %d: %s" % (name, mode or "all", n, "ok" if ok else "WRONG"))
            bad += not ok
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--list", default=os.path.join(ROOT, "scripts/corpus.json"))
    ap.add_argument("--cache", default=os.path.join(ROOT, "work/corpus"))
    ap.add_argument("--only", help="comma-separated app names")
    ap.add_argument("--json", help="write the full report here")
    ap.add_argument("--fail-on-new", metavar="BASELINE", help="exit 3 when a finding is not in this earlier --json file")
    ap.add_argument("--selftest", action="store_true", help="check the link check on the translator fixtures and exit")
    a = ap.parse_args()
    cp, platform = classpaths()
    if a.selftest:
        return selftest(cp, platform)
    apps = json.load(open(a.list))["apps"]
    if a.only:
        want = set(a.only.split(","))
        apps = [x for x in apps if x["name"] in want]
        if not apps:
            sys.exit("corpus: --only matches nothing in " + a.list)
    so, shim = so_imports(), shim_symbols()
    rows, tool_error = [], False
    with tempfile.TemporaryDirectory() as tmp:
        for app in apps:
            print("== %s" % app["name"], flush=True)
            path, err = fetch(app, a.cache)
            if err:
                print("   " + err, flush=True)
                rows.append({"name": app["name"], "sha256": app["sha256"], "error": err})
                tool_error = True
                continue
            row = run_app(app, path, cp, platform, so, shim, tmp)
            if "error" in row:
                print("   " + row["error"], flush=True)
                tool_error = True
            rows.append(row)
    print()
    print(table(rows))
    for r in rows:
        for f in r.get("translateFailures", [])[:5]:
            print("  translate failure %s: %s: %s" % (r["name"], f["class"], f["error"][:160]))
        for f in r.get("linkErrors", [])[:10]:
            print("  link error %s: %s %s.%s: %s" % (r["name"], f["kind"], f["class"], f["method"], f["insn"]))
    sha = subprocess.run(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"apksim": sha, "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "apps": rows}, f, indent=1)
    code = 1 if tool_error else 0
    if a.fail_on_new:
        base = json.load(open(a.fail_on_new))
        old = keys(base["apps"])
        new = {n: k - old.get(n, set()) for n, k in keys(rows).items()}
        new = {n: sorted(k) for n, k in new.items() if k}
        for n, k in new.items():
            for x in k[:20]:
                print("  NEW %s: %s" % (n, x))
        print("new findings: %d" % sum(len(k) for k in new.values()))
        if new and not tool_error:
            code = 3
    return code


if __name__ == "__main__":
    sys.exit(main())
