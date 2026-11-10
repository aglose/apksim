#!/usr/bin/env python3
"""Startup-time gate: launches one APK with two apksim installs, a fixed anchor and the build under test, and fails
when the build under test starts slower than its budget allows (docs/startup-gate.md).

Usage: startup-gate.py --base DIR --head DIR --apk APK [--config FILE] [--runs N] [--json OUT] [--summary FILE]
       [--base-home DIR] [--head-home DIR]
       startup-gate.py --self-test   (the decision rules on synthetic numbers)

DIR is an apksim folder (a source checkout or an unpacked release); --*-home sets its APKSIM_HOME (state folder).
Both must have a startup cache (scripts/train.sh). Times on a shared CI machine drift by tens of percent between runs,
so the two installs are measured in one job, in rounds whose order is shuffled, and only the ratio between them is
judged: a metric fails when the head's median exceeds the anchor's by more than its budget (`max_slower`, a fraction,
plus `slack_ms`) and a one-sided Mann-Whitney test says the difference is real (p < `alpha`). Rounds come in batches
judged each on its own launches: a metric fails only when two batches are over budget, so one slow stretch of the
machine does not fail a pull request; a batch over its limit, significant or not, is followed by another (at most
three), and a median still over its limit at the end without a second significant batch is reported (not failed) as
inconclusive.
Exit status: 0 within budget, 1 over budget, 2 the launches themselves failed or a gated metric had no samples."""
import argparse, ctypes, json, math, os, random, re, statistics, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# (-Dapksim.timing=verbose marks end with the process's CPU so far, "  cpu N ms", since issue 205; older releases'
# do not.)
MARK = re.compile(r"^\[apksim\] (\S.*?)\s+(-?\d+) ms(?:\s+cpu\s+-?\d+ ms)?$", re.M)


def cpu_ticks():
    """Cumulative (busy, idle) CPU ticks of the whole machine, or None where unknown."""
    try:
        if sys.platform == "darwin":
            lib = ctypes.CDLL("/usr/lib/libSystem.dylib")
            lib.mach_host_self.restype = ctypes.c_uint
            info, count = (ctypes.c_uint * 4)(), ctypes.c_uint(4)  # HOST_CPU_LOAD_INFO: user, system, idle, nice
            if lib.host_statistics(lib.mach_host_self(), 3, info, ctypes.byref(count)) != 0:
                return None
            return info[0] + info[1] + info[3], info[2]
        with open("/proc/stat") as f:
            v = [int(x) for x in f.readline().split()[1:]]
        return sum(v) - v[3] - v[4], v[3] + v[4]
    except (OSError, ValueError, AttributeError):
        return None


def cpu_idle(window=0.3):
    """Percent of the machine's CPU time idle over the next `window` seconds (100 where unknown)."""
    a = cpu_ticks()
    time.sleep(window)
    b = cpu_ticks()
    if a is None or b is None or (b[0] + b[1]) == (a[0] + a[1]):
        return 100
    return round(100 * (b[1] - a[1]) / ((b[0] + b[1]) - (a[0] + a[1])))


class Install:
    def __init__(self, name, folder, home):
        self.name, self.folder = name, os.path.abspath(folder)
        self.launcher = os.path.join(self.folder, "apksim")
        self.client = os.path.join(self.folder, "work", "runtime", "apksim-client")
        self.env = dict(os.environ)
        self.env.pop("APKSIM_HOME", None)
        if home:
            self.env["APKSIM_HOME"] = os.path.abspath(home)
            state = self.env["APKSIM_HOME"]
        elif os.path.exists(os.path.join(self.folder, "work", "release")):
            state = os.path.expanduser("~/.apksim")
        else:
            state = os.path.join(self.folder, "work")
        # The launcher's standby key (see `KEY=` in apksim).
        self.key = self.folder if state == os.path.join(self.folder, "work") else f"{self.folder}:{state}"
        self.sock = subprocess.run([self.client, "sock", self.key, "n"], capture_output=True, text=True,
                                   check=True).stdout.strip()

    def standby_alive(self):
        try:
            os.kill(int(open(self.sock + ".pid").read().strip()), 0)
            return True
        except (OSError, ValueError):
            return False

    def standby_ready(self, deaths, timeout=90):
        """Waits for this install's standby, starting one if there is none or the one booting died (its log is kept in
        `deaths`: a standby that crashes is a finding of its own, but not a startup time)."""
        end = time.time() + timeout
        while time.time() < end:
            if os.path.exists(self.sock):
                return True
            if not self.standby_alive():
                if os.path.exists(self.sock + ".pid"):
                    log = open(self.sock + ".log", errors="replace").read()[-1500:] if os.path.exists(self.sock + ".log") else ""
                    deaths.append(f"{self.name}'s standby died while booting:\n{log}")
                    print(f"  {self.name} standby died while booting: starting another", flush=True)
                    if len(deaths) > 3:
                        return False
                subprocess.run([self.launcher, "--spawn-standby", "n"], env=self.env)
                time.sleep(0.2)
            time.sleep(0.05)
        return False

    def stop_standbys(self):
        subprocess.run([self.launcher, "--stop-standbys"], env=self.env, capture_output=True)

    def launch(self, apk, mode, cwd):
        env = dict(self.env)
        if mode == "cold":
            env["APKSIM_STANDBY"] = "0"
        env["APKSIM_T0"] = str(int(time.time() * 1000))
        try:
            p = subprocess.run([self.launcher, "--headless", "--no-network", "--exit-after-first-frame", apk],
                               capture_output=True, text=True, env=env, cwd=cwd, timeout=180)
        except subprocess.TimeoutExpired as e:
            return None, f"timed out\n{(e.stderr or b'')[-2000:]}"
        marks = {name.strip(): int(ms) for name, ms in MARK.findall(p.stderr)}
        if p.returncode != 0 or "first-frame" not in marks:
            return None, f"exit {p.returncode}\n{p.stderr[-2000:]}"
        # A warm launch must have been taken by a standby, or it measured a cold start.
        if mode == "warm" and "handoff" not in marks:
            return None, "launched cold: no standby took it\n" + p.stderr[-2000:]
        return marks, None


