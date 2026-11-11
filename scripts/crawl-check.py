#!/usr/bin/env python3
"""Checks scripts/crawl.py on the test app (docs/crawl.md "Check").

Usage: crawl-check.py [--apk APK] [--out DIR] [--quick] [--docker IMAGE] [-j N] [crawl.py options]

1. A walk from the home screen, SecondActivity ("apksim://stack") and the dialogs screen finds the tabs, Trips,
   SecondActivity with its "Deeper" button and every dialog; every screen's path replays and is captured in a
   two-combination matrix (a screen whose path does not replay is a `replay` finding); nothing crashes.
2. A second walk gives the same graph: the same screens (ids), the same transitions.
3. The same APK as its own base, launched with a changed Settings value only the slot screen shows, is compared:
   exactly that screen is reported changed, no screen new or gone.
4. A prepared start (`--setting`, `--sdcard-file`) reaches the screen that shows it.

--quick (CI, where the image is slow) walks to depth 2. --docker IMAGE runs crawl.py in the Linux image
(`--entrypoint crawl.py`, OUT mounted at /work) as scripts/ci-smoke.sh does. Exit status 1 on any failed expectation.
"""
import argparse, json, os, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CRAWL = os.path.join(ROOT, "scripts", "crawl.py")
DEFAULT_APK = os.path.join(ROOT, "testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk")
failures = []


