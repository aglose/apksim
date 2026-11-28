#!/usr/bin/env python3
"""Runs N instances of an APK at once and drives each through the same script, as N simulators running UI automation.

Usage: fleet.py N APK [--keep] [--log DIR] [-- APKSIM_OPTION...] -- CMD [CMD...]

Starts N headless instances (`apksim --headless --control auto`; cold launches, no standby), reads the port each one
bound from its own log (DIR, default work/fleet, then a folder of this run's own, so no command reaches a process this
run did not start), waits until each answers `ping`, then sends every instance the commands in order, all instances at
once, and quits them (unless --keep): an instance whose process then exits with a nonzero status, or does not exit
within 30 s, fails.
Commands are drive.py's control commands (tap, swipe, type, key, back, nodes, shot, ...) plus:
  wait MS         sleep
  expect TEXT     the last command's reply must contain TEXT (e.g. after `nodes`), else the instance fails here
  until MS TEXT   repeat `nodes` until a reply received within MS milliseconds contains TEXT, else fail
  quit            ends the instance; it fails unless the process then exits with status 0
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
import os, re, socket, subprocess, sys, threading, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(sys.argv[0])))


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


def end(proc, grace=10):
    """Ends a child that would not quit: SIGTERM, then SIGKILL after [grace] seconds, and reaps it either way."""
    proc.terminate()
    try:
        proc.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


class Closed(Exception):
    """The instance closed its control connection: it exited or crashed."""


class Instance:
    def __init__(self, i, proc, log):
        self.i, self.proc, self.log = i, proc, log
        self.port = self.sock = self.file = None
        self.ready = self.script = None
        self.failure = None
        self.quit = False

    def find_port(self, deadline):
        """The port this instance bound, as it printed it (`--control auto`), or None if it exited or never did."""
        while time.time() < deadline and self.proc.poll() is None:
            try:
                with open(self.log, errors="replace") as f:
                    m = re.search(r"\[apksim\] control port (\d+)", f.read())
                if m:
                    return int(m[1])
            except OSError:
                pass
            time.sleep(0.1)
        return None

    def connect(self, deadline):
        self.port = self.find_port(deadline)
        if self.port is None:
            return False
        while time.time() < deadline and self.proc.poll() is None:
            try:
                self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                self.sock.settimeout(60)
                self.file = self.sock.makefile("rw")
                if self.send("ping"):
                    return True
            except (OSError, Closed):
                time.sleep(0.1)
        return False

    def send(self, cmd):
        self.file.write(cmd + "\n")
        self.file.flush()
        reply = self.file.readline()
        if not reply:  # EOF: no reply is not an empty one
            raise Closed(f"the instance closed its control connection after {cmd!r}")
        return reply.strip()

    def stop(self):
        """`quit`: the instance may close the connection before answering; either way its process must exit with 0."""
        self.quit = True
        try:
            self.send("quit")
        except (OSError, Closed):
            pass
        try:
            code = self.proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            end(self.proc)
            return "'quit': the process did not exit within 30 s"
        return None if code == 0 else f"'quit': the process exited with status {code}"

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
            elif word == "quit":
                return self.stop()
            elif word == "until":
                ms, _, text = rest.partition(" ")
                deadline = time.time() + int(ms) / 1000
                while True:
                    # A match counts only if its reply came by the deadline: the instance may answer a request sent in
                    # time with a screen from after it (its UI reads queue behind the main thread's work).
                    last = self.send("nodes")
                    if text in last and time.time() <= deadline:
                        break
                    if time.time() > deadline:
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
    keep = "--keep" in head
    base = head[head.index("--log") + 1] if "--log" in head else os.path.join(ROOT, "work", "fleet")
    # A folder of this run's own: two fleets at once must not read each other's logs for their instances' ports.
    logdir = os.path.join(base, f"{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}")
    os.makedirs(logdir)

    t0 = time.time()
    procs, insts = [], []
    for i in range(n):
        path = os.path.join(logdir, f"instance-{i}.log")
        with open(path, "w") as log:
            # Cold launches (APKSIM_STANDBY=0): a warm one runs in a standby JVM outside this process tree (its memory
            # would not be counted), and every instance would boot another standby beside it.
            procs.append(subprocess.Popen([ROOT + "/apksim", "--headless", "--control", "auto", *opts, apk],
                                          stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                          env=dict(os.environ, APKSIM_STANDBY="0")))
        insts.append(Instance(i, procs[-1], path))
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
            code = inst.proc.poll()
            inst.failure = (f"exited with status {code} before answering ping" if code is not None
                            else "did not answer ping within 180 s") + f" (see {inst.log})"
            return
        inst.ready = time.time() - t0
        start = time.time()
        try:
            inst.failure = inst.run(rest)
        except (OSError, ValueError, Closed) as e:
            code = inst.proc.poll()
            inst.failure = f"lost its control connection: {e}" + (f" (exit status {code})" if code is not None else "")
        inst.script = time.time() - start

    threads = [threading.Thread(target=drive, args=(inst,)) for inst in insts]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    stop.set()
    sampler.join()
    total = time.time() - t0

    def shut(inst):
        """Quits an instance the script left running; its exit counts towards the result, as an explicit `quit`."""
        if inst.quit:
            return
        if inst.file is None:  # never connected: it has failed already
            if inst.proc.poll() is None:
                end(inst.proc)
            return
        if inst.proc.poll() is None:
            why = inst.stop()
            why = why and why.replace("'quit'", "shutting down")
        else:  # it ended by itself after its script (a crash during a final `wait`, say)
            code = inst.proc.returncode
            why = None if code == 0 else f"the process exited with status {code} before shutdown"
        if why and not inst.failure:
            inst.failure = why

    if not keep:
        stoppers = [threading.Thread(target=shut, args=(inst,)) for inst in insts]
        for t in stoppers:
            t.start()
        for t in stoppers:
            t.join()
    else:  # left running, but one that has already ended with an error still fails (a crash during a final `wait`)
        for inst in insts:
            if inst.file is None and inst.proc.poll() is None:
                end(inst.proc)  # never answered: it has failed, and nothing could reach it to use it later
                continue
            code = inst.proc.poll()
            if code not in (None, 0) and not inst.quit and not inst.failure:
                inst.failure = f"the process exited with status {code} after its script"

    for inst in insts:
        ready = f"ready {inst.ready:5.1f} s" if inst.ready is not None else "never ready"
        script = f"script {inst.script:5.1f} s" if inst.script is not None else ""
        print(f"instance {inst.i:3} port {inst.port or '-':>5}: {ready}  {script}  {'FAILED: ' + inst.failure if inst.failure else 'ok'}")
    failed = sum(1 for inst in insts if inst.failure)
    print(f"{n} instances, {n - failed} passed, {failed} failed in {total:.1f} s; "
          f"peak memory {peak[0]:.0f} MB together, {peak[0] / n:.0f} MB each")
    print(f"logs in {logdir}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":  # scripts/scene.py imports Instance
    main()
