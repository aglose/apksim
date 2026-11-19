#!/usr/bin/env python3
"""Checks what `settle` says for each source of "the app still has work" in the test app's apksim://idle-zoo page
(IdleZooActivity), alone and in a few combinations, against what a device user would call it. Several past issues were
apksim deciding wrongly whether the app is idle (83, 131, 197, 204, 215, 360, 459, 475, 479, 489), each for a different
source; this puts them all in one place before their rules are unified.

  scripts/idle-zoo-check.py CONTROL_PORT [--tag TAG] [--known FILE] [--max-ms N] [--only A,B] [--no-combos]

CONTROL_PORT: an instance of the test app started with --control (and --headless). Run it against an instance
started with `--max-fps 10` too (`--tag maxfps10`: the frame-rate cap changes what lets a frame through, issue 459).
For each case: `restart apksim://idle-zoo?on=X`, then `settle`, which must answer
  idle             `settled`
  busy             `error: not settled ...` (an endless animation; settle gives up at --max-ms)
  busy then idle   `settled` within the source's own time plus a margin (a Scroller's 1000 ms, a view animation's 300 ms)
and prints a table: source, expected, got, ms, and the reason text of a not-settled answer. Exit 1 on a mismatch that
is not in the known-failures file (scripts/idle-zoo-known.txt: `TAG CASE : reason`, TAG `*` for every run, CASE the
sources joined by `+`); a listed failure that now passes is reported as FIXED and also exits 1, so the list shrinks as
the rules are fixed. The expectations below repeat the table in IdleZooActivity's comment (nodes shows each label's
own "expect ..." text; the check reads it back and fails if they differ).
"""
import argparse
import os
import re
import socket
import sys
import time

# source -> (expect, within_ms). Keep in step with IdleZooActivity.Source.
SOURCES = {
    "ticker": ("idle", 0),
    "slowticker": ("idle", 0),
    "caret": ("idle", 0),
    "scroller": ("busy-then-idle", 1000),
    "animator": ("busy", 0),
    "viewanim": ("busy-then-idle", 300),
    "sensor": ("idle", 0),
    "overlay": ("idle", 0),
    "popup": ("idle", 0),
    "worker": ("idle", 0),
    "progress": ("busy", 0),
}
IDLE_ONES = [s for s, (e, _) in SOURCES.items() if e == "idle"]
COMBOS = [
    ["ticker", "animator"],
    ["ticker", "worker"],
    ["slowticker", "caret"],
    ["caret", "sensor", "overlay", "popup"],
    ["scroller", "ticker"],
    ["viewanim", "slowticker"],
    ["scroller", "viewanim"],
    ["progress", "caret"],
    IDLE_ONES,
    list(SOURCES),
]
MARGIN_MS = 1500   # a busy-then-idle source may settle this long after its own time (restart, quiet period)
EARLY = 0.5        # ... and not before this share of it: settled while still moving


class Control:
    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=120)
        self.f = self.s.makefile("r", encoding="utf-8")

    def __call__(self, cmd):
        self.s.sendall((cmd + "\n").encode())
        return self.f.readline().strip()


def expectation(names):
    """(expect, within_ms) of a set of sources switched on together."""
    es = [SOURCES[n] for n in names]
    if any(e == "busy" for e, _ in es):
        return "busy", 0
    bti = [w for e, w in es if e == "busy-then-idle"]
    if bti:
        return "busy-then-idle", max(bti)
    return "idle", 0


def run_case(ctl, names, max_ms):
    expect, within = expectation(names)
    r = ctl("restart apksim://idle-zoo?on=" + ",".join(names))
    if not r.startswith("ok"):
        return expect, "restart failed", 0, r, False
    limit = max_ms if expect != "busy-then-idle" else within + 3000
    t0 = time.time()
    ans = ctl(f"settle {limit}")
    wall = int((time.time() - t0) * 1000)
    m = re.match(r"settled in (\d+) ms", ans)
    if m:
        ms = int(m.group(1))
        reason = re.sub(r"^settled in \d+ ms\s*", "", ans)
        if expect == "busy":
            return expect, "settled", ms, reason, False
        if expect == "busy-then-idle":
            if ms > within + MARGIN_MS:
                return expect, "settled late", ms, reason, False
            if ms < within * EARLY:
                return expect, "settled early", ms, reason, False
        return expect, "settled", ms, reason, True
    m = re.match(r"error: not settled after (\d+) ms:?\s*(.*)", ans)
    if m:
        return expect, "not settled", int(m.group(1)), m.group(2), expect == "busy"
    return expect, "?", wall, ans, False


