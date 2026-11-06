#!/usr/bin/env python3
"""Startup-time gate: launches one APK with two apksim installs, a fixed anchor and the build under test, and fails
when the build under test starts slower than its budget allows (docs/startup-gate.md).

Usage: startup-gate.py --base DIR --head DIR --apk APK [--config FILE] [--runs N] [--json OUT] [--summary FILE]
       [--base-home DIR] [--head-home DIR]

DIR is an apksim folder (a source checkout or an unpacked release); --*-home sets its APKSIM_HOME (state folder).
Both must have a startup cache (scripts/train.sh). Times on a shared CI machine drift by tens of percent between runs,
so the two installs are measured in one job, in rounds whose order is shuffled, and only the ratio between them is
judged: a metric fails when the head's median exceeds the anchor's by more than its budget (`max_slower`, a fraction,
plus `slack_ms`) and a one-sided Mann-Whitney test says the difference is real (p < `alpha`). A failure is measured
again with as many rounds before it counts, so one slow stretch of the machine does not fail a pull request; a median
over budget that is not significant is measured up to twice more, and is reported (not failed) if it stays unclear.
Exit status: 0 within budget, 1 over budget, 2 the launches themselves failed."""
import argparse, json, math, os, random, re, statistics, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MARK = re.compile(r"^\[apksim\] (\S.*?)\s+(-?\d+) ms$", re.M)


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
    samples = {s: {m: {} for m in modes} for s in sides}
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
        time.sleep(0.5)

    def measure(side, mode, record=True):
        ready_all()
        marks, err = sides[side].launch(apk, mode, cwd)
        if marks is None:
            failures.append(f"{side} {mode}: {err}")
            print(f"  {side:4} {mode}: FAILED {err.splitlines()[0]}", flush=True)
            if len(failures) > max(3, runs // 2):
                for f in failures:
                    print(f, file=sys.stderr)
                sys.exit(2)
            return
        print(f"  {side:4} {mode}: first frame {marks['first-frame']:5d} ms", flush=True)
        if record:
            for k, v in marks.items():
                samples[side][mode].setdefault(k, []).append(v)

    for s in sides.values():
        s.stop_standbys()
    # The first launches of an APK translate its classes and fill the class cache; a standby's first launch after
    # its boot is not typical either. Not measured.
    print("warming up", flush=True)
    for _ in range(2):
        for side in sides:
            for mode in modes:
                measure(side, mode, record=False)

    def rounds(n, first):
        for i in range(first, first + n):
            print(f"round {i + 1}", flush=True)
            plan = [(side, mode) for side in sides for mode in modes]
            random.shuffle(plan)
            for side, mode in plan:
                measure(side, mode)

    # A median over budget is measured again before it counts: once more when the difference is significant, up to
    # twice when it is not yet (a busy machine widens the spread, and more launches tell a real change from noise).
    rounds(runs, 0)
    batches = 1
    while True:
        rows = judge(metrics, samples, alpha)
        over_limit = [r for r in rows if r.get("gate") and r["verdict"] != "missing" and r["head_median"] > r["limit"]]
        if not over_limit or batches == 3 or (batches >= 2 and all(r["p"] < alpha for r in over_limit)):
            break
        print(f"{', '.join(r['metric'] for r in over_limit)} over budget after {batches * runs} rounds: measuring "
              f"{runs} more before it counts", flush=True)
        print(table(rows, base_name, a.head_name), flush=True)
        rounds(runs, batches * runs)
        batches += 1
    for s in sides.values():
        s.stop_standbys()

    over = [r for r in rows if r["verdict"] == "over budget"]
    report = [f"### Startup time: {a.head_name} against {base_name}", "",
              table(rows, base_name, a.head_name), "",
              f"Median of {len(samples['head'][modes[0]].get('first-frame', []))} launches each of "
              f"`{os.path.basename(apk)}` (headless, `--no-network`), from the launcher's start to the app's first "
              f"frame; ± is half the interquartile range. Over budget means slower than the budget **and** "
              f"p < {alpha} (one-sided Mann-Whitney), measured twice. Load average at the end: "
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
            json.dump({"anchor": base_name, "apk": os.path.basename(apk), "rows": rows, "samples": samples,
                       "failures": failures, "standby_deaths": deaths,
                       "loadavg": os.getloadavg()}, f, indent=1)
    sys.exit(1 if over else 0)


if __name__ == "__main__":
    main()
