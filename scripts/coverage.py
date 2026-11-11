#!/usr/bin/env python3
"""Merges the coverage of --instrument runs (apksim --coverage DIR) and writes reports (docs/coverage.md).

    scripts/coverage.py DIR [--mapping R8_MAPPING.txt] [--out OUT] [--source-prefix PREFIX] [--include-tests]
                            [--include GLOB]... [--exclude GLOB]... [--title TEXT] [--quiet]

DIR holds what the test processes wrote: `cov-*.cov` (what ran in one process; processes of shards, isolated tests and
reruns each write one) and `universe-*.cov` (every method and line of the build with no counts, so what nothing ran
reads 0). Hits are added up, so the result does not depend on how the suite was split. Writes to OUT (default DIR):

    jacoco.xml   JaCoCo's XML report (report.dtd structure; Codecov, Sonar and Jenkins read it)
    lcov.info    LCOV tracefile
    index.html   one self-contained page: totals, packages, classes
    coverage.json  the merged numbers (hits per method and line, by source file)

Names are the DEX's, or the original ones with --mapping (an R8/ProGuard mapping.txt: classes, methods and, where the
mapping has line ranges, lines). Kotlin inline functions' lines go to the file they came from through the class's SMAP.
"""
import argparse, fnmatch, glob, html, json, os, re, sys, time


# ---------------------------------------------------------------- reading

def unescape(s):
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def read_dir(path):
    """{class: {kind, source, smap, methods: {(name, desc): [hits, {line: hits}]}}} from every .cov file in path."""
    classes = {}
    files = sorted(glob.glob(os.path.join(path, "universe-*.cov"))) + sorted(glob.glob(os.path.join(path, "cov-*.cov")))
    if not files:
        raise SystemExit(f"coverage: no cov-*.cov files in {path}")
    for f in files:
        cls = method = None
        with open(f, encoding="utf-8") as fh:
            for raw in fh:
                row = raw.rstrip("\n").split("\t")
                k = row[0]
                if k == "C":
                    cls = classes.setdefault(row[1], {"kind": row[2], "source": None if row[3] == "-" else row[3],
                                                      "smap": None, "methods": {}})
                    method = None
                elif k == "S":
                    cls["smap"] = unescape(row[1])
                elif k == "M":
                    method = cls["methods"].setdefault((row[1], row[2]), [0, {}])
                    method[0] += int(row[3])
                elif k == "L":
                    method[1][int(row[1])] = method[1].get(int(row[1]), 0) + int(row[2])
    return classes, len(files)


# ---------------------------------------------------------------- SMAP (Kotlin inline functions)

def parse_smap(text):
    """(files {id: path}, ranges [(out_start, out_end, file id, in_start, increment)]) of the Kotlin stratum."""
    files, ranges = {}, []
    if not text:
        return files, ranges
    sections = re.split(r"^\*S ", text, flags=re.M)
    body = ""
    for sec in sections[1:]:
        if sec.startswith("Kotlin\n") or sec.startswith("Kotlin\r"):
            body = sec
            break
    else:
        body = sections[1] if len(sections) > 1 else ""
    mode, fid = None, 1
    lines = body.splitlines()
    i = 0
    while i < len(lines):
        ln = lines[i]
        i += 1
        if ln.startswith("*F"):
            mode = "F"
        elif ln.startswith("*L"):
            mode = "L"
        elif ln.startswith("*"):
            mode = None
        elif mode == "F":
            if ln.startswith("+ "):
                _, n, name = ln.split(" ", 2)
                path = lines[i] if i < len(lines) else name
                i += 1
                files[int(n)] = path
            else:
                parts = ln.split(" ", 1)
                if len(parts) == 2 and parts[0].isdigit():
                    files[int(parts[0])] = parts[1]
        elif mode == "L":
            m = re.fullmatch(r"(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?", ln.strip())
            if not m:
                continue
            ins, f, rep, out, inc = m.groups()
            if f:
                fid = int(f)
            rep, inc = int(rep or 1), int(inc or 1)
            ranges.append((int(out), int(out) + rep * inc, fid, int(ins), inc))
    return files, ranges


