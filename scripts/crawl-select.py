#!/usr/bin/env python3
"""Selects which screens of a baseline crawl.py run (--record-deps) a code change can affect.

Reads the baseline crawl.json (its per-screen `deps` and `start_deps`) and a set of changed files (`--diff REV` or
`--changed PATH...`), maps Kotlin/Java source files and Android resource files (including individual changed entries in
values/*.xml) to the screens that depend on them, and writes an `--only-file` for:

    scripts/crawl.py NEW.apk --out DIR --seed BASE_RUN --only-file IDS --diff-seed

Falls back to mode="full" (exit status 0 with all screen ids or full-walk recommendation) when a change touches
`start_deps`, unprobed packages, build/manifest/asset files, or when the baseline has `deps_unreliable`.
"""
import argparse, fnmatch, json, os, re, subprocess, sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import affected  # noqa: E402

DEFAULT_IGNORES = (
    "*.md", "*.txt", "LICENSE*", "NOTICE*",
    "docs/*", "*/docs/*",
    ".github/*", ".claude/*", ".agents/*", ".gitignore", ".editorconfig",
    "iosApp/*", "*/iosApp/*",
    "*/src/test/*", "*/src/androidTest/*", "*/src/commonTest/*",
    "*/src/iosMain/*", "*/src/iosTest/*", "*/src/desktopMain/*", "*/src/desktopTest/*",
    "*/src/jvmTest/*", "*/src/jsMain/*", "*/src/wasmJsMain/*",
)

FILE_RES_TYPES = {
    "anim", "animator", "color", "drawable", "font", "interpolator",
    "layout", "menu", "mipmap", "navigation", "raw", "transition", "xml",
}

VALUE_TAG_TYPE = {
    "string": "string",
    "plurals": "plurals",
    "string-array": "array",
    "integer-array": "array",
    "array": "array",
    "color": "color",
    "dimen": "dimen",
    "bool": "bool",
    "integer": "integer",
    "style": "style",
    "attr": "attr",
    "fraction": "fraction",
    "id": "id",
}


def parse_values_xml(text):
    """Parses an Android res/values*/*.xml file into {'type/name': canonical_xml}."""
    out = {}
    if not text or not text.strip():
        return out
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return None
    for child in root:
        tag = child.tag.rsplit("}", 1)[-1]
        name = child.get("name")
        if not name:
            continue
        if tag == "item":
            rtype = child.get("type")
            if not rtype:
                continue
        elif tag == "declare-styleable":
            # Styleable attributes can be referenced as styleable/Name or attr/child
            out[f"styleable/{name}"] = ET.tostring(child, encoding="unicode")
            for sub in child:
                sname = sub.get("name")
                if sname and not sname.startswith("android:"):
                    out[f"attr/{sname}"] = ET.tostring(sub, encoding="unicode")
            continue
        else:
            rtype = VALUE_TAG_TYPE.get(tag)
            if not rtype:
                continue
        out[f"{rtype}/{name}"] = ET.tostring(child, encoding="unicode")
    return out


def base_ref_of(diff, repo):
    """Best-effort base commit for `git show <ref>:<path>` given a `--diff` spec like `main...HEAD` or `HEAD`."""
    if not diff:
        return None
    if "..." in diff:
        left, right = diff.split("...", 1)
        r = subprocess.run(["git", "-C", repo, "merge-base", left or "HEAD", right or "HEAD"],
                           capture_output=True, text=True)
        return r.stdout.strip() if r.returncode == 0 else None
    if ".." in diff:
        return diff.split("..", 1)[0] or "HEAD"
    return diff


