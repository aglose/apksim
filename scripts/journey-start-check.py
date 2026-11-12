#!/usr/bin/env python3
"""Checks how scripts/journey.py starts a replay and plays steps (docs/replay.md, "Recording to journey"), against a
stand-in apksim.

Usage: journey-start-check.py. No apksim, APK or JVM needed: journey.py runs from a folder of its own whose `apksim`
is this script (`--fake`), which plays `apksim replay ... --control auto`: it prints its control port, answers `ping`,
`replay`, `back`, `settle` and `quit`, and notes every command (with its time) and a SIGTERM in CHECK_DIR. Scenarios:

  stuck   `replay` answers "replay: starting" for ever: journey.py exits 2 after --start-timeout, says why, runs no step,
          writes no results and ends the app (before, it polled for two minutes, then ran the steps against it anyway)
  silent  `replay` is never answered: the same, by --start-timeout (not the control channel's 90 s read timeout)
  starts  `replay` answers "starting" twice, then its result: journey.py runs the step and passes (exit 0)
  steps   a long press is a finger down and up as long apart as recorded (not a timed `swipe`, which holds 120 ms more),
          a double tap (`times`) its four touches at their recorded times with no settle between, and a `type` step's
          wait for its input counts the Host's 7 ms per character (the stand-in says "input queued" until then)
"""
import base64, json, os, shutil, signal, socket, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(sys.argv[0]))
problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)
    return ok


def fake():
    state = os.environ["CHECK_DIR"]
    note = lambda line: open(os.path.join(state, "commands.txt"), "a").write(line + "\n")
    signal.signal(signal.SIGTERM, lambda *_: (note("SIGTERM"), os._exit(143)))
    open(os.path.join(state, "pid"), "w").write(str(os.getpid()))
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(4)
    print(f"[apksim] control port {srv.getsockname()[1]}", file=sys.stderr, flush=True)
    asked = 0
    typed_until = 0
    while True:
        conn, _ = srv.accept()
        f = conn.makefile("rw")
        for line in f:
            cmd = line.strip()
            note(cmd)
            open(os.path.join(state, "timed.txt"), "a").write(f"{time.time():.3f} {cmd}\n")
            if cmd.startswith("type64 "):
                typed_until = time.time() + 0.007 * len(base64.b64decode(cmd.split()[1]).decode())
            if cmd == "ping":
                answer = "pong"
            elif cmd == "replay" and os.environ["CHECK_SCENARIO"] == "silent":
                continue
            elif cmd == "replay":
                asked += 1
                started = os.environ["CHECK_SCENARIO"] == "steps" or os.environ["CHECK_SCENARIO"] == "starts" and asked > 2
                answer = ("replay: matched" if started else "replay: starting") + "; 0/0 events and commands; 0 points compared"
            elif cmd.startswith("settle") and time.time() < typed_until:
                time.sleep(0.05)
                answer = "error: not settled after 5000 ms: input queued"
            elif cmd.startswith("settle"):
                answer = "settled"
            else:
                answer = "ok"
            f.write(answer + "\n")
            f.flush()
            if cmd == "quit":
                sys.exit(0)
        conn.close()


def scenario(name, root, journey, *extra):
    state = os.path.join(root, "state-" + name)
    os.makedirs(state)
    xml = os.path.join(state, "results.xml")
    env = dict(os.environ, CHECK_SCENARIO=name, CHECK_DIR=state)
    t0 = time.time()
    try:
        r = subprocess.run([sys.executable, os.path.join(root, "scripts", "journey.py"), journey, "--bundle", journey,
                            "--xml", xml, "--start-timeout", "1", *extra], env=env, capture_output=True, text=True, timeout=60)
        code, err = r.returncode, r.stderr
    except subprocess.TimeoutExpired as e:
        code, err = None, (e.stderr or b"").decode(errors="replace")
    time.sleep(0.5)  # the stand-in notes its SIGTERM
    path = os.path.join(state, "commands.txt")
    cmds = open(path).read().split("\n")[:-1] if os.path.exists(path) else []
    try:  # one journey.py left behind (a timeout here) does not outlive the check
        os.kill(int(open(os.path.join(state, "pid")).read()), signal.SIGKILL)
    except (OSError, ValueError):
        pass
    return code, err, cmds, xml, time.time() - t0


def timed(name, root):
    """The stand-in's commands as (seconds since the first, command)."""
    lines = [l.split(" ", 1) for l in open(os.path.join(root, "state-" + name, "timed.txt")).read().splitlines()]
    return [(float(t) - float(lines[0][0]), c) for t, c in lines]