def smap_lookup(ranges, n):
    for out, end, fid, ins, inc in ranges:
        if out <= n < end:
            return fid, ins + (n - out) // inc
    return None


# ---------------------------------------------------------------- R8 mapping

class Mapping:
    """A mapping.txt: original class and method names for the obfuscated ones, original lines where it says."""

    ENTRY = re.compile(r"^(?:(\d+):(\d+):)?(\S+) (\S+?)\((.*)\)(?::(\d+)(?::(\d+))?)? -> (\S+)$")

    def __init__(self, path):
        self.classes = {}   # obfuscated internal name -> original internal name
        self.rev = {}       # original internal name -> obfuscated
        self.methods = {}   # obfuscated class -> [(obf name, params [original types], start, end, orig name, o_start, o_end)]
        cur = None
        with open(path, encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                if raw.startswith("#") or not raw.strip():
                    continue
                if not raw.startswith((" ", "\t")):
                    m = re.fullmatch(r"(\S+) -> (\S+):", raw.strip())
                    if m:
                        orig, obf = m.group(1).replace(".", "/"), m.group(2).replace(".", "/")
                        self.classes[obf] = orig
                        self.rev[orig] = obf
                        cur = obf
                    continue
                s = raw.strip()
                if cur is None or s.startswith("#") or "(" not in s:
                    continue
                m = self.ENTRY.match(s)
                if not m:
                    continue
                a, b, ret, name, params, os_, oe, obf_name = m.groups()
                self.methods.setdefault(cur, []).append((
                    obf_name, [p for p in params.split(",") if p], int(a) if a else None, int(b) if b else None,
                    name, int(os_) if os_ else None, int(oe) if oe else None))

    def cls(self, name):
        return self.classes.get(name, name)

    def desc_type(self, t):
        return {"V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long",
                "F": "float", "D": "double"}.get(t)

    def params_of(self, desc):
        """The parameter types of a JVM descriptor as source types, with the obfuscated class names."""
        out = []
        for dims, prim, ref in re.findall(r"(\[*)(?:([ZBCSIJFD])|L([^;]+);)", desc[1:desc.index(")")]):
            out.append((self.desc_type(prim) if prim else ref.replace("/", ".")) + "[]" * len(dims))
        return out

    def method(self, cls, name, desc, line):
        """(original name, original line or the given one) of the obfuscated method; entries that came from inlining
        another class's method (a dotted name) are not used."""
        entries = [e for e in self.methods.get(cls, []) if e[0] == name and "." not in e[4]]
        if not entries:
            return name, line
        have = self.params_of(desc)
        match = [e for e in entries if [self.obf_type(p) for p in e[1]] == have] or entries
        if line is not None:
            for e in match:
                if e[2] is not None and e[2] <= line <= e[3]:
                    if e[5] is None:
                        return e[4], line
                    if e[6] is not None and e[6] - e[5] == e[3] - e[2]:
                        return e[4], e[5] + line - e[2]
                    return e[4], e[5]
        return match[0][4], line

    def obf_type(self, t):
        base = t.rstrip("[]")
        return self.rev.get(base.replace(".", "/"), base.replace(".", "/")).replace("/", ".") + t[len(base):]

    def desc(self, d):
        """A descriptor with the classes' original names."""
        return re.sub(r"L([^;]+);", lambda m: "L" + self.cls(m.group(1)) + ";", d)


# ---------------------------------------------------------------- model

def build(classes, mapping=None, include_tests=False, include=(), exclude=()):
    """The merged model: {"classes": [...], "files": {path: {line: hits}}} with names mapped."""
    out_classes, files = {}, {}
    for name in sorted(classes):
        c = classes[name]
        if c["kind"] == "test" and not include_tests:
            continue
        shown = mapping.cls(name) if mapping else name
        dotted = shown.replace("/", ".")
        if include and not any(fnmatch.fnmatch(dotted, g) for g in include):
            continue
        if any(fnmatch.fnmatch(dotted, g) for g in exclude):
            continue
        pkg = shown.rsplit("/", 1)[0] if "/" in shown else ""
        source = c["source"] if c["source"] and "." in c["source"] else None  # D8$$SyntheticClass is no file
        own = (pkg + "/" if pkg else "") + source if source else None
        dex_pkg = name.rsplit("/", 1)[0] if "/" in name else ""
        dex_own = (dex_pkg + "/" if dex_pkg else "") + source if source else None  # the SMAP names the DEX's path
        sfiles, ranges = parse_smap(c["smap"])
        methods, own_lines = [], {}
        for (mname, desc), (hits, lines) in sorted(c["methods"].items()):
            first, oname = None, mname
            for ln, h in sorted(lines.items()):
                target, tline = own, ln
                if ranges:
                    r = smap_lookup(ranges, ln)
                    if r:
                        fid, tline = r
                        target = sfiles.get(fid, own)
                        if target == dex_own:
                            target = own
                        if target == own or (source and target.endswith("/" + source) and fid == 1):
                            target = own
                if target == own and mapping:
                    oname2, tline2 = mapping.method(name, mname, desc, tline)
                    tline = tline2 if tline2 is not None else tline
                if target:
                    f = files.setdefault(target, {})
                    f[tline] = f.get(tline, 0) + h
                if target == own:
                    own_lines[tline] = own_lines.get(tline, 0) + h
                    first = tline if first is None else min(first, tline)
            if mapping:
                oname = mapping.method(name, mname, desc, first)[0]
                desc = mapping.desc(desc)
            methods.append({"name": oname, "desc": desc, "line": first, "hits": hits})
        if own and own not in files:
            files[own] = {}
        merged = out_classes.setdefault(shown, {"name": shown, "package": pkg, "source": source, "file": own,
                                                "methods": [], "lines": {}})
        merged["methods"] += methods
        for ln, h in own_lines.items():
            merged["lines"][ln] = merged["lines"].get(ln, 0) + h
    return {"classes": [out_classes[k] for k in sorted(out_classes)], "files": files}


def counter(missed, covered):
    return {"missed": missed, "covered": covered}


def totals(items):
    """(missed, covered) of an iterable of hit counts."""
    items = list(items)
    c = sum(1 for h in items if h > 0)
    return len(items) - c, c


def class_counters(c):
    hits = [m["hits"] for m in c["methods"]]
    lm, lc = totals(list(c["lines"].values()))
    mm, mc = totals(hits)
    return {"INSTRUCTION": counter(lm, lc), "LINE": counter(lm, lc), "METHOD": counter(mm, mc),
            "CLASS": counter(0 if mc else 1, 1 if mc else 0)}


def sum_counters(parts):
    out = {}
    for p in parts:
        for t, v in p.items():
            o = out.setdefault(t, counter(0, 0))
            o["missed"] += v["missed"]
            o["covered"] += v["covered"]
    return out


def packages(model):
    """{package: {"classes": [...], "files": {name: {line: hits}}}} (a file lands in the package its path says)."""
    pk = {}
    for c in model["classes"]:
        pk.setdefault(c["package"], {"classes": [], "files": {}})["classes"].append(c)
    for path, lines in model["files"].items():
        pkg, _, name = path.rpartition("/")
        pk.setdefault(pkg, {"classes": [], "files": {}})["files"][name] = lines
    return dict(sorted(pk.items()))


def package_counters(p):
    lines = [h for f in p["files"].values() for h in f.values()]
    lm, lc = totals(lines)
    parts = [class_counters(c) for c in p["classes"]]
    c = sum_counters(parts)
    c["LINE"] = counter(lm, lc)
    c["INSTRUCTION"] = counter(lm, lc)
    return c


def report_counters(pks):
    parts = [package_counters(p) for p in pks.values()]
    return sum_counters(parts)


# ---------------------------------------------------------------- JaCoCo XML

COUNTER_ORDER = ["INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS"]


def xml_counters(c, ind):
    return "".join(f'{ind}<counter type="{t}" missed="{c[t]["missed"]}" covered="{c[t]["covered"]}"/>\n'
                   for t in COUNTER_ORDER if t in c)


def q(s):
    return html.escape(str(s), quote=True)


def jacoco_xml(model, title, now_ms):
    pks = packages(model)
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n',
           '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">\n',
           f'<report name="{q(title)}">\n',
           f'  <sessioninfo id="apksim" start="{now_ms}" dump="{now_ms}"/>\n']
    for pkg, p in pks.items():
        out.append(f'  <package name="{q(pkg)}">\n')
        for c in p["classes"]:
            out.append(f'    <class name="{q(c["name"])}"' + (f' sourcefilename="{q(c["source"])}"' if c["source"] else "") + ">\n")
            for m in sorted(c["methods"], key=lambda m: (m["line"] or 0, m["name"], m["desc"])):
                cov = m["hits"] > 0
                out.append(f'      <method name="{q(m["name"])}" desc="{q(m["desc"])}"' + (f' line="{m["line"]}"' if m["line"] else "") + ">\n")
                out.append(xml_counters({"METHOD": counter(0 if cov else 1, 1 if cov else 0)}, "        "))
                out.append("      </method>\n")
            out.append(xml_counters(class_counters(c), "      "))
            out.append("    </class>\n")
        for name, lines in p["files"].items():
            out.append(f'    <sourcefile name="{q(name)}">\n')
            for ln, h in sorted(lines.items()):
                out.append(f'      <line nr="{ln}" mi="{0 if h else 1}" ci="{1 if h else 0}" mb="0" cb="0"/>\n')
            lm, lc = totals(list(lines.values()))
            out.append(xml_counters({"INSTRUCTION": counter(lm, lc), "LINE": counter(lm, lc)}, "      "))
            out.append("    </sourcefile>\n")
        out.append(xml_counters(package_counters(p), "    "))
        out.append("  </package>\n")
    out.append(xml_counters(report_counters(pks), "  "))
    out.append("</report>\n")
    return "".join(out)