def mann_whitney_greater(xs, ys):
    """One-sided p-value that values in ys tend to be greater than in xs (normal approximation, tie-corrected)."""
    n1, n2 = len(xs), len(ys)
    ranked = sorted([(v, 0) for v in xs] + [(v, 1) for v in ys])
    ranks, i, ties = [0.0] * len(ranked), 0, 0.0
    while i < len(ranked):
        j = i
        while j + 1 < len(ranked) and ranked[j + 1][0] == ranked[i][0]:
            j += 1
        for k in range(i, j + 1):
            ranks[k] = (i + j) / 2 + 1
        t = j - i + 1
        ties += t ** 3 - t
        i = j + 1
    u = sum(r for r, (_, side) in zip(ranks, ranked) if side == 1) - n2 * (n2 + 1) / 2
    n = n1 + n2
    var = n1 * n2 / 12 * ((n + 1) - ties / (n * (n - 1)))
    if var <= 0:
        return 1.0
    z = (u - n1 * n2 / 2 - 0.5) / math.sqrt(var)  # continuity correction
    return 0.5 * math.erfc(z / math.sqrt(2))


def iqr(vs):
    if len(vs) < 4:
        return 0
    q = statistics.quantiles(vs, n=4)
    return q[2] - q[0]


def judge(metrics, samples, alpha):
    rows = []
    for name, m in metrics.items():
        base, head = samples["base"][m["mode"]].get(m["mark"], []), samples["head"][m["mode"]].get(m["mark"], [])
        if not base or not head:
            rows.append({"metric": name, "verdict": "missing"})
            continue
        mb, mh = statistics.median(base), statistics.median(head)
        limit = mb * (1 + m["max_slower"]) + m.get("slack_ms", 0)
        p = mann_whitney_greater(base, head)
        p_faster = mann_whitney_greater(head, base)
        gate = m.get("gate", True)
        if mh > limit and p < alpha:
            verdict = "over budget" if gate else "slower (not gated)"
        elif mh > limit:
            verdict = "inconclusive"
        elif mh < mb * (1 - m["max_slower"]) and p_faster < alpha:
            verdict = "faster"
        else:
            verdict = "ok"
        rows.append({"metric": name, "gate": gate, "base_median": mb, "head_median": mh, "base_iqr": iqr(base),
                     "head_iqr": iqr(head), "change": mh / mb - 1 if mb else 0, "limit": limit, "p": p,
                     "n": (len(base), len(head)), "verdict": verdict})
    return rows


def decide(history):
    """Final verdict of one metric from its rows, one per batch so far, and whether another batch is needed.
    Fails on two batches over budget; a batch over its limit (significant or not) without that asks for another."""
    if sum(r["verdict"] == "over budget" for r in history) >= 2:
        return "over budget", False
    last = history[-1]
    if last["verdict"] in ("over budget", "inconclusive"):
        return "inconclusive", True
    return last["verdict"], False


