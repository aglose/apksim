#!/usr/bin/env python3
"""Runs a journey (docs/replay.md, "Recording to journey") against apksim over its control channel.

Usage: journey.py JOURNEY.json [--port PORT] [--apk APK] [--bundle BUNDLE] [--start checkpoint|launch]
                  [--xml FILE] [--timeout S] [--start-timeout S] [--keep] [--by-coordinates] [-- APKSIM OPTIONS]

A journey is what `apksim replay --journey BUNDLE` writes: steps that find their element by text, content
description or resource id (as `nodes` shows them), with the texts each step should bring on screen. For each step:

  tap / longpress   the node's centre (`nodes`, waited for up to --timeout), else the recorded point when the
                    recording found no node there;
  swipe, wheel, rotary, type, key, back, command   as the control commands of those names;
  then `settle`, and each expected text waited for (up to --timeout seconds, default 10).

Without --port it starts the app itself: from the recording's checkpoint (`apksim replay --input none --compare none`,
the journey's bundle or --bundle; --apk for another build) or, with --start launch, a fresh launch of --apk. APKSIM
OPTIONS go to that launch (`--device tablet`, `--feature X`...). It quits the app at the end unless --keep. A replay
still starting after --start-timeout seconds (default 120) is a start that failed: exit 2, no step run.

--by-coordinates taps the recorded points instead of the nodes (what a plain replay does), to compare.

Writes JUnit XML (--xml, default journey-results.xml beside the journey): one test case per step, a failure for the
step that failed and the rest skipped. Exit 0 when every step passed, 1 when one failed, 2 when it could not start.
"""
import argparse, base64, json, os, re, socket, subprocess, sys, time
from xml.sax.saxutils import escape, quoteattr

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")
# One `nodes` line: bounds, then flags (clickable, field...) and attributes whose values escape \\, \" and \n
# (Nodes.quote), then "; ". A value may hold "; " itself, so the line is read token by token, not split.
NODE = re.compile(r'\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\] ((?:[\w-]+ |\w+="(?:[^"\\]|\\.)*" )*); ?')
ATTR = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def unquote(v):
    return re.sub(r'\\(.)', lambda m: {"n": "\n", "r": "\r"}.get(m.group(1), m.group(1)), v)


class Control:
    def __init__(self, port, deadline=120, proc=None, log=None):
        end = time.time() + deadline
        while True:
            try:
                self.s = socket.create_connection(("127.0.0.1", port), timeout=2)
                self.s.settimeout(90)
                self.f = self.s.makefile("rw")
                if self("ping") == "pong":
                    return
            except OSError:
                pass
            if time.time() > end or (proc is not None and proc.poll() is not None):
                raise SystemExit(2 if proc is None else _died(proc, log))
            time.sleep(0.2)

    def __call__(self, cmd):
        self.f.write(cmd.replace("\n", " ") + "\n")
        self.f.flush()
        line = self.f.readline()
        if not line:  # the connection closed: the app is gone, which fails the step (never a silent pass)
            raise ConnectionError("the app closed its control connection")
        return line.strip()


def die(msg):
    """A journey that cannot start: one line on stderr, exit 2 (a failed step is 1)."""
    print(msg, file=sys.stderr)
    sys.exit(2)


def _died(proc, log):
    print("journey: the app did not come up" + (": " + open(log).read()[-2000:] if log else ""), file=sys.stderr)
    return 2


def nodes(c):
    """Every node `nodes` lists: bounds and attributes (text, desc, id, hint, flags)."""
    out = []
    for m in NODE.finditer(c("nodes") + " "):
        x1, y1, x2, y2 = map(int, m.groups()[:4])
        attrs = {k: unquote(v) for k, v in ATTR.findall(m.group(5))}
        flags = set(re.findall(r'(?:^| )([\w-]+)(?= )', ATTR.sub("", m.group(5))))
        out.append({"bounds": (x1, y1, x2, y2), "flags": flags, **attrs})
    return out