def check(ok, what, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (f": {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        failures.append(what)


DOCKER = None  # (image, platform, work folder on the host)


def crawl(apk, out, *extra, expect=(0, 1)):
    if DOCKER:
        image, platform, work = DOCKER
        cmd = ["docker", "run", "--rm", "--platform", platform, "-v", f"{work}:/work", "--entrypoint", "crawl.py", image,
               "/work/app.apk", "--out", "/work/" + os.path.relpath(out, work), *extra]
    else:
        cmd = [sys.executable, CRAWL, apk, "--out", out, *extra]
    try:
        os.remove(os.path.join(out, "crawl.json"))  # never read an earlier run's result
    except OSError:
        pass
    t0 = time.time()
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    secs = time.time() - t0
    print(f"     crawl {' '.join(extra)[:110]}: {secs:.0f} s, exit {p.returncode}", flush=True)
    if p.returncode not in expect:
        print(p.stdout[-3000:])
        print(f"crawl-check: crawl.py exited {p.returncode}, expected one of {expect}", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(os.path.join(out, "crawl.json")):
        print(p.stdout[-3000:])
        print(f"crawl-check: crawl.py (exit {p.returncode}) wrote no crawl.json in {out}", file=sys.stderr)
        sys.exit(1)
    with open(os.path.join(out, "crawl.json")) as f:
        return json.load(f), secs, p


def graph(doc):
    return (sorted(s["id"] for s in doc["screens"]),
            sorted((e["from"], e["action"], str(e["to"])) for e in doc["edges"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=DEFAULT_APK)
    ap.add_argument("--out", default=os.path.join(ROOT, "work", "crawl-check"))
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--docker")
    ap.add_argument("-j", type=int, default=3)
    args, rest = ap.parse_known_args()
    args.out = os.path.abspath(args.out)
    if args.docker:
        global DOCKER
        os.makedirs(args.out, exist_ok=True)
        platform = "linux/" + subprocess.run(["docker", "image", "inspect", "-f", "{{.Architecture}}", args.docker],
                                              stdout=subprocess.PIPE, text=True, check=True).stdout.strip()
        subprocess.run(["cp", args.apk, os.path.join(args.out, "app.apk")], check=True)
        DOCKER = (args.docker, platform, args.out)
    opts = ["-j", str(args.j)] + (["--max-depth", "2"] if args.quick else []) + rest
    starts = ["--start", "apksim://composeapp/home", "--start", "apksim://stack", "--start", "apksim://dialogs"]
    matrix = ["--matrix", "appearance=light,dark"]
    a_dir, b_dir, c_dir, d_dir = (os.path.join(args.out, n) for n in ("a", "b", "base", "prepared"))

    # 1
    doc, secs_a, _ = crawl(args.apk, a_dir, *starts, *matrix, *opts)
    screens = doc["screens"]
    by_edge = {}
    for e in doc["edges"]:
        by_edge.setdefault(e["from"], []).append(e)
    root = next((s for s in screens if s["path"].get("start") == "apksim://composeapp/home" and not s["path"]["steps"]), None)
    check(root is not None, "the home screen is a root")
    labels = {e["action"] for e in by_edge.get(root["id"], [])} if root else set()
    check({"Trips", "Settings", "Shader"} <= labels, "the tabs are found from home", str(sorted(labels)))
    trips = [s for s in screens if s["path"]["steps"] == ["tap-text Trips"] and s["path"].get("start") == "apksim://composeapp/home"]
    check(len(trips) == 1 and "vacation" in trips[0]["title"].lower(), "Trips is found behind its tab and shows its list",
          str(trips and trips[0]["title"]))
    stack = [s for s in screens if s["activity"].endswith("SecondActivity")]
    check(bool(stack) and any(e["action"].lower() == "deeper" and e["to"] == stack[0]["id"]
                              for e in by_edge.get(stack[0]["id"], [])), "SecondActivity is found and 'Deeper' is a transition",
          str([s["id"] for s in stack]))
    dialogs = [s for s in screens if s["activity"].endswith("DialogsActivity")]
    dialog_titles = " | ".join(s["title"] for s in dialogs)
    check(len({s["id"] for s in dialogs}) >= 5 and all(t in dialog_titles for t in ("Full-screen dialog open", "Small modal dialog open", "Compose dialog open")),
          "the dialogs screen and its dialogs are separate screens", dialog_titles)
    bad = [f for f in doc["findings"] if f["kind"] in ("crash", "replay", "flaky", "capture")]
    check(not bad, "no crash, no path that does not replay, no failed capture", json.dumps(bad)[:600])
    missing = [(s["id"], c) for s in screens for c in doc["combos"]
               if not os.path.getsize(os.path.join(a_dir, s["captures"].get(c, "missing"))) > 1000] \
        if all(c in s["captures"] for s in screens for c in doc["combos"]) else ["captures missing"]
    check(not missing, f"every screen is captured in {len(doc['combos'])} combinations", str(missing[:3]))
    check(os.path.getsize(os.path.join(a_dir, "report.html")) > 5000, "the HTML report is written")
    skipped = [e for e in doc["edges"] if e["kind"] == "skipped"]
    check(any(e["action"] == "Share" for e in skipped), "a control on the skip list is not tapped (Share)")

    # 2
    doc2, secs_b, _ = crawl(args.apk, b_dir, *starts, *opts, "--no-captures")
    ga, gb = graph(doc), graph(doc2)
    check(ga == gb, "a second walk gives the same graph", f"{len(ga[0])} vs {len(gb[0])} screens; only in 1st: "
          f"{sorted(set(ga[1]) - set(gb[1]))[:3]}; only in 2nd: {sorted(set(gb[1]) - set(ga[1]))[:3]}")

    # 3
    doc3, secs_c, p3 = crawl(args.apk, c_dir, "--start", "apksim://composeapp/home", "--start", "apksim://slot",
                             "--max-depth", "1", "-j", str(args.j), "--base-option", "--setting global:apksim_slot_seed=changed",
                             "--matrix", "appearance=light", expect=(1,))
    cmp = doc3.get("compare") or []
    changed = [p["id"] for p in cmp if p["status"] == "changed"]
    others = [(p["status"], p["id"] or p["base"]) for p in cmp if p["status"] not in ("same", "changed")]
    slot = [s["id"] for s in doc3["screens"] if s["activity"].endswith("SlotStateActivity")]
    check(p3.returncode == 1 and changed == slot and len(slot) == 1 and not others and len(cmp) >= 4,
          "with --base, exactly the changed screen is reported", f"changed={changed} others={others} slot={slot} of {len(cmp)}")
    diffs = (doc3.get("diffs") or {}).get(slot[0] if slot else "", {})
    check(any(d.get("map") and os.path.exists(os.path.join(c_dir, d["map"])) for d in diffs.values()), "the changed screen has a difference image")

    # 4
    doc4, secs_d, _ = crawl(args.apk, d_dir, "--start", "apksim://slot", "--max-depth", "0", "--matrix", "appearance=light",
                            "--setting", "global:apksim_slot_seed=prepared-by-crawl", *opts[:2])
    shot = doc4["screens"][0] if doc4["screens"] else {}
    check(bool(shot) and "prepared-by-crawl" in json.dumps(doc4["screens"]),
          "a prepared start (--setting) is in effect on the screen it reaches")

    # 5: what review of the first version found
    sys.path.insert(0, os.path.join(ROOT, "scripts"))
    import crawl as crawl_mod
    recs = crawl_mod.parse_tree("0\tandroid.view.View\t\tcv\t0,0,200,100\t\t\t\x1e1\tandroid.widget.TextView\t\tv\t0,0,100,100\t\xd7\tDelete item\t\x1e")
    acts = crawl_mod.candidates(recs, crawl_mod.re.compile(crawl_mod.DEFAULT_SKIP, crawl_mod.re.I), False)
    check(len(acts) == 1 and acts[0]["skipped"], "the skip list reads a descendant's description when it also has text", str(acts))
    a5 = crawl_mod.parse(["x.apk", "--out", os.path.join(args.out, "unit")])
    ex = crawl_mod.Explorer("new", "x.apk", a5, os.path.join(args.out, "unit"), [])
    check("--no-autofill" in ex.options and "crawl" in ex.options, "launches have no autofill and a data-slot namespace of their own", str(ex.options))
    p = subprocess.run([sys.executable, CRAWL, args.apk, "--out", os.path.join(args.out, "unit"), "--keep-data", "--base", args.apk],
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    check(p.returncode == 2 and "--keep-data" in p.stdout, "--keep-data with --base is refused", p.stdout[-200:])
    doc7, _, _ = crawl(args.apk, os.path.join(args.out, "budget"), "--start", "apksim://composeapp/home", "--max-seconds", "4",
                       "--no-captures", "-j", "1")
    check(bool(doc7["incomplete"]), "a deadline that falls inside a level says the walk is incomplete", str(doc7["incomplete"]))
    doc8, _, _ = crawl(args.apk, os.path.join(args.out, "cap"), "--start", "apksim://composeapp/home", "--start", "apksim://slot",
                       "--max-screens", "1", "--max-depth", "0", "--no-captures", "-j", "1")
    check(len(doc8["screens"]) == 1 and any("--max-screens" in x for x in doc8["incomplete"]),
          "--max-screens also limits the starting screens", f"{len(doc8['screens'])} screens, {doc8['incomplete']}")
    logs = os.listdir(os.path.join(c_dir, "logs"))
    check(any(n.startswith("new-instance-") for n in logs) and any(n.startswith("base-instance-") for n in logs),
          "the two builds of a comparison keep separate logs", str(logs[:6]))

    print(f"timings: walk+captures {secs_a:.0f} s ({len(screens)} screens, {len(doc['edges'])} transitions), repeat walk {secs_b:.0f} s, "
          f"compare {secs_c:.0f} s, prepared {secs_d:.0f} s")
    if failures:
        print(f"crawl-check: {len(failures)} check(s) failed", file=sys.stderr)
        sys.exit(1)
    print("crawl-check: all passed")


main()