def main():
    root = tempfile.mkdtemp(prefix="journey-start-check-")
    os.makedirs(os.path.join(root, "scripts"))
    # journey.py starts ROOT/apksim, ROOT being the folder above its own (not resolving links): this script stands in.
    os.symlink(os.path.join(HERE, "journey.py"), os.path.join(root, "scripts", "journey.py"))
    with open(os.path.join(root, "apksim"), "w") as f:
        f.write(f"#!/bin/sh\nexec {sys.executable!r} {os.path.abspath(sys.argv[0])!r} --fake \"$@\"\n")
    os.chmod(os.path.join(root, "apksim"), 0o755)
    journey = os.path.join(root, "journey.json")
    json.dump({"format": "apksim-journey/1", "steps": [{"n": 1, "do": "back"}]}, open(journey, "w"))

    print("== a replay that never gets past starting")
    code, err, cmds, xml, took = scenario("stuck", root, journey)
    check(code == 2, f"exit 2 ({code}, {took:.1f} s)")
    check("did not get past starting" in err, "says why: " + (err.strip().splitlines() or [""])[-1][:160])
    check("back" not in cmds and not any(c.startswith("settle") for c in cmds), f"runs no step: {sorted(set(cmds))}")
    check(not os.path.exists(xml), "writes no results")
    check("SIGTERM" in cmds, "ends the app")

    print("== a replay that stops answering")
    code, err, cmds, xml, took = scenario("silent", root, journey)
    check(code == 2 and took < 20, f"exit 2 by --start-timeout ({code}, {took:.1f} s)")
    check("did not answer while starting" in err, "says why: " + (err.strip().splitlines() or [""])[-1][:160])
    check("back" not in cmds and not os.path.exists(xml) and "SIGTERM" in cmds, f"runs no step and ends the app: {cmds}")

    print("== a replay that starts")
    code, err, cmds, xml, took = scenario("starts", root, journey)
    check(code == 0, f"exit 0 ({code}, {took:.1f} s): {err.strip()[-200:]}")
    check(cmds.count("replay") == 3 and "back" in cmds and cmds[-1] == "quit", f"waits for it, runs the step, quits: {cmds}")
    check(os.path.exists(xml) and 'failures="0"' in open(xml).read(), "results: one step, passed")

    print("== steps played with their recorded timing")
    steps = os.path.join(root, "steps.json")
    json.dump({"format": "apksim-journey/1", "steps": [
        {"n": 1, "do": "longpress", "at": [100, 200], "ms": 700},
        {"n": 2, "do": "tap", "at": [300, 400], "times": [[0, 60], [200, 50]]},
        {"n": 3, "do": "type", "text": "x" * 200},
    ]}, open(steps, "w"))
    code, err, cmds, xml, took = scenario("steps", root, steps, "--timeout", "0.5")
    check(code == 0, f"exit 0 ({code}, {took:.1f} s): {err.strip()[-200:]}")
    t = [(at, c) for at, c in timed("steps", root) if c.startswith(("touch", "tap", "swipe", "type64", "settle"))]
    press = [(at, c) for at, c in t if c.endswith(" 100 200")]
    check([c for _, c in press] == ["touch down 100 200", "touch up 100 200"] and 0.65 <= press[1][0] - press[0][0] < 0.85,
          "a long press: down, then up 700 ms later: " + str([(round(at - t[0][0], 2), c) for at, c in press]))
    i = next((k for k, (_, c) in enumerate(t) if c.endswith(" 300 400")), None)
    tap = t[i:i + 4] if i is not None else []
    check([c for _, c in tap] == ["touch down 300 400", "touch up 300 400"] * 2 and 0.15 <= tap[2][0] - tap[0][0] < 0.35,
          "a double tap: four touches, the second down 200 ms after the first, no settle between: "
          + str([(round(at - tap[0][0], 2), c[:30]) for at, c in tap]))
    check(os.path.exists(xml) and 'failures="0"' in open(xml).read() and "still being dispatched" not in open(xml).read(),
          "typing 200 characters (1.4 s of input) passes with --timeout 0.5")
    shutil.rmtree(root, ignore_errors=True)
    print()
    if problems:
        print(f"journey-start-check: {len(problems)} problem(s):")
        for p in problems:
            print("  - " + p)
        sys.exit(1)
    print("journey-start-check: all good")


if __name__ == "__main__":
    fake() if sys.argv[1:2] == ["--fake"] else main()