def screen(c):
    """The app's screen as (left, top, right, bottom), from `config`'s window bounds, or None."""
    m = re.search(r"mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", c("config"))
    return tuple(map(int, m.groups())) if m else None


def find(c, target, timeout):
    """The centre of the visible part of the node the target names (its index-th match, counted over all its nodes
    as the generator counts), waited for; or None."""
    kind = next(k for k in ("text", "desc", "id") if k in target)
    want, index = target[kind], target.get("index", 0)
    end = time.time() + timeout
    while True:
        hits = [n for n in nodes(c) if n.get(kind) == want and n["bounds"][2] > n["bounds"][0] and n["bounds"][3] > n["bounds"][1]]
        # Every match counts toward the index (as the generator counts), but the target must be on screen: a node marked
        # offscreen (clipped by a parent, scrolled away) is waited for like one that is not there.
        if len(hits) > index and "offscreen" not in hits[index]["flags"]:
            x1, y1, x2, y2 = hits[index]["bounds"]
            # A node scrolled partly off screen has bounds outside it: its visible part's centre is what a finger hits.
            s = screen(c)
            if s:
                vx1, vy1, vx2, vy2 = max(x1, s[0]), max(y1, s[1]), min(x2, s[2]), min(y2, s[3])
                if vx2 > vx1 and vy2 > vy1:
                    return (vx1 + vx2) // 2, (vy1 + vy2) // 2
                # Wholly off screen: not tappable yet. Waited for like a node that is not there (then the step fails).
            else:
                return (x1 + x2) // 2, (y1 + y2) // 2
        if time.time() > end:
            return None
        time.sleep(0.25)


def describe(step):
    d = step["do"]
    if d in ("tap", "longpress"):
        t = step.get("target")
        if t:
            k = next(k for k in ("text", "desc", "id") if k in t)
            return f"{d} {k}={t[k]!r}" + (f" #{t['index']}" if "index" in t else "")
        return f"{d} at {step['at'][0]},{step['at'][1]}"
    if d == "swipe":
        return f"swipe {step['from']} -> {step['to']}"
    if d == "type":
        return f"type {step['text']!r}"
    if d == "key":
        return "key " + step["key"] + ("+" + "+".join(step["mods"]) if step.get("mods") else "")
    if d == "command":
        return " ".join(step["command"])
    return d