def git_show_file(repo, ref, rel):
    if not ref:
        return None
    r = subprocess.run(["git", "-C", repo, "show", f"{ref}:{rel}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def match_resource_file(rel, full, repo, base_ref):
    """If [rel] is an Android res/ file, returns (keys_set, note) or (None, fallback_reason).
    Returns False if [rel] is not an Android res/ file."""
    norm = rel.replace("\\", "/")
    m = re.search(r"(?:^|/)res/([^/]+)/([^/]+)$", norm)
    if not m:
        return False
    folder, fname = m[1], m[2]
    base_type = folder.split("-", 1)[0]
    if base_type == "values" and fname.endswith(".xml"):
        new_text = read_text(full) if full else None
        old_text = git_show_file(repo, base_ref, norm) if base_ref else None
        new_map = parse_values_xml(new_text) if new_text is not None else {}
        old_map = parse_values_xml(old_text) if old_text is not None else None
        if new_map is None or (old_text is not None and old_map is None):
            return None, f"{rel}: could not parse values XML"
        if old_map is not None:
            keys = {k for k in (set(old_map) | set(new_map)) if old_map.get(k) != new_map.get(k)}
            return keys, f"{rel}: {len(keys)} changed value resource(s)"
        return set(new_map), f"{rel}: {len(new_map)} value resource(s) (no base ref to narrow)"
    if base_type in FILE_RES_TYPES:
        stem = fname[:-6] if fname.endswith(".9.png") else os.path.splitext(fname)[0]
        return {f"{base_type}/{stem}"}, f"{rel}: resource {base_type}/{stem}"
    return None, f"{rel}: unrecognized Android resource folder {folder!r}"


KMP_RES_TYPES = {"drawable", "font"}

KMP_SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "iosApp", ".idea", ".kotlin", ".claude", ".agents"}


def kmp_ident(name):
    """Compose Multiplatform resource identifier normalization (dashes/dots -> underscores)."""
    return re.sub(r"[^A-Za-z0-9_]", "_", name)


def match_kmp_resource_file(rel, full, repo, base_ref):
    """If [rel] is a Compose Multiplatform composeResources/ file, returns ([(kmp_type, ident)], note)
    or (None, fallback_reason). Returns False if [rel] is not a composeResources/ file."""
    norm = rel.replace("\\", "/")
    m = re.search(r"(?:^|/)composeResources/([^/]+)/(.+)$", norm)
    if not m:
        return False
    folder, rest = m[1], m[2]
    base_type = folder.split("-", 1)[0]
    if base_type == "values" and rest.endswith(".xml") and "/" not in rest:
        new_text = read_text(full) if full else None
        old_text = git_show_file(repo, base_ref, norm) if base_ref else None
        new_map = parse_values_xml(new_text) if new_text is not None else {}
        old_map = parse_values_xml(old_text) if old_text is not None else None
        if new_map is None or (old_text is not None and old_map is None):
            return None, f"{rel}: could not parse composeResources values XML"
        if old_map is not None:
            keys = {k for k in (set(old_map) | set(new_map)) if old_map.get(k) != new_map.get(k)}
        else:
            keys = set(new_map)
        out = []
        for k in sorted(keys):
            rtype, _, name = k.partition("/")
            if rtype not in ("string", "plurals", "array"):
                return None, f"{rel}: unsupported composeResources value type {rtype!r}"
            out.append((rtype, kmp_ident(name)))
        return out, f"{rel}: {len(out)} changed composeResources value(s)"
    if base_type in KMP_RES_TYPES and "/" not in rest:
        stem = rest[:-6] if rest.endswith(".9.png") else os.path.splitext(rest)[0]
        return [(base_type, kmp_ident(stem))], f"{rel}: composeResources {base_type}/{stem}"
    return None, f"{rel}: composeResources/{folder} cannot be mapped via Res.* references"


def find_kmp_resource_referrers(repo, items):
    """Scans Kotlin source files under [repo] for references to Res.<type>.<ident> or generated.resources.<ident>.
    Returns (list of (rel_path, full_path, matched_labels), scanned_count)."""
    if not items or not repo or not os.path.isdir(repo):
        return [], 0
    # Build per-item patterns so we can report which resource matched each file.
    patterns = [(f"Res.{rtype}.{ident}", re.compile(
        rf"\b(?:Res\.{re.escape(rtype)}\.{re.escape(ident)}|generated\.resources\.{re.escape(ident)})\b"
    )) for rtype, ident in items]
    combined = re.compile("|".join(f"(?:{p.pattern})" for _, p in patterns))
    hits = []
    scanned = 0
    repo_abs = os.path.abspath(repo)
    for root, dirs, files in os.walk(repo_abs):
        dirs[:] = [d for d in dirs if d not in KMP_SKIP_DIRS]
        rel_root = os.path.relpath(root, repo_abs).replace("\\", "/")
        norm_root = "/" + rel_root + "/"
        if any(seg in norm_root for seg in ("/src/test/", "/src/androidTest/", "/src/commonTest/",
                                            "/src/iosMain/", "/src/iosTest/", "/src/desktopMain/", "/src/desktopTest/")):
            dirs[:] = []
            continue
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            full = os.path.join(root, fn)
            scanned += 1
            text = read_text(full)
            if not text or not combined.search(text):
                continue
            matched = [label for label, pat in patterns if pat.search(text)]
            rel = os.path.relpath(full, repo_abs).replace("\\", "/")
            hits.append((rel, full, matched))
    return hits, scanned


CONST_RE = re.compile(r"\b(?:const\s+val|static\s+final\s+[\w<>\[\].]+)\s+(\w+)")
KT_CODE_RE = re.compile(r"\b(?:fun|class|interface)\s+\w|@Composable")
JAVA_CODE_RE = re.compile(r"\)\s*(?:throws\s+[\w.,\s]+)?\{|\bstatic\s*\{")  # a method body or static initializer
COMMENT_RE = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)


