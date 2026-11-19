#!/usr/bin/env python3
"""Launch-option list check: every option the launcher parses is decided for every surface.

The lists live apart: the launcher `apksim` (its option parsing), serve's `Catalog.LAUNCH_OPTIONS`/`LAUNCH_FLAGS` (the beam
agent takes the same list, adding a few options itself), and `scripts/mcp.py`'s allow-list. Four past issues (161, 393,
422, 429) were an option the command line took and a surface could not pass, found when someone needed it.
`scripts/launch-options.tsv` has one row per launcher option and a cell per surface:
  yes          the surface accepts it from a user (serve and beam: a catalog line, URL add or start, or a session option
               for --appearance/--locale/--font-scale/--permissions; mcp: `options`)
  server-only  the server adds it itself; a page cannot name it
  no: REASON   a deliberate refusal (touches files, ports or the JVM, ...); `no: undecided` is a gap nobody ruled on
`scripts/launch-options-undecided.txt` lists the undecided cells known today ("OPTION SURFACE" per line); a new one
fails the check, so a new option is decided where it is added. Fix a failure by editing the file the message names.
serve and beam are separate surfaces. They share Catalog's list and Session.java's launch code, so what the server adds is
found by line (a literal on a line with `.add(`, `.addAll(`, `.append(` or `cmd =`), and the few adds that sit in a
`serve.beam` or `!serve.beam` branch are named in ONLY_SERVE/ONLY_BEAM below with the line they come from. Because adds
are found by line, a conditional add on another line than its literal, or a literal split over lines, is not seen: a
refactor may need a table or exceptions edit. An option in Catalog's or mcp.py's sets that the launcher does not parse
(a typo) fails too.
Usage: scripts/check-launch-options.py [--list]   (--list prints what each source accepts)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAUNCHER = os.path.join(ROOT, "apksim")
CATALOG = os.path.join(ROOT, "runtime/src/main/java/dev/apksim/serve/Catalog.java")
SERVE = os.path.join(ROOT, "runtime/src/main/java/dev/apksim/serve/Serve.java")
SESSION = os.path.join(ROOT, "runtime/src/main/java/dev/apksim/serve/Session.java")
MCP = os.path.join(ROOT, "scripts/mcp.py")
TABLE = os.path.join(ROOT, "scripts/launch-options.tsv")
UNDECIDED = os.path.join(ROOT, "scripts/launch-options-undecided.txt")
SURFACES = ["serve", "beam", "mcp"]
OPT = r"--?[A-Za-z][A-Za-z0-9-]*"


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def rel(path):
    return os.path.relpath(path, ROOT)


def launcher_options():
    """{option: 'flag'|'value'|'pair'} from the case arms of the launcher's option loop (4-space arms)."""
    text = read(LAUNCHER)
    start = text.index('while [[ -z "${SERVE:-}" && $# -gt 1 ]]')
    end = text.index('*) echo "unknown option', start)
    body = text[start:end]
    arms = list(re.finditer(r"^    (%s(?:\|%s)*)\)" % (OPT, OPT), body, re.M))
    out = {}
    for n, m in enumerate(arms):
        chunk = body[m.end():arms[n + 1].start() if n + 1 < len(arms) else len(body)]
        shifts = [int(x or 1) for x in re.findall(r"\bshift(?: (\d))?", chunk)]
        kind = {1: "flag", 2: "value", 3: "pair"}.get(max(shifts) if shifts else 1, "pair")
        for name in m.group(1).split("|"):
            out[name] = kind
    return out


def java_set(text, name):
    m = re.search(r"\b%s\s*=\s*Set\.of\((.*?)\);" % name, text, re.S)
    if not m:
        raise SystemExit("check-launch-options: %s not found in %s (the parser here needs updating)" % (name, rel(CATALOG)))
    return set(re.findall(r'"(--[a-z0-9-]+)"', m.group(1)))