def run_step(c, step, timeout, by_coordinates=False):
    """Does the step; returns None, or why it failed."""
    d = step["do"]
    if d in ("tap", "longpress"):
        at = None
        if step.get("target") and not by_coordinates:
            at = find(c, step["target"], timeout)
            if at is None:
                k = next(k for k in ("text", "desc", "id") if k in step["target"])
                return f"no node with {k}={step['target'][k]!r} on screen after {timeout} s"
        else:
            at = tuple(step["at"])
        r = c(f"tap {at[0]} {at[1]}") if d == "tap" else c(f"swipe {at[0]} {at[1]} {at[0]} {at[1]} {step.get('ms', 600)}")
    elif d == "swipe" and step.get("path"):
        # The recorded path, touch by touch at its recorded times: the release speed, so whether and how far it flings,
        # is the recording's.
        path = step["path"]
        t0 = time.time()
        r = c("touch down {} {}".format(path[0][1], path[0][2]))
        for ms, x, y in path[1:-1]:
            delay = t0 + ms / 1000 - time.time()
            if delay > 0:
                time.sleep(delay)
            r = c(f"touch move {x} {y}")
        delay = t0 + path[-1][0] / 1000 - time.time()
        if delay > 0:
            time.sleep(delay)
        r = c("touch up {} {}".format(path[-1][1], path[-1][2])) if not r.startswith("error") else r
    elif d == "swipe":
        # An older journey (no path): a fling as an untimed swipe, else a timed drag.
        if step.get("fling", step["ms"] < 250):
            # A fling (its speed at lifting, as recorded; older journeys: under 250 ms): queued moves that lift at once,
            # so it flings as it did; a timed drag would hold its end point and stop dead.
            r = c("swipe {} {} {} {}".format(*step["from"], *step["to"]))
        else:
            r = c("swipe {} {} {} {} {}".format(*step["from"], *step["to"], step["ms"]))
    elif d == "type":
        # As Base64: the control channel splits a command on whitespace, which would change "a  b" or a leading space.
        r = c("type64 " + base64.b64encode(step["text"].encode()).decode())
    elif d == "key":
        r = c(f"key {step['key']} 1" + (" " + " ".join(step["mods"]) if step.get("mods") else ""))
    elif d == "back":
        r = c("back")
    elif d == "wheel":
        r = c("wheel {} {} {}".format(*step["at"], step["amount"]))
    elif d == "rotary":
        r = c(f"rotary {step['amount']}")
    elif d == "command":
        r = c(" ".join(step["command"]))
    else:
        return f"unknown step {d!r}"
    if r.startswith(("error", "not found", "usage", "unknown")):  # "unknown command: X", "unknown key: X"
        return f"{describe(step)}: {r}"
    # Settle: an endless animation answers with an error after 5 s, and the expectations below wait anyway. Input still
    # being dispatched (a long press's hold, typed characters) is not that: the step waits for it to be done, as long
    # as the step itself takes plus --timeout, and fails if it never is.
    end = time.time() + step.get("ms", 0) / 1000 + timeout
    while True:
        r = c("settle 5000 200")
        if "input queued" not in r:
            break
        if time.time() > end:
            return f"{describe(step)}: its input was still being dispatched after {step.get('ms', 0) / 1000 + timeout:g} s"
    for text in step.get("expect", []):
        end = time.time() + timeout
        # On screen: a node scrolled away is still in the tree (nodes marks it offscreen) and does not count.
        while not any(n.get("text") == text and "offscreen" not in n["flags"] for n in nodes(c)):
            if time.time() > end:
                shown = [n["text"] for n in nodes(c) if "text" in n and "offscreen" not in n["flags"]][:12]
                return f"expected {text!r} on screen after {timeout} s; it shows {shown}"
            time.sleep(0.25)
    return None


def start(a, journey):
    os.makedirs(os.path.dirname(os.path.abspath(a.xml)), exist_ok=True)
    # One log per run (its XML's name): two journeys writing results to one folder must not read each other's port.
    log = os.path.abspath(a.xml) + ".app.log"
    if a.start == "launch":
        if not a.apk:
            die("journey: --start launch needs --apk")
        # Slots of its own (journey-0, -1...): --fresh empties the slot it takes, and must never take a normal
        # launch's `default` and erase the app's data, /sdcard and settings there.
        # --no-autofill: never the owner's saved sign-ins in a test (a checkpoint start gets it from apksim replay).
        cmd = [os.path.join(ROOT, "apksim"), "--headless", "--fresh", "--no-autofill", "--data-slots", "journey", "--control", "auto",
               *a.apksim, a.apk]
    else:
        bundle = a.bundle or journey.get("bundle")
        if not bundle or not os.path.isfile(bundle):
            die(f"journey: no recording to start from ({bundle}); pass --bundle, or --start launch --apk")
        cmd = [os.path.join(ROOT, "apksim"), "replay", "--input", "none", "--compare", "none", "--headless", "--control", "auto"]
        if a.apk:
            cmd += ["--apk", a.apk]
        cmd += [*a.apksim, bundle]
    p = subprocess.Popen(cmd, stdout=open(log, "w"), stderr=subprocess.STDOUT,
                         env=dict(os.environ, APKSIM_STANDBY=os.environ.get("APKSIM_STANDBY", "0")))
    try:
        port = None
        for _ in range(1500):
            with open(log, errors="replace") as f:
                m = re.search(r"\[apksim\] control port (\d+)", f.read())
            if m:
                port = int(m.group(1))
                break
            if p.poll() is not None:
                break
            time.sleep(0.1)
        if port is None:
            sys.exit(_died(p, log))
        c = Control(port, proc=p, log=log)
        if a.start == "launch":
            c("settle 20000")
        else:
            # The replay puts the checkpoint back before it hands the app over. One that never gets that far cannot
            # run the journey: that is a start that failed (exit 2), not a first step that did.
            end = time.time() + a.start_timeout
            while re.match(r"replay: (starting|running)", state := c("replay")):
                if time.time() > end:
                    die(f"journey: the replay did not get past starting in {a.start_timeout:g} s ({state}); its log: {log}")
                time.sleep(0.2)
        return p, c
    except BaseException:
        # Not started: the app's JVM does not outlive the runner (it would keep its data slot), unless --keep.
        if not a.keep and p.poll() is None:
            p.terminate()
            try:
                p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                p.kill()
        raise


