"""Test-impact analysis and time-balanced shards for instrument.py (docs/affected-tests.md).

- Recording (`instrument.py --record-deps FILE`): apksim runs with `-Dapksim.deps=true`, which gives every class of the
  app's packages a probe at each method's start and reports, per test, the classes whose code ran
  (`INSTRUMENTATION_APKSIM_DEPS:` lines, see Instrument.DepsListener). `write_deps` merges what every process reported
  with the APKs' class -> source file map into one gzip'd JSON file.
- Selection (`--affected-by FILE --changed PATH... | --diff REV`): `affected_classes` maps changed source files to
  classes through each class's DEX source file and package, plus the files Kotlin inlined into it (its SMAP); a test is
  selected when it reached one of them, or when the file has no record of it (a new test, or one whose process died).
  Anything the class map cannot see (resources, manifests, build files, assets, a source file of no recorded class)
  selects the whole suite.
- Balanced shards (`--shards N --timings XML`): `balance` splits tests longest first over the shards by the times an
  earlier JUnit XML measured.

Also a command for looking at a deps file: `affected.py show DEPS [--changed PATH...|--diff REV]`.
"""
import fnmatch, gzip, json, os, re, struct, subprocess, sys, zipfile
import xml.etree.ElementTree as ET

PREFIX = "INSTRUMENTATION_APKSIM_DEPS: "
SOURCE_EXTENSIONS = (".kt", ".java")
NO_INDEX = 0xFFFFFFFF


# ---------------------------------------------------------------- DEX: class -> source files

class Dex:
    """Just enough of a DEX file for class names, source files and Kotlin's SMAP."""

    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off, self.type_ids_size, self.type_ids_off) = struct.unpack_from("<4I", data, 0x38)
        (self.class_defs_size, self.class_defs_off) = struct.unpack_from("<2I", data, 0x60)
        self._strings = {}

    def uleb(self, off):
        result = shift = 0
        while True:
            b = self.d[off]
            off += 1
            result |= (b & 0x7F) << shift
            if b < 0x80:
                return result, off
            shift += 7

    def string(self, idx):
        s = self._strings.get(idx)
        if s is None:
            off = struct.unpack_from("<I", self.d, self.string_ids_off + idx * 4)[0]
            _, off = self.uleb(off)
            end = self.d.index(b"\0", off)
            # MUTF-8: NUL as C0 80 and supplementary characters as surrogate pairs; neither occurs in names that matter
            s = self.d[off:end].decode("utf-8", "replace")
            self._strings[idx] = s
        return s

    def type(self, idx):
        return self.string(struct.unpack_from("<I", self.d, self.type_ids_off + idx * 4)[0])

    def classes(self):
        """(internal class name, source file name or None, SMAP text or None) for each class_def."""
        for i in range(self.class_defs_size):
            off = self.class_defs_off + i * 32
            class_idx, _, _, _, source_idx, annotations_off = struct.unpack_from("<6I", self.d, off)
            desc = self.type(class_idx)
            source = None if source_idx == NO_INDEX else self.string(source_idx)
            yield desc[1:-1], source, self.smap(annotations_off) if annotations_off else None

    def smap(self, directory_off):
        """The class's dalvik.annotation.SourceDebugExtension value (Kotlin's SMAP), or None."""
        set_off = struct.unpack_from("<I", self.d, directory_off)[0]
        if not set_off:
            return None
        size = struct.unpack_from("<I", self.d, set_off)[0]
        for k in range(size):
            item = struct.unpack_from("<I", self.d, set_off + 4 + k * 4)[0]
            type_idx, p = self.uleb(item + 1)  # after the visibility byte
            if self.type(type_idx) != "Ldalvik/annotation/SourceDebugExtension;":
                continue
            count, p = self.uleb(p)
            if count < 1:
                return None
            _, p = self.uleb(p)  # name_idx: "value"
            head = self.d[p]
            if head & 0x1F != 0x17:  # VALUE_STRING
                return None
            n = (head >> 5) + 1
            return self.string(int.from_bytes(self.d[p + 1:p + 1 + n], "little"))
        return None