TEST_SETS = ("/src/test/", "/src/androidTest/", "/src/commonTest/", "/src/iosMain/", "/src/iosTest/", "/src/desktopMain/", "/src/desktopTest/")


def declared_constants(full_path):
    """Names of the constants a source file declares (`const val`, `static final`), when it declares nothing that
    runs (Kotlin: no fun/class/interface, `object` holders are fine; Java: no method body or static initializer):
    its constants are inlined into their users, so no screen records the file itself. [] otherwise."""
    text = read_text(full_path) if full_path else None
    if not text:
        return []
    text = COMMENT_RE.sub(" ", text)
    names = CONST_RE.findall(text)
    code = JAVA_CODE_RE if str(full_path).endswith(".java") else KT_CODE_RE
    return names if names and not code.search(text) else []


def find_constant_referrers(repo, names, exclude=None):
    """Source files under [repo] that mention one of [names] (a source-level scan, not a resolver).
    Returns ([(rel, full, [names])], scanned_count)."""
    if not names or not repo or not os.path.isdir(repo):
        return [], 0
    pats = [(n, re.compile(rf"\b{re.escape(n)}\b")) for n in names]
    hits, scanned = [], 0
    repo_abs = os.path.abspath(repo)
    for root, dirs, files in os.walk(repo_abs):
        dirs[:] = [d for d in dirs if d not in KMP_SKIP_DIRS]
        if any(seg in "/" + os.path.relpath(root, repo_abs).replace("\\", "/") + "/" for seg in TEST_SETS):
            dirs[:] = []
            continue
        for fn in files:
            if not fn.endswith(affected.SOURCE_EXTENSIONS):
                continue
            full_p = os.path.join(root, fn)
            rel = os.path.relpath(full_p, repo_abs).replace("\\", "/")
            if rel == exclude:
                continue
            scanned += 1
            text = read_text(full_p)
            if not text:
                continue
            m = [n for n, pat in pats if pat.search(text)]
            if m:
                hits.append((rel, full_p, m))
    return hits, scanned


def resource_matches(res_key, recorded_set):
    """Matches 'type/name' against recorded entries ('type/name' or 'pkg:type/name')."""
    if res_key in recorded_set:
        return True
    suffix = ":" + res_key
    return any(r.endswith(suffix) for r in recorded_set)