def self_test():
    """The decision rules on synthetic batches (no launches): `startup-gate.py --self-test`."""
    rnd = random.Random(7)
    metrics = {"warm": {"mode": "warm", "mark": "first-frame", "max_slower": 0.10, "slack_ms": 15}}

    def row(base_ms, head_ms, n=12):
        noise = lambda mu: [mu + rnd.randint(-6, 6) for _ in range(n)]
        return judge(metrics, {"base": {"warm": {"first-frame": noise(base_ms)}},
                               "head": {"warm": {"first-frame": noise(head_ms)}}}, 0.01)[0]

    cases = [
        ("same speed", [(200, 202)], ("ok", False)),
        ("within budget", [(200, 225)], ("ok", False)),
        ("over budget once: measure again", [(200, 280)], ("inconclusive", True)),
        ("over budget twice: fail", [(200, 280), (200, 280)], ("over budget", False)),
        # A slow first batch must not outweigh a fresh batch within budget (pooled, 240 against a 235 limit, would fail).
        ("slow batch, then a clean one", [(200, 280), (200, 200)], ("ok", False)),
        ("over, clean, over: one batch is not two in a row, but two in all", [(200, 280), (200, 200), (200, 280)],
         ("over budget", False)),
        ("much faster", [(200, 150)], ("faster", False)),
    ]
    bad = 0
    for name, batches, want in cases:
        got = decide([row(b, h) for b, h in batches])
        print(f"{'ok  ' if got == want else 'FAIL'} {name}: {got}")
        bad += got != want
    missing = judge({"x": {"mode": "warm", "mark": "appinfo", "max_slower": 0.1}},
                    {"base": {"warm": {}}, "head": {"warm": {}}}, 0.01)[0]["verdict"]
    print(f"{'ok  ' if missing == 'missing' else 'FAIL'} a mark no launch printed is missing: {missing}")
    bad += missing != "missing"
    sys.exit(1 if bad else 0)


def table(rows, base_name, head_name):
    out = [f"| metric | {base_name} | {head_name} | change | budget | p | |", "|---|---:|---:|---:|---:|---:|---|"]
    for r in rows:
        if r["verdict"] == "missing":
            out.append(f"| {r['metric']} | | | | | | no samples |")
            continue
        icon = {"over budget": "❌ over budget", "faster": "✅ faster", "ok": "✅", "inconclusive": "⚠️ inconclusive"}.get(r["verdict"], r["verdict"])
        out.append(f"| {r['metric']} | {r['base_median']:.0f} ms ±{r['base_iqr'] / 2:.0f} | "
                   f"{r['head_median']:.0f} ms ±{r['head_iqr'] / 2:.0f} | {r['change'] * 100:+.1f}% | "
                   f"≤ {r['limit']:.0f} ms | {r['p']:.3f} | {icon} |")
    return "\n".join(out)


