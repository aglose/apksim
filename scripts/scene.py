#!/usr/bin/env python3
"""Runs a scene: several linked instances with roles, driven step by step, waiting on one another (docs/scenes.md).

Usage: scene.py SCENE.json [--out DIR] [--keep] [--warm] [--var NAME=VALUE]... [--apk [ROLE=]APK]... [-- APKSIM_OPTION...]

A scene file names roles (an APK, a device, launch options each) and steps. Every role is a headless instance
(`apksim --headless --control auto --data-slots scene --fresh --device D ...`, started together, cold unless --warm),
run with DIR as its working directory (default work/scenes/NAME-TIME), so `shot x.png` lands there; its log is
DIR/ROLE.log. An optional fixture (a server every role shares, e.g. scripts/network-fixture.py) starts first, and the
port it prints is {fixture} in the launch options, so one compiled-in backend address can point every instance at it
(`--network-host api.apksim.test=127.0.0.1:{fixture}`): two users of one backend in one script.

Steps run in order. A step is an object mapping roles to a command or a list of commands; roles in one step run at
the same time and the step ends when all have. "all" sends the commands to every role, "name" labels the step.
Commands are the control commands (tap, swipe, type, open URL, push ..., settle, shot ..., ...: an answer that says it
failed fails the step: `error ...`, `unknown command`, `usage:`, tap-text's `not found:`, open's `no activity ...`,
key's `unknown key:`, push's `bad JSON:` and `the app has no FCM receiver`) plus:
  wait MS               sleep
  until MS TEXT         `nodes` until TEXT is on screen (an answer received within MS), else fail; `nodes` lists
                        the windows accessibility reports, so not text a dialog covers, as tap-text sees them
  gone MS TEXT          `nodes` until TEXT is not on screen
  expect TEXT           the last answer must contain TEXT
  capture NAME REGEX    the first group of REGEX in the last answer (else in `nodes`) becomes {NAME}
  to ROLE CMD           CMD goes to ROLE's instance (e.g. `to watch open apksim://x?code={code}`, `to bob push k=v`)
  quit                  ends the role's instance, which must exit with status 0
{NAME} is replaced in commands by captured values, --var values, {fixture}, {out}, {role} (this role's name) and
{port:ROLE} (ROLE's control port). --apk APK replaces every role's APK, --apk ROLE=APK one role's (CI runs a
scene file against the APK it built). APKSIM_OPTIONs after `--` go to every role. Every role still running at the end is quit and must exit 0; --keep leaves the roles and the fixture running.

On a failure every running role's screen is saved as DIR/failed-ROLE.png. Writes DIR/result.json (steps, times,
failure) and prints a line per step. Exits 1 on a failure, 2 on a bad scene file.
"""
import json, os, re, shutil, subprocess, sys, tempfile, threading, time

SCRIPTS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPTS)
from fleet import Closed, Instance, end, rss_mb  # noqa: E402

ROOT = os.path.dirname(SCRIPTS)


def apksim_launcher():
    """The checkout's launcher, or (in the Linux image, where scripts are on the PATH) the installed one."""
    if os.environ.get("APKSIM"):
        return os.environ["APKSIM"]
    own = os.path.join(ROOT, "apksim")
    return own if os.path.exists(own) else (shutil.which("apksim") or own)


class Failure(Exception):
    pass


# Answers that mean a command did not do what it was asked: `error ...` (most commands), and the few that answer in
# their own words (tap-text's target missing, open with no activity for the URL, key's unknown name, push's bad JSON
# or missing FCM receiver, bad arguments).
FAILED_ANSWER = re.compile(r"^(error|unknown command|unknown key:|usage:|not found:|no activity( in |$)|no window$"
                           r"|bad JSON:|the app has no FCM receiver)")


def failed(answer):
    return bool(FAILED_ANSWER.match(answer))


class Role:
    def __init__(self, name, spec, scene_dir):
        self.name = name
        apk = spec.get("apk")
        if not apk:
            raise ValueError(f"role {name} has no apk")
        self.apk = apk if os.path.isabs(apk) else os.path.normpath(os.path.join(scene_dir, apk))
        self.device = spec.get("device", "phone")
        self.options = [str(o) for o in spec.get("options", [])]
        self.inst = None
        self.lock = threading.Lock()  # one command at a time on its connection (`to ROLE` from another role's thread)
        self.last = ""
        self.ready = None

    def send(self, cmd):
        with self.lock:
            return self.inst.send(cmd)


