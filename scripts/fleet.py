#!/usr/bin/env python3
"""Runs N instances of an APK at once and drives each through the same script, as N simulators running UI automation.

Usage: fleet.py N APK [--port BASE] [--keep] [--log DIR] [-- APKSIM_OPTION...] -- CMD [CMD...]

Starts N headless instances (`apksim --headless --control PORT`, ports BASE..BASE+N-1, default 7600; cold launches, no
standby), waits until each
answers `ping`, then sends every instance the commands in order, all instances at once, and quits them (unless --keep).
Commands are drive.py's control commands (tap, swipe, type, key, back, nodes, shot, ...) plus:
  wait MS         sleep
  expect TEXT     the last command's reply must contain TEXT (e.g. after `nodes`), else the instance fails here
  until MS TEXT   repeat `nodes` until its reply contains TEXT, at most MS milliseconds, else fail
`{i}` in a command is the instance's number (0..N-1), e.g. `shot work/fleet/{i}.png` or `type user{i}`.

Prints, per instance, how long it took to answer its first ping, how long the script took, and the first failure if
any; then the peak resident memory of all instances together and per instance. Exits 1 if any instance failed.
Each instance keeps its own app data (`instance-N` slots, see AGENTS.md), so a script that writes data runs N times
in parallel without the instances sharing it. An instance answers `ping` before its first frame, and input sent
before the UI is there is lost, so start a script by waiting for something on screen. Example (the test app: three
FAB taps, then the Trips tab):
  scripts/fleet.py 8 app-debug.apk -- 'until 30000 FAB clicks: 0' 'tap 733 1533' 'tap 733 1533' 'tap 733 1533' \\
      'until 3000 FAB clicks: 3' 'tap 350 1690' 'until 5000 vacation'
"""
import os, socket, subprocess, sys, threading, time

ROOT = os.path.dirname(os.path.abspath(sys.argv[0])) + "/.."


def rss_mb(pids):
    """Resident memory of these processes and their children (the launcher execs the JVM, so usually the same PID)."""
    out = subprocess.run(["ps", "-axo", "pid=,ppid=,rss="], capture_output=True, text=True).stdout
    procs = [tuple(int(x) for x in line.split()) for line in out.splitlines() if line.strip()]
    keep, total = set(pids), 0
    changed = True
    while changed:
        changed = False
        for pid, ppid, _ in procs:
            if ppid in keep and pid not in keep:
                keep.add(pid)
                changed = True
    for pid, _, rss in procs:
        if pid in keep:
            total += rss
    return total / 1024


class Instance:
    def __init__(self, i, port):
        self.i, self.port, self.sock, self.file = i, port, None, None
        self.ready = self.script = None
        self.failure = None

    def connect(self, deadline):
        while time.time() < deadline:
            try:
                self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                self.sock.settimeout(60)
                self.file = self.sock.makefile("rw")
                if self.send("ping"):
                    return True
            except OSError:
                time.sleep(0.1)
        return False

    def send(self, cmd):
        self.file.write(cmd + "\n")
        self.file.flush()
        return self.file.readline().strip()

    def run(self, commands):
        last = ""
        for raw in commands:
            cmd = raw.replace("{i}", str(self.i))
            word, _, rest = cmd.partition(" ")
            if word == "wait":
                time.sleep(int(rest) / 1000)
            elif word == "expect":
                if rest not in last:
                    return f"{raw!r}: not in the reply {last[:300]!r}"
            elif word == "until":
                ms, _, text = rest.partition(" ")
                end = time.time() + int(ms) / 1000
                while text not in (last := self.send("nodes")):
                    if time.time() > end:
                        return f"{raw!r}: not on screen after {ms} ms: {last[:300]!r}"
                    time.sleep(0.1)
            else:
                last = self.send(cmd)
                if last.startswith("error"):
                    return f"{raw!r}: {last}"
        return None


def main():
    argv = sys.argv[1:]
    if argv.count("--") < 1 or len(argv) < 3:
        sys.exit(__doc__)
    first = argv.index("--")
    head, rest = argv[:first], argv[first + 1:]
    opts = []
    if "--" in rest:  # fleet.py N APK -- APKSIM_OPTION... -- CMD...
        second = rest.index("--")
        opts, rest = rest[:second], rest[second + 1:]
    n, apk = int(head[0]), os.path.abspath(head[1])
    base = int(head[head.index("--port") + 1]) if "--port" in head else 7600
    keep = "--keep" in head
    logdir = head[head.index("--log") + 1] if "--log" in head else os.path.join(ROOT, "work", "fleet")
    os.makedirs(logdir, exist_ok=True)

    t0 = time.time()
    procs = []
    for i in range(n):
        log = open(os.path.join(logdir, f"instance-{i}.log"), "w")
        # Cold launches (APKSIM_STANDBY=0): a warm one runs in a standby JVM outside this process tree (its memory
        # would not be counted), and every instance would boot another standby beside it.
        procs.append(subprocess.Popen([ROOT + "/apksim", "--headless", "--control", str(base + i), *opts, apk],
                                      stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                      env=dict(os.environ, APKSIM_STANDBY="0")))
    insts = [Instance(i, base + i) for i in range(n)]
    peak = [0.0]
    stop = threading.Event()

    def sample():
        while not stop.is_set():
            peak[0] = max(peak[0], rss_mb([p.pid for p in procs]))
            stop.wait(1)

    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()

    def drive(inst):
        if not inst.connect(time.time() + 180):
            inst.failure = "did not answer ping within 180 s (see its log)"
            return
        inst.ready = time.time() - t0
        start = time.time()
        try:
            inst.failure = inst.run(rest)
        except (OSError, ValueError) as e:
            inst.failure = f"lost its control connection: {e}"
        inst.script = time.time() - start

    threads = [threading.Thread(target=drive, args=(inst,)) for inst in insts]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    stop.set()
    sampler.join()
    total = time.time() - t0

    for inst in insts:
        ready = f"ready {inst.ready:5.1f} s" if inst.ready is not None else "never ready"
        script = f"script {inst.script:5.1f} s" if inst.script is not None else ""
        print(f"instance {inst.i:3} port {inst.port}: {ready}  {script}  {'FAILED: ' + inst.failure if inst.failure else 'ok'}")
    failed = sum(1 for inst in insts if inst.failure)
    print(f"{n} instances, {n - failed} passed, {failed} failed in {total:.1f} s; "
          f"peak memory {peak[0]:.0f} MB together, {peak[0] / n:.0f} MB each")
    if not keep:
        for inst in insts:
            try:
                if inst.file:
                    inst.file.write("quit\n")
                    inst.file.flush()
            except OSError:
                pass
        for p in procs:
            try:
                p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                p.terminate()
    sys.exit(1 if failed else 0)


main()