def select_screens(doc, changed, repo=".", diff=None, ignore=(), use_default_ignore=True, include_unexplored=True):
    """Maps [changed] = [(rel, full)] against [doc] (crawl.json with --record-deps).
    Returns dict: {mode: 'none'|'partial'|'full', selected: [id], reasons: {id: [str]}, notes: [str], fallback_reason: str|None}."""
    screens = doc.get("screens") or []
    all_ids = [s["id"] for s in screens]
    if "start_deps" not in doc:
        return {"mode": "full", "selected": all_ids, "reasons": {}, "notes": [],
                "fallback_reason": "baseline crawl.json has no start_deps (run baseline with --record-deps)",
                "full": {"file": None, "rule": "no-start-deps"}, "screens": {}, "ignored": [], "unplaced": [], "diff_controls": "all"}
    if doc.get("deps_unreliable"):
        return {"mode": "full", "selected": all_ids, "reasons": {}, "notes": [],
                "fallback_reason": f"baseline deps unreliable: {doc['deps_unreliable']}",
                "full": {"file": None, "rule": "deps-unreliable"}, "screens": {}, "ignored": [], "unplaced": [], "diff_controls": "all"}

    start_deps = doc.get("start_deps") or {}
    start_sources = set(start_deps.get("sources") or [])
    start_resources = set(start_deps.get("resources") or [])
    probed_packages = list(doc.get("deps_packages") or [])

    screen_sources = {}
    screen_resources = {}
    unexplored_ids = []
    for s in screens:
        d = s.get("deps")
        if d is None:
            unexplored_ids.append(s["id"])
        else:
            screen_sources[s["id"]] = set(d.get("sources") or [])
            screen_resources[s["id"]] = set(d.get("resources") or [])

    ignores = (list(DEFAULT_IGNORES) if use_default_ignore else []) + list(ignore or [])
    base_ref = base_ref_of(diff, repo) if diff else None

    selected_reasons = {}  # sid -> list of reason strings
    details = {}  # sid -> [{file, via, name?}] (machine-readable twin of selected_reasons)
    ignored_files = []
    unplaced = []  # changed sources no recorded screen ran, that nothing was found to select by
    notes = []
    counted = [0]  # changed files that were not ignored

    def add_reason(sid, reason, file=None, via=None, name=None):
        lst = selected_reasons.setdefault(sid, [])
        if reason not in lst:
            lst.append(reason)
        if file:
            d = {"file": file, "via": via}
            if name:
                d["name"] = name
            dl = details.setdefault(sid, [])
            if d not in dl:
                dl.append(d)

    def go_full(file, rule, why):
        return {"mode": "full", "selected": all_ids, "reasons": {}, "notes": notes, "fallback_reason": why,
                "full": {"file": file, "rule": rule}, "screens": {}, "ignored": ignored_files,
                "unplaced": unplaced, "diff_controls": "all"}

    for rel, full in changed:
        norm = rel.replace("\\", "/")
        if any(fnmatch.fnmatch(norm, g) for g in ignores):
            notes.append(f"{rel}: ignored")
            ignored_files.append(rel)
            continue
        counted[0] += 1

        if norm.endswith(affected.SOURCE_EXTENSIONS):
            base = os.path.basename(norm)
            pkg = affected.package_of(full) if (full and os.path.isfile(full)) else None
            if pkg is None:
                m = re.search(r"/(?:kotlin|java)/(.+)/[^/]+$", "/" + norm)
                pkg = m[1].replace("/", ".") if m else None
            key = ((pkg.replace(".", "/") + "/") if pkg else "") + base if pkg is not None else None
            # Check start_deps first
            if key and key in start_sources:
                return go_full(rel, "start_deps", f"{rel} ({key}) is in start_deps")
            if not key and any(("/" + norm).endswith("/" + k) for k in start_sources):
                return go_full(rel, "start_deps", f"{rel} is in start_deps")

            matched = []
            for sid, srcs in screen_sources.items():
                if (key and key in srcs) or (not key and any(("/" + norm).endswith("/" + k) for k in srcs)):
                    matched.append(sid)
                    add_reason(sid, f"source {rel}", rel, "source")
            if matched:
                notes.append(f"{rel} -> {len(matched)} screen(s)")
                continue

            # No recorded screen executed this source file: check if its package was probed.
            pkg_prefix = (pkg.replace(".", "/") + "/") if pkg else ""
            probed = pkg is not None and any(pkg_prefix.startswith(p) for p in probed_packages)
            if probed:
                consts = declared_constants(full)
                if consts:
                    hits, _scanned = find_constant_referrers(repo, consts, exclude=norm)
                    msc = set()
                    for ref_rel, ref_full, names in hits:
                        rpkg = affected.package_of(ref_full)
                        rkey = ((rpkg.replace(".", "/") + "/") if rpkg else "") + os.path.basename(ref_rel) if rpkg is not None else None
                        if rkey and rkey in start_sources:
                            return go_full(rel, "start_deps", f"{rel} (constant {names[0]}) is used by {ref_rel} in start_deps")
                        for sid, srcs in screen_sources.items():
                            if rkey and rkey in srcs:
                                msc.add(sid)
                                add_reason(sid, f"constant {names[0]} ({rel}) used by {ref_rel}", rel, "constant", names[0])
                    if msc:
                        notes.append(f"{rel}: constants only, referenced by {len(hits)} file(s) -> {len(msc)} screen(s)")
                        continue
                notes.append(f"{rel}: in probed packages, not executed by any recorded screen")
                unplaced.append(rel)
                continue
            return go_full(rel, "unprobed-package", f"{rel}: package {pkg!r} is outside probed packages ({', '.join(probed_packages)})")

        res = match_resource_file(norm, full, repo, base_ref)
        if res is not False:
            keys, note = res
            if keys is None:
                return go_full(rel, "unmappable-resource", note)
            for rk in sorted(keys):
                if resource_matches(rk, start_resources):
                    return go_full(rel, "start_deps", f"{rel}: resource {rk} is in start_deps")
            matched = []
            for rk in sorted(keys):
                for sid, rset in screen_resources.items():
                    if resource_matches(rk, rset):
                        matched.append(sid)
                        add_reason(sid, f"resource {rk} ({rel})", rel, "resource", rk)
            notes.append(f"{note} -> {len(set(matched))} screen(s)")
            continue

        kmp_res = match_kmp_resource_file(norm, full, repo, base_ref)
        if kmp_res is not False:
            items, note = kmp_res
            if items is None:
                return go_full(rel, "unmappable-resource", note)
            if not items:
                notes.append(f"{note} -> 0 screen(s)")
                continue
            referrers, scanned = find_kmp_resource_referrers(repo, items)
            if scanned == 0:
                return go_full(rel, "no-kotlin-sources", f"{rel}: no Kotlin sources found under {repo!r} to resolve composeResources")
            matched_screens = set()
            for ref_rel, ref_full, labels in referrers:
                pkg = affected.package_of(ref_full)
                key = ((pkg.replace(".", "/") + "/") if pkg else "") + os.path.basename(ref_rel) if pkg is not None else None
                if key and key in start_sources:
                    return go_full(rel, "start_deps", f"{rel} ({', '.join(labels)}) is referenced by {ref_rel} in start_deps")
                for sid, srcs in screen_sources.items():
                    if key and key in srcs:
                        matched_screens.add(sid)
                        add_reason(sid, f"{', '.join(labels)} via {ref_rel}", rel, "resource", ", ".join(labels))
            notes.append(f"{note} -> {len(referrers)} file(s), {len(matched_screens)} screen(s)")
            continue

        # Anything else (AndroidManifest.xml, build.gradle*, assets/, native code, etc.)
        return go_full(rel, f"unmappable-{affected.kind_of(norm)}", f"{rel} ({affected.kind_of(norm)}) cannot be mapped to screens")

    # A screen found but never explored has no deps: any change that is not ignored may reach it.
    if include_unexplored and counted[0]:
        for sid in unexplored_ids:
            add_reason(sid, "unexplored in baseline (no recorded deps)")
            details.setdefault(sid, []).append({"file": None, "via": "unexplored"})
    # A source change inside a click handler is invisible to --diff-controls changed (it compares labels), so when
    # a source selected a screen, recommend tapping all of its controls.
    via_source = any(d["via"] in ("source", "constant") for dl in details.values() for d in dl)
    base = {"notes": notes, "fallback_reason": None, "full": None, "ignored": ignored_files, "unplaced": unplaced,
            "diff_controls": "all" if via_source else "changed"}
    if not selected_reasons:
        return dict(base, mode="none", selected=[], reasons={}, screens={})
    selected = [s["id"] for s in screens if s["id"] in selected_reasons]
    return dict(base, mode="partial", selected=selected, reasons=selected_reasons,
                screens={sid: details[sid] for sid in selected if sid in details})