class Scene:
    def __init__(self, path, out, cli_vars, extra, warm, apks=None):
        self.path = path
        with open(path) as f:
            self.spec = json.load(f)
        self.dir = os.path.dirname(os.path.abspath(path))
        self.name = self.spec.get("name") or os.path.splitext(os.path.basename(path))[0]
        self.out = out or os.path.join(ROOT, "work", "scenes", f"{self.name}-{time.strftime('%Y%m%d-%H%M%S')}-{os.getpid()}")
        os.makedirs(self.out, exist_ok=True)
        self.out = os.path.abspath(self.out)
        self.vars = {"out": self.out, "scripts": SCRIPTS, "scene": self.dir, **cli_vars}
        self.vars_lock = threading.Lock()
        self.extra = extra
        self.warm = warm
        roles = self.spec.get("roles") or {}
        if not roles:
            raise ValueError("the scene has no roles")
        self.roles = {n: Role(n, r, self.dir) for n, r in roles.items()}
        for name, apk in (apks or {}).items():
            for role in self.roles.values() if name is None else [self.roles[name]]:
                role.apk = os.path.abspath(apk)
        self.fixture = None
        # Captures are written here first and then moved into the output folder: on the Mac an instance's sandbox may
        # write only its state folder (and not elsewhere under $HOME, where --out may point); a temporary folder is
        # outside $HOME.
        self.stage = tempfile.mkdtemp(prefix="apksim-scene-")
        self.capture_failures = []
        self.t0 = time.time()

    def expand(self, text, role=None):
        def sub(m):
            key = m.group(1)
            if key == "role" and role is not None:
                return role.name
            if key.startswith("port:"):
                r = self.roles.get(key[5:])
                if r is None or r.inst is None or r.inst.port is None:
                    raise Failure(f"{{{key}}}: no such running role")
                return str(r.inst.port)
            with self.vars_lock:
                if key in self.vars:
                    return str(self.vars[key])
            raise Failure(f"{{{key}}} is not set")
        return re.sub(r"\{([A-Za-z_][\w:.-]*)\}", sub, text)

    # ---- fixture and launches ----

    def start_fixture(self):
        fx = self.spec.get("fixture")
        if not fx:
            return
        if not isinstance(fx, dict) or not fx.get("command"):
            raise ValueError("the fixture has no command")
        cmd = [self.expand(c) for c in fx["command"]]
        log_path = os.path.join(self.out, "fixture.log")
        # Its output goes straight to fixture.log, which is watched for the ready line: nothing is held in memory, a
        # chatty fixture never blocks on a pipe, and one left running with --keep outlives this runner.
        with open(log_path, "w") as log:
            self.fixture = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                            cwd=self.out, env=dict(os.environ, PYTHONUNBUFFERED="1"))
        pattern = re.compile(fx.get("ready", r"ready (\d+)"))
        timeout = float(fx.get("timeout", 30))
        deadline = time.time() + timeout
        seen, offset = "", 0
        while True:
            with open(log_path, "rb") as f:  # only what was appended since the last look
                f.seek(offset)
                chunk = f.read()
            offset += len(chunk)
            seen = (seen + chunk.decode(errors="replace"))[-65536:]
            m = pattern.search(seen)
            if m:
                self.vars["fixture"] = m.group(1) if m.groups() else ""
                print(f"fixture ready{' on port ' + self.vars['fixture'] if self.vars['fixture'] else ''}")
                return
            if self.fixture.poll() is not None:
                raise Failure(f"the fixture exited with status {self.fixture.returncode} before printing {pattern.pattern!r}"
                              f" (see {log_path})")
            if time.time() > deadline:
                raise Failure(f"the fixture did not print {pattern.pattern!r} within {timeout:g} s (see {log_path})")
            time.sleep(0.05)

    def launch(self):
        env = dict(os.environ)
        if not self.warm:
            env["APKSIM_STANDBY"] = "0"
        for role in self.roles.values():
            # --no-autofill: a role never signs in with the owner's saved sign-in details (Autofill), whatever its APK.
            args = [apksim_launcher(), "--headless", "--control", "auto", "--data-slots", "scene", "--fresh", "--no-autofill",
                    "--device", role.device, *[self.expand(o, role) for o in self.spec.get("options", []) + role.options], *self.extra, role.apk]
            log_path = os.path.join(self.out, f"{role.name}.log")
            with open(log_path, "w") as log:
                log.write(" ".join(args) + "\n")
                log.flush()
                proc = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, env=env,
                                        cwd=self.out)
            role.inst = Instance(role.name, proc, log_path)

        def connect(role):
            if role.inst.connect(time.time() + 180):
                role.ready = time.time() - self.t0
        threads = [threading.Thread(target=connect, args=(r,)) for r in self.roles.values()]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        for role in self.roles.values():
            if role.ready is None:
                code = role.inst.proc.poll()
                raise Failure(f"{role.name} " + (f"exited with status {code}" if code is not None else "did not answer ping")
                              + f" (see {role.inst.log})")
            print(f"{role.name:>10}: {role.device}, port {role.inst.port}, ready in {role.ready:.1f} s")

    # ---- commands ----

    def nodes(self, role):
        # `nodes`: the windows accessibility reports, so not what a dialog covers, as tap-text and a user see them.
        return role.send("nodes")

    def run_command(self, role, raw):
        cmd = self.expand(raw, role)
        word, _, rest = cmd.partition(" ")
        if word == "wait":
            time.sleep(int(rest) / 1000)
        elif word in ("until", "gone"):
            ms, _, text = rest.partition(" ")
            deadline = time.time() + int(ms) / 1000
            while True:
                # A reply counts only if it came by the deadline (UI reads queue behind the main thread's work).
                role.last = self.nodes(role)
                if failed(role.last):
                    raise Failure(f"nodes: {role.last}")
                present = text in role.last
                if present == (word == "until") and time.time() <= deadline:
                    return
                if time.time() > deadline:
                    raise Failure(f"{text!r} {'not on screen' if word == 'until' else 'still on screen'} after {ms} ms: "
                                  f"{role.last[:400]!r}")
                time.sleep(0.1)
        elif word == "expect":
            if rest not in role.last:
                raise Failure(f"{rest!r} not in the last answer {role.last[:400]!r}")
        elif word == "capture":
            name, _, pattern = rest.partition(" ")
            try:
                rx = re.compile(pattern)
            except re.error as e:
                raise Failure(f"capture {name}: bad regular expression {pattern!r}: {e}")
            m = rx.search(role.last) or rx.search(self.nodes(role))
            if not m:
                raise Failure(f"capture {name}: {pattern!r} matched nothing")
            with self.vars_lock:
                self.vars[name] = m.group(1) if m.groups() else m.group(0)
        elif word == "to":
            target, _, inner = rest.partition(" ")
            other = self.roles.get(target)
            if other is None:
                raise Failure(f"to {target}: no such role")
            reply = other.send(inner)
            if failed(reply):
                raise Failure(f"to {target} {inner!r}: {reply}")
            role.last = reply
        elif word in ("shot", "wshot") and rest:
            role.last = self.capture(role, word, rest)
            if failed(role.last):
                raise Failure(role.last)
        elif word == "quit":
            with role.lock:  # not while another role's `to` reads this connection
                why = role.inst.stop()
            if why:
                raise Failure(why)
        else:
            with role.lock:
                role.last = role.inst.send(cmd)
            if failed(role.last):
                raise Failure(role.last)

    def run_step(self, n, step):
        label = step.get("name", "")
        work = {}
        for key, cmds in step.items():
            if key == "name":
                continue
            targets = list(self.roles) if key == "all" else [key]
            for t in targets:
                if t not in self.roles:
                    raise ValueError(f"step {n}: no role {t!r}")
                work.setdefault(t, []).extend([cmds] if isinstance(cmds, str) else list(cmds))
        errors = {}
        errors_lock = threading.Lock()

        def go(name, cmds):
            role = self.roles[name]
            for c in cmds:
                try:
                    self.run_command(role, c)
                except Failure as e:
                    with errors_lock:
                        errors[name] = f"{c!r}: {e}"
                    return
                except (OSError, ValueError, Closed) as e:
                    code = role.inst.proc.poll()
                    with errors_lock:
                        errors[name] = f"{c!r}: lost its control connection: {e}" + (f" (exit status {code})" if code is not None else "")
                    return
        start = time.time()
        threads = [threading.Thread(target=go, args=(name, cmds)) for name, cmds in work.items()]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        took = time.time() - start
        summary = "; ".join(f"{r}: {', '.join(c)}" for r, c in work.items())
        print(f"step {n:>2} {took:6.2f} s  {'FAILED ' if errors else 'ok     '}{label + ' - ' if label else ''}{summary[:160]}")
        return took, errors

    def capture(self, role, word, target):
        """`shot`/`wshot` to TARGET (relative to the output folder), staged where the instance may write. The answer."""
        dest = target if os.path.isabs(target) else os.path.join(self.out, target)
        staged = os.path.join(self.stage, f"{role.name}-{time.time_ns()}-{os.path.basename(target)}")
        with role.lock:
            reply = role.inst.send(f"{word} {staged}")
        if not failed(reply):
            if not os.path.exists(staged):
                return f"error: {word} answered {reply!r} but wrote nothing"
            os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
            shutil.move(staged, dest)
        return reply

    def shots(self):
        """Every running role's screen after a failure; a capture that fails is reported, not dropped."""
        for role in self.roles.values():
            if role.inst and role.inst.file and role.inst.proc.poll() is None and not role.inst.quit:
                try:
                    reply = self.capture(role, "shot", f"failed-{role.name}.png")
                except (OSError, Closed) as e:
                    reply = f"error: {e}"
                if failed(reply):
                    self.capture_failures.append(f"{role.name}: {reply}")
                    print(f"could not save {role.name}'s screen: {reply}")

    def finish(self, keep):
        failures = {}
        failures_lock = threading.Lock()
        if not keep:
            def shut(role):
                inst = role.inst
                if inst is None or inst.quit:
                    return
                if inst.file is None:
                    if inst.proc.poll() is None:
                        end(inst.proc)
                    return
                if inst.proc.poll() is None:
                    with role.lock:
                        why = inst.stop()
                else:
                    why = None if inst.proc.returncode == 0 else f"exited with status {inst.proc.returncode}"
                if why:
                    with failures_lock:
                        failures[role.name] = why.replace("'quit'", "shutting down")
            threads = [threading.Thread(target=shut, args=(r,)) for r in self.roles.values()]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
        if self.fixture and self.fixture.poll() is None:
            if keep:  # the kept roles still need their backend
                print(f"fixture left running with the roles: PID {self.fixture.pid}")
            else:
                end(self.fixture, grace=3)
        return failures