def serve_sets():
    text = read(CATALOG)
    return java_set(text, "LAUNCH_OPTIONS") | java_set(text, "LAUNCH_FLAGS")


def added_literals(text, launcher, marker=False):
    """Option literals on lines that add to a list (`cmd.add`, `launch.addAll(List.of(...))`): what code puts there itself."""
    out = set()
    for line in text.splitlines():
        if re.search(r"\.(add|addAll|append)\(|\bcmd\s*\+?=", line):
            out |= set(re.findall(r'"(--[a-z0-9-]+)"', line))
        if marker:
            out |= set(re.findall(r'_MARKER\s*=\s*"(--[a-z0-9-]+)"', line))
    return {o for o in out if o in launcher}


def session_literals(launcher):
    """Options the server or the beam agent puts on an instance's command line (Session.java, Serve.java)."""
    return added_literals(read(SESSION), launcher, True) | added_literals(read(SERVE), launcher)


def py_set(text, name):
    m = re.search(r"^%s\s*=\s*\{(.*?)\}" % name, text, re.S | re.M)
    if not m:
        raise SystemExit("check-launch-options: %s not found in %s (the parser here needs updating)" % (name, rel(MCP)))
    return set(re.findall(r'"(--[a-z0-9-]+)"', m.group(1)))


def mcp_sets(launcher):
    text = read(MCP)
    allowed = py_set(text, "LAUNCH_OPTIONS") | py_set(text, "LAUNCH_FLAGS")
    a = text.index("cmd = [self.apksim")
    b = text.index("cmd.append(apk)", a)
    return allowed, added_literals(text[a:b], launcher)


# Session.java's adds that only one of serve and beam makes (a `serve.beam` branch), by the line they are on today.
ONLY_SERVE = {"--headless",        # ~422: `serve.beam ? --window hidden : --headless`
              "--rec-window",      # ~440: `serve.allowRec && !serve.beam`
              "--rec-crash-dir"}   # ~456: `if (!serve.beam)`
ONLY_BEAM = {"--window",           # ~422
             "--jvm",              # ~445: the beam build cache
             "--adb",              # ~476: for ADB_MARKER (a beam start with "adb": true) while the owner allows it
             "--tab-relay",        # Serve.java ~2711: a beam start with "relay": true
             "--forward-link",     # Session ~437: `inspecting() && serve.beam`
             "--no-network-inspect"}  # Serve.java ~2719: a beam start with "inspect": false
# A tab's session options, passed on as these (Session.java ~427-434): `yes` for serve and beam, as a session option.
SESSION_OPTIONS = {"--appearance", "--locale", "--font-scale", "--permissions"}