def smap_files(smap):
    """The source files an SMAP names, as package paths (`com/example/Util.kt`): Kotlin lists each file a class's code
    came from (its own, and every file whose inline functions were inlined into it) as `+ id Name.kt` followed by the
    class that declares them, whose package is the file's."""
    out, lines = [], smap.splitlines()
    in_files = False
    for i, line in enumerate(lines):
        if line.startswith("*F"):
            in_files = True
        elif line.startswith("*"):
            in_files = False
        elif in_files and line.startswith("+ ") and i + 1 < len(lines):
            name = line.split(" ", 2)[2]
            owner = lines[i + 1]
            out.append((owner.rsplit("/", 1)[0] + "/" if "/" in owner else "") + name)
    return out


def source_map(apks, packages):
    """{internal class name: set of source keys (`com/example/Foo.kt`)} for the classes of [packages] (internal-name
    prefixes, `com/example/`) in [apks]. A class with no source file name (R8 strips them) has no keys."""
    out = {}
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            for name in z.namelist():
                if not re.fullmatch(r"classes\d*\.dex", name):
                    continue
                dex = Dex(z.read(name))
                for cls, source, smap in dex.classes():
                    if not any(cls.startswith(p) for p in packages):
                        continue
                    keys = out.setdefault(cls, set())
                    if source and source not in ("SourceFile", "D8$$SyntheticClass") and not source.startswith("r8-map-id-"):
                        keys.add((cls.rsplit("/", 1)[0] + "/" if "/" in cls else "") + source)
                    if smap:
                        keys.update(smap_files(smap))
    # D8's synthetic classes (lambdas, `Foo$$ExternalSyntheticLambda0`) name no source file of their own: they are
    # their outer class's code.
    for cls, keys in out.items():
        outer = cls
        while not keys and "$" in outer:
            outer = outer[:outer.rindex("$")].rstrip("$")
            keys.update(out.get(outer, ()))
    return out


# ---------------------------------------------------------------- recording

def unescape(s):
    return re.sub(r"\\(.)", lambda m: {"t": "\t", "n": "\n"}.get(m[1], m[1]), s)


def parse_process(lines):
    """One process's deps lines -> (packages, {(class, method): set of class names}, {test class: set of class names})."""
    names, tests, gaps, packages = {}, {}, {}, []
    for line in lines:
        f = line[len(PREFIX):].rstrip("\n").split("\t")
        if f[0] == "P":
            packages = [p for p in f[1].split(",") if p]
        elif f[0] == "C":
            names[int(f[1])] = unescape(f[2])
        elif f[0] == "T":
            got = {names[int(i)] for i in f[3].split()}
            tests.setdefault((unescape(f[1]), unescape(f[2])), set()).update(got)
        elif f[0] == "G":
            gaps.setdefault(unescape(f[1]), set()).update(names[int(i)] for i in f[2].split())
    return packages, tests, gaps