# ---------------------------------------------------------------- LCOV

def lcov(model, prefix):
    methods_by_file = {}
    for c in model["classes"]:
        if c["file"]:
            methods_by_file.setdefault(c["file"], []).extend(c["methods"])
    out = []
    for path in sorted(model["files"]):
        lines = model["files"][path]
        out.append("TN:\nSF:" + prefix + path + "\n")
        ms = [m for m in methods_by_file.get(path, []) if m["line"]]
        for m in sorted(ms, key=lambda m: (m["line"], m["name"], m["desc"])):
            out.append(f'FN:{m["line"]},{m["name"]}{m["desc"]}\n')
        for m in sorted(ms, key=lambda m: (m["line"], m["name"], m["desc"])):
            out.append(f'FNDA:{m["hits"]},{m["name"]}{m["desc"]}\n')
        out.append(f"FNF:{len(ms)}\nFNH:{sum(1 for m in ms if m['hits'] > 0)}\n")
        for ln in sorted(lines):
            out.append(f"DA:{ln},{lines[ln]}\n")
        out.append(f"LF:{len(lines)}\nLH:{sum(1 for h in lines.values() if h > 0)}\nend_of_record\n")
    return "".join(out)


# ---------------------------------------------------------------- HTML

def pct(c):
    n = c["missed"] + c["covered"]
    return None if n == 0 else 100.0 * c["covered"] / n