def load_table():
    rows = {}
    with open(TABLE, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for ln, line in enumerate(f, 2):
            if not line.strip() or line.startswith("#"):
                continue
            cells = line.rstrip("\n").split("\t")
            if len(cells) != len(header):
                raise SystemExit("%s:%d: %d cells, expected %d" % (rel(TABLE), ln, len(cells), len(header)))
            rows[cells[0]] = dict(zip(header, cells), line=ln)
    return header, rows


def main():
    launcher = launcher_options()
    serve = serve_sets()
    server_added = session_literals(launcher)
    mcp_allowed, mcp_added = mcp_sets(launcher)
    if "--list" in sys.argv:
        print("launcher:", " ".join(sorted(launcher)))
        print("serve/beam list:", " ".join(sorted(serve)))
        print("serve adds:", " ".join(sorted(server_added)))
        print("mcp list:", " ".join(sorted(mcp_allowed)), "| mcp adds:", " ".join(sorted(mcp_added)))
        return 0
    header, rows = load_table()
    problems = []
    t = rel(TABLE)
    exceptions = ONLY_SERVE | ONLY_BEAM | SESSION_OPTIONS
    for name, src, path in (("Catalog's LAUNCH_OPTIONS/LAUNCH_FLAGS", serve, CATALOG),
                            ("mcp.py's LAUNCH_OPTIONS/LAUNCH_FLAGS", mcp_allowed, MCP),
                            ("the exceptions sets", exceptions, os.path.abspath(__file__))):
        for o in sorted(src - set(launcher)):
            problems.append("%s: %s names %s, which the launcher does not parse: fix the spelling or remove it" % (rel(path), name, o))
    for o in sorted(exceptions - server_added):
        problems.append("%s: %s is in an exceptions set but no line of Session.java/Serve.java adds it any more: update the set" % (
            rel(os.path.abspath(__file__)), o))
    for o in sorted(launcher):
        if o not in rows:
            problems.append("%s: the launcher parses %s but the table has no row: add one to %s (cli yes; for each of "
                            "serve, beam, mcp: yes, server-only or `no: REASON`)" % (rel(LAUNCHER), o, t))
    known = set(launcher)
    for o, r in sorted(rows.items()):
        if o not in known:
            problems.append("%s:%d: row %s is not an option the launcher parses: remove it (or fix the launcher)" % (t, r["line"], o))
            continue
        if r["cli"] != "yes":
            problems.append("%s:%d: %s cli is %r, the launcher parses it: say yes" % (t, r["line"], o, r["cli"]))
        if r["value"] != launcher[o]:
            problems.append("%s:%d: %s takes %s in the launcher, the table says %s" % (t, r["line"], o, launcher[o], r["value"]))
        # What each surface's code does with the option.
        def server_side(other_only):
            if o in serve or o in SESSION_OPTIONS:
                return "yes"
            return "server-only" if o in server_added and o not in other_only else "no"
        actual = {
            "serve": server_side(ONLY_BEAM),
            "beam": server_side(ONLY_SERVE),  # the agent takes Catalog's list and Session's additions
            "mcp": ("yes" if o in mcp_allowed else "server-only" if o in mcp_added else "no"),
        }
        files = {"serve": rel(CATALOG) + " (LAUNCH_OPTIONS) or " + rel(SESSION) + "/" + rel(SERVE), "beam": rel(CATALOG) + " or " + rel(SESSION) + "/" + rel(SERVE),
                 "mcp": rel(MCP)}
        for s in SURFACES:
            cell = r[s]
            kind = cell if cell in ("yes", "server-only") else "no" if cell.startswith("no: ") and len(cell) > 4 else None
            if kind is None:
                problems.append("%s:%d: %s %s is %r: use yes, server-only or `no: REASON`" % (t, r["line"], o, s, cell))
            elif kind != actual[s]:
                problems.append("%s:%d: %s %s: the table says %s but the code says %s: edit %s or the table" % (
                    t, r["line"], o, s, kind, actual[s], files[s]))
    allowed = set()
    if os.path.exists(UNDECIDED):
        for line in read(UNDECIDED).splitlines():
            if line.strip() and not line.startswith("#"):
                allowed.add(tuple(line.split()))
    seen = set()
    for o, r in sorted(rows.items()):
        for s in SURFACES:
            if r[s] == "no: undecided":
                seen.add((o, s))
                if (o, s) not in allowed:
                    problems.append("%s:%d: %s %s is undecided: decide it (yes, server-only or `no: REASON`) rather than adding "
                                    "it to %s" % (t, r["line"], o, s, rel(UNDECIDED)))
    for o, s in sorted(allowed - seen):
        problems.append("%s: %s %s is listed as undecided but the table no longer says so: delete the line" % (rel(UNDECIDED), o, s))
    for p in problems:
        print("FAIL " + p)
    if problems:
        print("launch-option check: %d problem(s)" % len(problems))
        return 1
    counts = {s: sum(1 for r in rows.values() if r[s] == "yes") for s in SURFACES}
    print("launch-option check ok: %d options; yes: cli %d, serve %d, beam %d, mcp %d; %d undecided" % (
        len(rows), len(rows), counts["serve"], counts["beam"], counts["mcp"], len(seen)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