def write_deps(path, processes, apks):
    """Merges every process's lines ([processes]: one list of lines each) into the deps file at [path]."""
    tests, gaps, packages = {}, {}, set()
    for lines in processes:
        p, t, g = parse_process(lines)
        packages.update(p)
        for k, v in t.items():
            tests.setdefault(k, set()).update(v)
        for k, v in g.items():
            gaps.setdefault(k, set()).update(v)
    for (c, _), reached in tests.items():
        reached |= gaps.get(c, set()) | gaps.get("*", set())  # "*": before the first test, for every test
    try:
        sources = source_map(apks, sorted(packages))
    except OSError as e:
        raise SystemExit(f"--record-deps reads the APKs' classes, and could not open them here ({e}); with --apksim, "
                         "run from the folder the container mounts, so APP and TEST name the same files on both sides")
    classes = sorted(set(sources) | {n.replace(".", "/") for v in tests.values() for n in v})
    index = {c: i for i, c in enumerate(classes)}
    by_key = {}
    for cls, keys in sources.items():
        for k in keys:
            by_key.setdefault(k, []).append(index[cls])
    doc = {
        "version": 1,
        "apks": [os.path.basename(a) for a in apks],
        "packages": sorted(packages),
        # The share of probed classes the map can place: near 1 on a debug build, near 0 on an R8-minified one.
        "named": round(sum(1 for k in sources.values() if k) / max(1, len(sources)), 4),
        "classes": classes,
        "sources": {k: sorted(v) for k, v in sorted(by_key.items())},
        "tests": {f"{c}#{m}": sorted(index[n.replace(".", "/")] for n in v) for (c, m), v in sorted(tests.items())},
    }
    with gzip.open(path, "wt", encoding="utf-8") as f:
        json.dump(doc, f, separators=(",", ":"))
    reached = [len(v) for v in doc["tests"].values()]
    print(f"recorded {len(doc['tests'])} tests, {len(classes)} classes of {', '.join(p.rstrip('/').replace('/', '.') for p in sorted(packages))} "
          f"({sum(1 for c in classes if sources.get(c))} with a source file), {sorted(reached)[len(reached) // 2] if reached else 0} "
          f"reached by the median test; {os.path.getsize(path) // 1024} KB in {path}", file=sys.stderr)
    return doc