def main():
    argv = sys.argv[1:]
    extra = []
    if "--" in argv:
        i = argv.index("--")
        argv, extra = argv[:i], argv[i + 1:]
    if not argv or argv[0].startswith("-"):
        sys.exit(__doc__)
    path, out, keep, warm, cli_vars, apks = argv[0], None, False, False, {}, {}
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--out":
            out = argv[i + 1]; i += 2
        elif a == "--keep":
            keep = True; i += 1
        elif a == "--warm":
            warm = True; i += 1
        elif a == "--apk":
            v = argv[i + 1]
            role, sep, apk = v.partition("=")
            apks[role if sep and not role.endswith(".apk") else None] = apk if sep and not role.endswith(".apk") else v
            i += 2
        elif a == "--var":
            k, _, v = argv[i + 1].partition("="); cli_vars[k] = v; i += 2
        else:
            sys.exit(f"unknown option {a}\n\n{__doc__}")
    try:
        scene = Scene(path, out, cli_vars, extra, warm, apks)
    except (OSError, ValueError, KeyError) as e:
        print(f"bad scene {path}: {e}", file=sys.stderr)
        sys.exit(2)

    result = {"scene": scene.name, "roles": {}, "steps": [], "failure": None}
    peak = [0.0]
    stop = threading.Event()

    def sample():
        while not stop.is_set():
            pids = [r.inst.proc.pid for r in scene.roles.values() if r.inst]
            if pids:
                peak[0] = max(peak[0], rss_mb(pids))
            stop.wait(1)
    threading.Thread(target=sample, daemon=True).start()
    try:
        scene.start_fixture()
        scene.launch()
        for n, step in enumerate(scene.spec.get("steps", []), 1):
            took, errors = scene.run_step(n, step)
            result["steps"].append({"step": n, "name": step.get("name", ""), "seconds": round(took, 3),
                                    "errors": errors})
            if errors:
                raise Failure(f"step {n}: " + "; ".join(f"{r}: {e}" for r, e in errors.items()))
    except Failure as e:
        result["failure"] = str(e)
        scene.shots()
    except ValueError as e:
        result["failure"] = f"bad scene: {e}"
    finally:
        stop.set()
        shutdown = scene.finish(keep)
        if shutdown and not result["failure"]:
            result["failure"] = "; ".join(f"{r}: {w}" for r, w in shutdown.items())
    total = time.time() - scene.t0
    for role in scene.roles.values():
        result["roles"][role.name] = {"device": role.device, "apk": role.apk, "ready_s": role.ready,
                                      "port": role.inst.port if role.inst else None}
    if scene.capture_failures:
        result["capture_failures"] = scene.capture_failures
    shutil.rmtree(scene.stage, ignore_errors=True)
    result["seconds"] = round(total, 2)
    result["peak_rss_mb"] = round(peak[0])
    with open(os.path.join(scene.out, "result.json"), "w") as f:
        json.dump(result, f, indent=1)
    if result["failure"]:
        print(f"FAILED: {result['failure']}")
    print(f"scene {scene.name}: {len(result['steps'])} steps, {len(scene.roles)} roles, "
          f"{'failed' if result['failure'] else 'passed'} in {total:.1f} s; peak memory {peak[0]:.0f} MB together")
    print(f"output in {scene.out}")
    sys.exit(1 if result["failure"] else 0)


if __name__ == "__main__":
    main()