def bar(c):
    p = pct(c)
    if p is None:
        return '<td class="n">-</td><td></td>'
    return (f'<td class="n">{p:.1f}%</td><td><div class="bar"><i style="width:{p:.1f}%"></i></div></td>'
            f'<td class="n">{c["covered"]}/{c["covered"] + c["missed"]}</td>')


def html_page(model, title, now):
    pks = packages(model)
    total = report_counters(pks)
    head = "<tr><th>Name</th><th colspan=3>Lines</th><th colspan=3>Methods</th></tr>"

    def row(name, c, cls=""):
        return f'<tr class="{cls}"><td>{html.escape(name)}</td>{bar(c["LINE"])}{bar(c["METHOD"])}</tr>'

    pkg_rows = "".join(row(p or "(default)", package_counters(v)) for p, v in pks.items())
    sections = []
    for p, v in pks.items():
        rows = "".join(row(c["name"].rsplit("/", 1)[-1], class_counters(c))
                       for c in sorted(v["classes"], key=lambda c: c["name"]))
        sections.append(f'<details><summary>{html.escape(p or "(default)")} <span class="n">'
                        f'{pct(package_counters(v)["LINE"]) or 0:.1f}% lines</span></summary>'
                        f"<table>{head}{rows}</table></details>")
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title><style>
:root{{--bg:#fff;--fg:#1c1b1f;--mut:#625f66;--line:#e3e1e6;--ok:#2e7d32;--bad:#c62828;--bar:#eee}}
@media(prefers-color-scheme:dark){{:root{{--bg:#141316;--fg:#e6e1e6;--mut:#a19ca6;--line:#2e2c31;--ok:#66bb6a;--bad:#ef5350;--bar:#2a282d}}}}
body{{background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif;margin:0 auto;max-width:960px;padding:16px}}
h1{{font-size:1.4rem}}table{{border-collapse:collapse;width:100%;margin:8px 0 16px}}th,td{{padding:4px 8px;text-align:left;border-bottom:1px solid var(--line)}}
td.n{{text-align:right;font-variant-numeric:tabular-nums;color:var(--mut);white-space:nowrap}}.bar{{background:var(--bad);height:8px;border-radius:4px;min-width:80px;overflow:hidden}}
.bar i{{display:block;height:100%;background:var(--ok)}}summary{{cursor:pointer;padding:6px 0;font-weight:600}}p{{color:var(--mut)}}
</style></head><body><h1>{html.escape(title)}</h1>
<p>{now} &middot; {len(model["classes"])} classes in {len(pks)} packages &middot; lines {pct(total["LINE"]) or 0:.1f}%, methods {pct(total["METHOD"]) or 0:.1f}%</p>
<table>{head}{row("All", total, "tot")}{pkg_rows}</table>{"".join(sections)}</body></html>
"""


# ---------------------------------------------------------------- main

def write_reports(directory, out=None, mapping=None, source_prefix="", include_tests=False, include=(), exclude=(),
                  title="apksim coverage", quiet=False):
    """Merges DIR and writes the reports into OUT (default DIR); returns the totals {type: {missed, covered}}."""
    out = out or directory
    classes, nfiles = read_dir(directory)
    model = build(classes, Mapping(mapping) if mapping else None, include_tests, include, exclude)
    os.makedirs(out, exist_ok=True)
    now_ms = int(time.time() * 1000)
    total = report_counters(packages(model))
    with open(os.path.join(out, "jacoco.xml"), "w", encoding="utf-8") as f:
        f.write(jacoco_xml(model, title, now_ms))
    with open(os.path.join(out, "lcov.info"), "w", encoding="utf-8") as f:
        f.write(lcov(model, source_prefix))
    with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
        f.write(html_page(model, title, time.strftime("%Y-%m-%d %H:%M", time.localtime())))
    with open(os.path.join(out, "coverage.json"), "w", encoding="utf-8") as f:
        json.dump({"classes": [{"name": c["name"], "source": c["source"],
                                "methods": {m["name"] + m["desc"]: m["hits"] for m in c["methods"]}}
                               for c in model["classes"]],
                   "files": {k: {str(l): h for l, h in sorted(v.items())} for k, v in sorted(model["files"].items())}},
                  f, indent=1, sort_keys=True)
    if not quiet:
        l, m = total["LINE"], total["METHOD"]
        print(f"coverage: {nfiles} files merged: lines {l['covered']}/{l['covered'] + l['missed']} "
              f"({pct(l) or 0:.1f}%), methods {m['covered']}/{m['covered'] + m['missed']} ({pct(m) or 0:.1f}%) -> {out}",
              file=sys.stderr)
    return total


def main(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("dir")
    p.add_argument("--out")
    p.add_argument("--mapping", help="R8/ProGuard mapping.txt: report original names")
    p.add_argument("--source-prefix", default="", help="put before every path in lcov.info (app/src/main/java/)")
    p.add_argument("--include-tests", action="store_true", help="also report the test APK's own classes")
    p.add_argument("--include", action="append", default=[], metavar="GLOB", help="only classes matching (a.b.*)")
    p.add_argument("--exclude", action="append", default=[], metavar="GLOB")
    p.add_argument("--title", default="apksim coverage")
    p.add_argument("--quiet", action="store_true")
    a = p.parse_args(argv)
    write_reports(a.dir, a.out, a.mapping, a.source_prefix, a.include_tests, a.include, a.exclude, a.title, a.quiet)


if __name__ == "__main__":
    main(sys.argv[1:])