def read_deps(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        doc = json.load(f)
    if doc.get("version") != 1:
        raise SystemExit(f"{path}: not a deps file this instrument.py reads (version {doc.get('version')})")
    return doc


# ---------------------------------------------------------------- selection

def changed_files(paths=None, diff=None, repo="."):
    """[(path relative to the repository or as given, its path here, which may be gone)] from --changed or
    --diff REV (`git diff --name-only REV`, so `origin/main...HEAD` is the branch's own changes and a single revision
    compares it with the working tree)."""
    out = []
    if diff:
        top = subprocess.run(["git", "-C", repo, "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip()
        names = subprocess.run(["git", "-C", repo, "diff", "--name-only", "--no-renames", diff], capture_output=True, text=True,
                               check=True).stdout.splitlines()
        out += [(n, os.path.join(top, n)) for n in names]
    for p in paths or []:
        if p.startswith("@"):  # a file listing them, one a line
            with open(p[1:]) as f:
                out += [(n, n) for n in (l.strip() for l in f) if n]
        else:
            out.append((p, p))
    return out


def package_of(path):
    """The package a Kotlin or Java source file declares, or None (none, or unreadable)."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read(65536)
    except OSError:
        return None
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    for line in text.splitlines():
        line = line.split("//")[0].strip()
        if not line or line.startswith("@file:") or line.startswith("#!"):
            continue
        m = re.match(r"package\s+([\w.`]+)", line)
        return m[1].replace("`", "") if m else ""  # the first statement: a package, or none (the default package)
    return ""


def affected_classes(doc, changed, extra_sources=None, ignore=()):
    """The classes the changed files are compiled into, or (None, reason) when the change needs the whole suite.
    [extra_sources]: {class: keys} from the APKs being tested now, for classes the recording's build did not have."""
    by_key = {k: {doc["classes"][i] for i in v} for k, v in doc["sources"].items()}
    for cls, keys in (extra_sources or {}).items():
        for k in keys:
            by_key.setdefault(k, set()).add(cls)
    classes, notes, modules = set(), [], {}
    for rel, full in changed:
        if any(fnmatch.fnmatch(rel, g) for g in ignore):
            notes.append(f"{rel}: ignored")
            continue
        if not rel.endswith(SOURCE_EXTENSIONS):
            module, inside = module_in_apks(full, by_key, doc, modules)
            if inside is False:
                notes.append(f"{rel}: its module ({os.path.relpath(module)}) has no code in these APKs")
                continue
            module = module and os.path.relpath(module)
            return None, (f"{rel} is not Kotlin or Java source ({kind_of(rel)}) " + (
                f"of a module these APKs hold ({module})" if inside else
                "outside any module" if module is None else f"of a module whose code the recording cannot place ({module})")
                + ": the class map cannot see what it affects")
        base = os.path.basename(rel)
        keys = []
        pkg = package_of(full) if full else None  # None: gone
        if pkg is not None:
            keys = [(pkg.replace(".", "/") + "/" if pkg else "") + base]
            if keys[0] not in by_key:
                keys = []
        if not keys:  # deleted, or its package is not in the class map: by its path (`.../java/com/example/Foo.kt`)
            keys = [k for k in by_key if ("/" + rel).endswith("/" + k)]
        if not keys:
            if pkg is None:  # gone: its package from its path (`src/main/kotlin/com/example/Foo.kt`)
                m = re.search(r"/(?:kotlin|java)/(.+)/[^/]+$", "/" + rel)
                pkg = m[1].replace("/", ".") if m else None
            probed = pkg is not None and any((pkg.replace(".", "/") + "/").startswith(p) for p in doc["packages"])
            if probed and doc.get("named", 0) >= 0.9:
                # In the probed packages, and these APKs name the source of nearly every class there: no class of
                # them comes from this file, so no test of this suite can run its code (another module's file, an
                # iOS or desktop source set, a file of declarations only).
                notes.append(f"{rel}: in none of these APKs' classes")
                continue
            return None, (f"{rel} is in no class the recording knows, and " + (
                f"the recorded build names the source file of only {doc.get('named', 0):.0%} of its classes (minified?)"
                if probed else "its package was not probed (-Dapksim.deps.packages)" if pkg is not None
                else "its package is unknown"))
        got = set().union(*(by_key[k] for k in keys))
        classes |= got
        notes.append(f"{rel}: {len(got)} classes")
    return classes, notes


def module_in_apks(path, by_key, doc, cache):
    """(the Gradle module a file belongs to, whether these APKs hold its code: True, False, or None for cannot tell).
    The module is the nearest folder above the file with a build.gradle(.kts) that is not the root project (the one
    with settings.gradle(.kts)); a file of the root project, or of no module, has none. Its code is in the APKs when
    one of its Kotlin or Java files is in the class map; it is not when its files are in the probed packages of an APK
    that names its sources, and none is there; with no such files (a module of resources only) it cannot be told."""
    if not path:
        return None, None
    d = os.path.dirname(os.path.abspath(path))
    while True:
        if any(os.path.exists(os.path.join(d, f)) for f in ("settings.gradle.kts", "settings.gradle")):
            return None, None  # the root project: its build files and anything else at the top
        if any(os.path.exists(os.path.join(d, f)) for f in ("build.gradle.kts", "build.gradle")):
            break
        parent = os.path.dirname(d)
        if parent == d:
            return None, None
        d = parent
    if d in cache:
        return d, cache[d]
    probed_any, inside = False, False
    for root, dirs, files in os.walk(d):
        dirs[:] = [x for x in dirs if x not in ("build", ".gradle", ".git", "node_modules")
                   and not os.path.exists(os.path.join(root, x, "build.gradle.kts"))
                   and not os.path.exists(os.path.join(root, x, "build.gradle"))]  # not into nested modules
        for f in files:
            if not f.endswith(SOURCE_EXTENSIONS):
                continue
            pkg = package_of(os.path.join(root, f))
            if pkg is None:
                continue
            key = (pkg.replace(".", "/") + "/" if pkg else "") + f
            if key in by_key:
                inside = True
                break
            if any((pkg.replace(".", "/") + "/").startswith(p) for p in doc["packages"]):
                probed_any = True
        if inside:
            break
    cache[d] = True if inside else False if probed_any and doc.get("named", 0) >= 0.9 else None
    return d, cache[d]


def kind_of(path):
    p = path.replace("\\", "/")
    if "/res/" in p or p.startswith("res/"):
        return "a resource"
    if p.endswith("AndroidManifest.xml"):
        return "a manifest"
    if p.endswith((".gradle", ".gradle.kts", ".toml", ".properties", ".pro")) or "/gradle/" in p:
        return "a build file"
    if "/assets/" in p:
        return "an asset"
    return "a file of another kind"


def select(doc, listing, classes):
    """The tests of [listing] to run for a change to [classes]: those that reached one of them, and those the recording
    has nothing on (new tests, tests whose process died while recording)."""
    names = {c.replace("/", "."): i for i, c in enumerate(doc["classes"])}
    hit = {names[c.replace("/", ".")] for c in classes if c.replace("/", ".") in names}
    out, unknown = [], 0
    for c, m in listing:
        reached = doc["tests"].get(f"{c}#{m}")
        if reached is None:
            unknown += 1
            out.append((c, m))
        elif hit.intersection(reached):
            out.append((c, m))
    return out, unknown


# ---------------------------------------------------------------- balanced shards

def read_timings(paths):
    """{(class, method): seconds} from JUnit XML files (testcase classname/name/time); the last file wins."""
    out = {}
    for p in paths or []:
        for case in ET.parse(p).getroot().iter("testcase"):
            t = case.get("time")
            if t is not None:
                out[(case.get("classname"), case.get("name"))] = float(t)
    return out


def balance(tests, timings, shards):
    """[tests] in [shards] lists of nearly equal measured time: longest first, each to the shard with the least so far
    (tests without a time count as the median of those with one). Returns (lists, their predicted seconds)."""
    known = sorted(v for t, v in timings.items())
    default = known[len(known) // 2] if known else 1.0
    # A test the XML timed at 0.000 s (it rounds to ms) still costs something: with no cost at all, every such test
    # would go to the first shard.
    cost = {t: max(timings.get(t, default), 0.001) for t in tests}
    order = sorted(tests, key=lambda t: (-cost[t], t))
    lists, totals = [[] for _ in range(shards)], [0.0] * shards
    for t in order:
        i = min(range(shards), key=lambda k: (totals[k], k))
        lists[i].append(t)
        totals[i] += cost[t]
    position = {t: i for i, t in enumerate(tests)}  # each shard runs its tests in the suite's order
    return [sorted(l, key=position.get) for l in lists], totals


# ---------------------------------------------------------------- command line

def main(argv):
    import argparse
    p = argparse.ArgumentParser(description="Look at a deps file that instrument.py --record-deps wrote.")
    p.add_argument("command", choices=["show"])
    p.add_argument("deps")
    p.add_argument("--changed", nargs="+", default=[])
    p.add_argument("--diff")
    p.add_argument("--repo", default=".")
    p.add_argument("--ignore", action="append", default=[])
    a = p.parse_args(argv)
    doc = read_deps(a.deps)
    print(f"{len(doc['tests'])} tests, {len(doc['classes'])} classes, {len(doc['sources'])} source files; packages "
          + ", ".join(doc["packages"]))
    if a.changed or a.diff:
        classes, notes = affected_classes(doc, changed_files(a.changed, a.diff, a.repo), ignore=a.ignore)
        if classes is None:
            print("whole suite: " + notes)
            return
        for n in notes:
            print("  " + n)
        listing = [tuple(t.split("#", 1)) for t in doc["tests"]]
        chosen, _ = select(doc, listing, classes)
        print(f"{len(chosen)} of {len(listing)} recorded tests reach them")
        for c, m in chosen:
            print(f"  {c}#{m}")


if __name__ == "__main__":
    main(sys.argv[1:])