def main():
    args, apksim = sys.argv[1:], []
    if "--" in args:
        i = args.index("--")
        args, apksim = args[:i], args[i + 1:]
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("journey")
    ap.add_argument("--port", type=int)
    ap.add_argument("--apk")
    ap.add_argument("--bundle")
    ap.add_argument("--start", choices=["checkpoint", "launch"], default="checkpoint")
    ap.add_argument("--xml")
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("--start-timeout", type=float, default=120)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--by-coordinates", action="store_true")
    a = ap.parse_args(args)
    a.apksim = apksim
    journey = json.load(open(a.journey))
    if journey.get("format") != "apksim-journey/1":
        die("journey: not an apksim journey: " + a.journey)
    a.xml = a.xml or os.path.join(os.path.dirname(os.path.abspath(a.journey)), "journey-results.xml")
    if a.apk:
        a.apk = os.path.abspath(a.apk)
    proc = None
    if a.port:
        c = Control(a.port)
    else:
        proc, c = start(a, journey)
    name = os.path.splitext(os.path.basename(a.journey))[0]
    cases, failed = [], None
    t_all = time.time()
    try:
        for step in journey["steps"]:
            label = f"{step['n']:02d} {describe(step)}"
            if failed:
                cases.append((label, 0, None, True))
                continue
            t0 = time.time()
            try:
                why = run_step(c, step, a.timeout, a.by_coordinates)
            except OSError as e:
                why = f"the app stopped answering: {e}"
            took = time.time() - t0
            cases.append((label, took, why, False))
            print(f"  {'ok  ' if why is None else 'FAIL'}  {label}  ({took:.1f} s)" + ("" if why is None else f"\n        {why}"))
            if why:
                failed = label
    finally:
        if proc is not None and not a.keep:
            try:
                c("quit")
            except OSError:
                pass
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
    total = time.time() - t_all
    fails = sum(1 for x in cases if x[2])
    skips = sum(1 for x in cases if x[3])
    xml = [f'<?xml version="1.0" encoding="UTF-8"?>',
           f'<testsuite name={quoteattr(name)} tests="{len(cases)}" failures="{fails}" skipped="{skips}" time="{total:.3f}">']
    for label, took, why, skipped in cases:
        xml.append(f'  <testcase classname={quoteattr("journey." + name)} name={quoteattr(label)} time="{took:.3f}">')
        if why:
            xml.append(f'    <failure message={quoteattr(why)}>{escape(why)}</failure>')
        if skipped:
            xml.append(f'    <skipped message={quoteattr("after " + failed)}/>')
        xml.append("  </testcase>")
    xml.append("</testsuite>")
    os.makedirs(os.path.dirname(os.path.abspath(a.xml)), exist_ok=True)
    open(a.xml, "w").write("\n".join(xml) + "\n")
    print(f"journey: {len(cases) - fails - skips} of {len(cases)} steps passed" + (f"; failed at {failed}" if failed else "") + f"; {a.xml}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