def unstable_in(doc):
    """The baseline's `unstable` screens (crawl.py: content that changed between two reads, requests that failed while
    reached), {id: why}: a --diff-seed against this baseline lists them apart, as `unstable`, rather than changed or gone."""
    return {s["id"]: s["unstable"] for s in doc.get("screens") or [] if s.get("unstable")}


def load_crawl_json(path):
    p = os.path.join(path, "crawl.json") if os.path.isdir(path) else path
    with open(p) as f:
        return json.load(f)


def main(argv=None):
    p = argparse.ArgumentParser(description="Select crawl.py screens affected by a git diff or list of changed files.")
    p.add_argument("crawl", help="baseline crawl.json or crawl output directory")
    p.add_argument("--diff", help="git diff revision spec (e.g. origin/main...HEAD or HEAD)")
    p.add_argument("--changed", nargs="+", default=[], help="changed file path(s) (or @file listing paths)")
    p.add_argument("--repo", default=".", help="repository directory for --diff and relative paths")
    p.add_argument("--ignore", action="append", default=[], help="extra glob(s) to ignore")
    p.add_argument("--no-default-ignore", action="store_true", help="do not ignore docs/tests/non-Android source sets")
    p.add_argument("--explored-only", action="store_true", help="do not automatically add unexplored baseline screens that have no recorded deps")
    p.add_argument("--out-ids", help="write selected screen ids (one per line, for crawl.py --only-file)")
    p.add_argument("--json", action="store_true", help="print result as JSON")
    args = p.parse_args(argv)

    if not args.diff and not args.changed:
        p.error("give --diff REV or --changed PATH...")

    doc = load_crawl_json(args.crawl)
    changed = affected.changed_files(args.changed, args.diff, args.repo)
    # Resolve relative --changed paths against --repo when they don't exist relative to CWD
    resolved = []
    for rel, full in changed:
        if not os.path.exists(full) and args.repo != ".":
            cand = os.path.join(args.repo, rel)
            if os.path.exists(cand):
                full = cand
        resolved.append((rel, full))

    res = select_screens(doc, resolved, repo=args.repo, diff=args.diff,
                         ignore=args.ignore, use_default_ignore=not args.no_default_ignore,
                         include_unexplored=not args.explored_only)
    res["unstable"] = unstable_in(doc)

    if args.out_ids:
        with open(args.out_ids, "w") as f:
            if res["mode"] == "full":
                f.write(f"# full walk required: {res['fallback_reason']}\n")
            for sid in res["selected"]:
                why = "; ".join(res["reasons"].get(sid, []))
                if why:
                    f.write(f"# {why}\n")
                f.write(f"{sid}\n")

    if args.json:
        print(json.dumps(res, indent=2))
        return

    screens_by_id = {s["id"]: s for s in doc.get("screens", [])}
    if res["mode"] == "full":
        print(f"FULL walk needed ({len(res['selected'])} screens): {res['fallback_reason']}")
    elif res["mode"] == "none":
        print(f"0 of {len(screens_by_id)} screens affected")
        if res["unplaced"]:
            print(f"  but {len(res['unplaced'])} changed file(s) are in no screen's sources (not ignored): "
                  "that does not show the change is invisible")
    else:
        print(f"{len(res['selected'])} of {len(screens_by_id)} screen(s) affected:")
        for sid in res["selected"]:
            title = screens_by_id.get(sid, {}).get("title", "")
            why = ", ".join(res["reasons"].get(sid, []))
            print(f"  {sid}" + (f" ({title})" if title else "") + (f"  <- {why}" if why else ""))
    for n in res["notes"]:
        print("  note: " + n)
    shaky = [sid for sid in res["selected"] if sid in res["unstable"]]
    if shaky:
        print(f"  note: {len(shaky)} selected screen(s) are unstable in the baseline (a --diff-seed lists them apart): "
              + ", ".join(shaky))
    if res["unplaced"]:
        print("  unplaced: " + ", ".join(res["unplaced"]))
    if res["mode"] != "full":
        print(f"  suggested --diff-controls: {res['diff_controls']}")


if __name__ == "__main__":
    main()