def main():
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--head", required=True)
    ap.add_argument("--base-home")
    ap.add_argument("--head-home")
    ap.add_argument("--apk", required=True)
    ap.add_argument("--config", default=os.path.join(ROOT, "scripts", "startup-gate.json"))
    ap.add_argument("--runs", type=int)
    ap.add_argument("--json")
    ap.add_argument("--summary", help="append the Markdown report here (GITHUB_STEP_SUMMARY)")
    ap.add_argument("--base-name")
    ap.add_argument("--head-name", default="this build")
    a = ap.parse_args()
    cfg = json.load(open(a.config))
    runs, alpha, metrics = a.runs or cfg["runs"], cfg.get("alpha", 0.01), cfg["metrics"]
    modes = sorted({m["mode"] for m in metrics.values()})
    apk = os.path.abspath(a.apk)
    sides = {"base": Install("base", a.base, a.base_home), "head": Install("head", a.head, a.head_home)}
    base_name = a.base_name or cfg.get("anchor", "anchor")
    cwd = tempfile.mkdtemp(prefix="startup-gate-")
    batches = []  # per batch: side -> mode -> mark -> launches (ms)
    failures, deaths = [], []

    def ready_all():
        # A warm launch is followed by the next standby booting (seconds of CPU): no launch is measured while any
        # standby of either install is still booting.
        if "warm" in modes:
            for s in sides.values():
                if not s.standby_ready(deaths):
                    print(f"startup-gate: {s.name}'s standby did not come up (log: {s.sock}.log)", file=sys.stderr)
                    print("\n".join(deaths), file=sys.stderr)
                    sys.exit(2)
        # Then the machine itself quiet (the last instance exiting, the OS's own work), up to a few seconds: on a
        # 3-core runner one busy process is a third of it. What was idle goes into the launch's record.
        end = time.time() + 5
        idle = cpu_idle()
        while idle < 80 and time.time() < end:
            idle = cpu_idle()
        return idle

    def measure(side, mode, record=True):
        idle = ready_all()
        marks, err = sides[side].launch(apk, mode, cwd)
        if marks is None:
            failures.append(f"{side} {mode}: {err}")
            print(f"  {side:4} {mode}: FAILED {err.splitlines()[0]}", flush=True)
            if len(failures) > max(3, runs // 2):
                for f in failures:
                    print(f, file=sys.stderr)
                sys.exit(2)
            return
        print(f"  {side:4} {mode}: first frame {marks['first-frame']:5d} ms (cpu {idle}% idle before)", flush=True)
        marks["cpu-idle-before"] = idle
        if record:
            for k, v in marks.items():
                batches[-1][side][mode].setdefault(k, []).append(v)

    for s in sides.values():
        s.stop_standbys()
    # The first launches of an APK translate its classes and fill the class cache; a standby's first launch after
    # its boot is not typical either. Not measured.
    print("warming up", flush=True)
    for _ in range(2):
        for side in sides:
            for mode in modes:
                measure(side, mode, record=False)

    def batch():
        batches.append({side: {m: {} for m in modes} for side in sides})
        first = (len(batches) - 1) * runs
        for i in range(first, first + runs):
            print(f"round {i + 1}", flush=True)
            plan = [(side, mode) for side in sides for mode in modes]
            random.shuffle(plan)
            for side, mode in plan:
                measure(side, mode)
        return judge(metrics, batches[-1], alpha)

    # Each batch is judged on its own launches (pooling would let one slow stretch outweigh a fresh batch that is
    # within budget). A batch over budget is followed by another; two over budget fail.
    history = {name: [] for name in metrics}
    while True:
        rows = batch()
        missing = [r["metric"] for r in rows if r["verdict"] == "missing" and metrics[r["metric"]].get("gate", True)]
        if missing:
            print(f"startup-gate: no samples for gated metric(s) {', '.join(missing)}: no launch printed their mark "
                  f"(a mark from -Dapksim.timing=verbose needs that switch)", file=sys.stderr)
            for side in sides.values():
                side.stop_standbys()
            sys.exit(2)
        for r in rows:
            history[r["metric"]].append(r)
        pending = [n for n in metrics if metrics[n].get("gate", True) and decide(history[n])[1]]
        if not pending or len(batches) == 3:
            break
        print(f"{', '.join(pending)} over budget in batch {len(batches)}: measuring a batch of {runs} more rounds "
              f"before it counts", flush=True)
        print(table(rows, base_name, a.head_name), flush=True)
    for side in sides.values():
        side.stop_standbys()
    # The last batch's numbers, with each metric's verdict over all batches.
    for r in rows:
        if r.get("gate", True):
            r["verdict"] = decide(history[r["metric"]])[0]
    per_batch = ["", "| batch | " + " | ".join(metrics) + " |", "|---|" + "---:|" * len(metrics)]
    for i in range(len(batches)):
        per_batch.append(f"| {i + 1} | " + " | ".join(
            (f"{h['change'] * 100:+.1f}% (p {h['p']:.3f})" if "change" in h else "no samples")
            for h in (history[n][i] for n in metrics)) + " |")

    over = [r for r in rows if r["verdict"] == "over budget"]
    report = [f"### Startup time: {a.head_name} against {base_name}", "",
              table(rows, base_name, a.head_name), *(per_batch if len(batches) > 1 else []), "",
              f"Medians of the last batch's {runs} launches each of `{os.path.basename(apk)}` (headless, "
              f"`--no-network`), from the launcher's start to the app's first frame; ± is half the interquartile "
              f"range. Over budget means slower than the budget **and** p < {alpha} (one-sided Mann-Whitney) in two "
              f"batches of {runs} rounds, each judged on its own. Load average at the end: "
              f"{' '.join(f'{x:.1f}' for x in os.getloadavg())}."]
    if over:
        report += ["", "**Startup got slower than its budget.** Find the cause with `-Dapksim.timing=verbose` and "
                   "`scripts/bench.py` (docs/startup-gate.md); if the cost is wanted, raise the budget in "
                   "`scripts/startup-gate.json` in the same pull request and say why."]
    elif any(r["verdict"] == "inconclusive" for r in rows):
        report += ["", "⚠️ A median is over budget, but the launches were too spread out to tell it from noise (a busy "
                   "machine). Not failed; run the job again to check."]
    elif any(r["verdict"] == "faster" for r in rows):
        report += ["", "Faster than the anchor by more than the budget: once this is on main, a release with it can "
                   "become the new anchor (`scripts/startup-gate.json`), so the gain is kept."]
    if deaths:
        report += ["", f"⚠️ {len(deaths)} standby JVM(s) died while booting and were started again (a crash, not a "
                   "startup time; the log tail is in the JSON report)."]
    if failures:
        report += ["", f"{len(failures)} launch(es) failed and were left out:", "```",
                   *(f.splitlines()[0] for f in failures), "```"]
    text = "\n".join(report)
    print(text)
    if a.summary:
        with open(a.summary, "a") as f:
            f.write(text + "\n")
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"anchor": base_name, "apk": os.path.basename(apk), "rows": rows, "batches": batches,
                       "failures": failures, "standby_deaths": deaths,
                       "loadavg": os.getloadavg()}, f, indent=1)
    sys.exit(1 if over else 0)


if __name__ == "__main__":
    main()