def read_known(path, tag):
    known = {}
    if not os.path.exists(path):
        return known
    for line in open(path, encoding="utf-8"):
        line = line.split("#", 1)[0].strip() if line.lstrip().startswith("#") else line.strip()
        if not line:
            continue
        head, _, why = line.partition(" : ")
        t, _, case = head.partition(" ")
        if t in ("*", tag):
            known[case.strip()] = why.strip()
    return known


def check_labels(ctl):
    """The page's own 'expect' text must match this script's table."""
    ctl("restart apksim://idle-zoo")
    ctl("settle 3000")
    text = ctl("nodes")
    bad = []
    for name, (expect, within) in SOURCES.items():
        m = re.search(re.escape(name) + r": (?:on|off), \d+ counted \(expect ([^)]*)\)", text)
        want = {"idle": "idle", "busy": "busy", "busy-then-idle": f"busy then idle within {within} ms"}[expect]
        if not m or m.group(1) != want:
            bad.append(f"{name}: page says {m.group(1) if m else 'nothing'}, script expects {want}")
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", type=int)
    ap.add_argument("--tag", default="default")
    ap.add_argument("--known", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "idle-zoo-known.txt"))
    ap.add_argument("--max-ms", type=int, default=3000, help="settle's limit for an idle or busy case")
    ap.add_argument("--only", help="comma list: run only these single sources")
    ap.add_argument("--no-combos", action="store_true")
    a = ap.parse_args()

    ctl = Control(a.port)
    bad = check_labels(ctl)
    if bad:
        print("idle zoo check FAILED: the page and the script disagree:\n  " + "\n  ".join(bad), file=sys.stderr)
        sys.exit(1)
    cases = [[s] for s in SOURCES if not a.only or s in a.only.split(",")]
    if not a.no_combos and not a.only:
        cases += COMBOS
    known = read_known(a.known, a.tag)
    rows, mismatches, fixed = [], [], []
    for names in cases:
        case = "+".join(names)
        expect, got, ms, reason, ok = run_case(ctl, names, a.max_ms)
        label = case if len(case) <= 34 else f"{len(names)} sources: " + names[0] + "..."
        rows.append((label, expect, got, ms, reason, ok))
        if not ok and case not in known:
            mismatches.append((case, expect, got, reason))
        if ok and case in known:
            fixed.append(case)
        if not ok:
            rows[-1] = rows[-1][:5] + (("known" if case in known else "MISMATCH"),)
    print(f"idle zoo ({a.tag}): settle against what a device user would call each source")
    print(f"{'source':36} {'expected':15} {'got':13} {'ms':>6}  reason")
    for label, expect, got, ms, reason, ok in rows:
        flag = "" if ok is True else f"[{ok}] "
        print(f"{label:36} {expect:15} {got:13} {ms:>6}  {flag}{reason}")
    sys.stdout.flush()
    ctl("restart apksim://idle-zoo")
    for c in fixed:
        print(f"FIXED: {c} passes now; remove it from {os.path.basename(a.known)}", file=sys.stderr)
    for c, e, g, r in mismatches:
        print(f"MISMATCH: {c}: expected {e}, got {g}: {r}", file=sys.stderr)
    if mismatches or fixed:
        print(f"idle zoo check FAILED ({len(mismatches)} mismatches, {len(fixed)} fixed)", file=sys.stderr)
        sys.exit(1)
    print(f"idle zoo check passed ({len([r for r in rows if r[5] == 'known'])} known failures)")


if __name__ == "__main__":
    main()
