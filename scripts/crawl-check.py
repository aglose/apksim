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
5. `--sweep all` on `apksim://faults` (deliberate faults: state kept with `remember`, text that clips at font scale 2,
   a spinner with no network, a layout that does not mirror, an unlabeled control, in Compose and in Views) reports each,
   none of the controls beside them that do it right, one failing JUnit case per finding with its path, and exits 0
   once `testapks/composeapp/crawl-allow-faults.txt` accepts them.
8. A walk seeded with walk 1's result (`--seed`) and `--only` one screen gives walk 1's screens, transitions and paths,
   walks that screen alone (`origin`), carries every other with its captures, prints `--events` lines beside the human
   ones, and takes under a fifth of walk 1's time. `--seed` without `--only`, or with `--base`, is refused.
9. A seed walked with a long Settings value that makes the slot screen's text wrap (another structure), walked again
   seeded without it and `--only` that screen: the screen at its position replaces it (`replaces`), the rest is carried.
10. `--record-deps` on home, SecondActivity and the AppCompat check screen: Trips' `sources` have `MainActivity.kt` and
   not `SecondActivity.kt`, SecondActivity's have `SecondActivity.kt`, the AppCompat screen lists its drawable and colour,
   `start_deps` has the Application's file, a walk without the flag has no `deps`, and a seeded walk carries `deps`.
   Both walk times are printed.

--quick (CI, where the image is slow) walks to depth 2. --docker IMAGE runs crawl.py in the Linux image
(`--entrypoint crawl.py`, OUT mounted at /work) as scripts/ci-smoke.sh does. Exit status 1 on any failed expectation.
"""
import argparse, json, os, subprocess, sys, time
import xml.etree.ElementTree as ET

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


def wpath(p):
    """A folder as crawl.py sees it (inside the container with --docker)."""
    return "/work/" + os.path.relpath(p, DOCKER[2]) if DOCKER else p


def shape(doc):
    """What a walk found, without where it came from or how long it took: screens with their paths, and transitions."""
    screens = {s["id"]: (json.dumps(s["path"], sort_keys=True), s.get("parent"), s["depth"], s["activity"],
                         tuple(a["label"] for a in s.get("actions", []))) for s in doc["screens"]}
    edges = sorted(json.dumps({k: e.get(k) for k in ("from", "action", "cmd", "kind", "to", "steps")}, sort_keys=True)
                   for e in doc["edges"])
    return screens, edges


def graph(doc):
    return (sorted(s["id"] for s in doc["screens"]),
            sorted((e["from"], e["action"], str(e["to"])) for e in doc["edges"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=DEFAULT_APK)
    ap.add_argument("--out", default=os.path.join(ROOT, "work", "crawl-check"))
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--no-sweep", action="store_true", help="skip the resilience sweep checks")
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
    check("--serial" in ex.options and any(o.startswith("secure:android_id=") for o in ex.options),
          "every instance has the same serial and ANDROID_ID", str(ex.options))
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

    # 6: the resilience sweep, on the screens with deliberate faults
    secs_e = secs_f = 0
    if not args.no_sweep:
        faults = ["--start", "apksim://faults", "--sweep", "all", "-j", str(args.j), "--no-captures", "--stable-ms", "200"]
        e_dir, f_dir = os.path.join(args.out, "sweep"), os.path.join(args.out, "sweep-allowed")
        doc5, secs_e, p5 = crawl(args.apk, e_dir, *faults)
        found = {(f["perturbation"], f["rule"], f["screen"].split("-")[0]): f for f in doc5["findings"] if f["kind"] == "sweep"}
        want = {("process-death", "state-lost", "FaultsActivity"), ("recreate", "state-lost", "FaultsActivity"),
                ("rotate", "state-lost", "FaultsActivity"), ("process-death", "state-lost", "FaultsViewsActivity"),
                ("recreate", "state-lost", "FaultsViewsActivity"), ("rotate", "state-lost", "FaultsViewsActivity"),
                ("offline", "endless-spinner", "FaultsActivity"), ("rtl", "rtl-not-mirrored", "FaultsActivity"),
                ("fontscale", "text-cut-off", "FaultsActivity"), ("fontscale", "text-cut-off", "FaultsViewsActivity"),
                ("dark", "a11y-unlabeled", "FaultsActivity")}
        check(want <= set(found), "the sweep finds each deliberate fault", f"missing {sorted(want - set(found))}")
        detail = " | ".join(f["detail"] for f in doc5["findings"] if f["kind"] == "sweep")
        check("saved: " not in detail and "wraps and always" not in detail and "saved taps" not in detail,
              "no finding on the controls that do it right (saved state, wrapping text)", detail[:300])
        check("Departure gate" in detail and "Panel label" not in detail,
              "a parent with a label of its own is not cut off because a child is", detail[-300:])
        check("Boarding gate B12" in detail and "Your table is ready" in detail and "Checked baggage" in detail,
              "clipped text is found in Compose (ellipsis, fixed height) and in Views")
        check(p5.returncode == 1, "the run fails on findings", str(p5.returncode))
        suite = ET.parse(os.path.join(e_dir, "junit.xml")).getroot()
        failing = [c for c in suite.iter("testcase") if c.find("failure") is not None]
        n_found = sum(1 for f in doc5["findings"] if f["kind"] == "sweep")
        check(len(failing) == n_found and n_found >= len(want) and all("restart" in c.find("failure").text for c in failing),
              "JUnit has a failing case per finding, with the path to reproduce it", f"{len(failing)} failing for {n_found}")
        allow = os.path.join(ROOT, "testapks/composeapp/crawl-allow-faults.txt")
        if DOCKER:  # the container sees only the mounted folder
            subprocess.run(["cp", allow, os.path.join(args.out, "allow.txt")], check=True)
            allow = "/work/allow.txt"
        doc6, secs_f, p6 = crawl(args.apk, f_dir, *faults, "--allow", allow, expect=(0,))
        suite = ET.parse(os.path.join(f_dir, "junit.xml")).getroot()
        check(p6.returncode == 0 and suite.get("failures") == "0" and int(suite.get("skipped")) == n_found
              and not doc6["sweep"]["stale"], "an allow-list accepts them: exit 0, the cases skipped, no stale entry",
              f"exit {p6.returncode}, failures {suite.get('failures')}, skipped {suite.get('skipped')}, stale {doc6['sweep']['stale']}")

    if not args.no_sweep:
        # 7: a sweep that the deadline cut short says so (it must not look like a pass)
        doc9, _, p9 = crawl(args.apk, os.path.join(args.out, "sweep-deadline"), "--start", "apksim://faults", "--sweep", "recreate",
                            "--max-seconds", "3", "--no-captures", "-j", "1")
        inc = [f for f in doc9["findings"] if f.get("rule") == "sweep-incomplete"]
        check(bool(inc) and p9.returncode == 1, "a sweep the deadline cut short reports sweep-incomplete and fails", f"exit {p9.returncode}, {inc}")
        crash_fix = ET.parse(os.path.join(args.out, "sweep-deadline", "junit.xml")).getroot()
        check(int(crash_fix.get("skipped")) >= 1, "its JUnit lists the cases that did not run as skipped")

    # 8: a seeded walk that walks one screen again
    seed_dir = os.path.join(args.out, "seeded")
    max_depth = 2 if args.quick else None
    pick = sorted((len(by_edge.get(s["id"], [])), s["id"]) for s in screens
                  if s["depth"] > 0 and not s.get("flaky") and by_edge.get(s["id"])
                  and all(e["to"] not in ("FAILED", "CRASH") for e in by_edge[s["id"]])
                  and (max_depth is None or s["depth"] < max_depth))
    only = pick[0][1] if pick else screens[0]["id"]
    doc10, secs_g, p10 = crawl(args.apk, seed_dir, *matrix, *opts, "--seed", wpath(a_dir), "--only", only, "--events")
    sa, sb = shape(doc), shape(doc10)
    check(sa == sb, f"a walk seeded with walk 1 and --only {only} finds the same screens, transitions and paths",
          f"screens only in 1st {sorted(set(sa[0]) - set(sb[0]))[:3]}, only in seeded {sorted(set(sb[0]) - set(sa[0]))[:3]}, "
          f"differing {[k for k in sa[0] if k in sb[0] and sa[0][k] != sb[0][k]][:3]}; edges only in 1st "
          f"{sorted(set(sa[1]) - set(sb[1]))[:2]}, only in seeded {sorted(set(sb[1]) - set(sa[1]))[:2]}")
    origins = {s["id"]: s.get("origin") for s in doc10["screens"]}
    check(origins.get(only) == "walked" and all(o == "carried" for k, o in origins.items() if k != only)
          and all(s.get("origin") == "walked" for s in screens),
          "the --only screen is walked, every other carried (and a plain walk says walked)",
          str([(k, o) for k, o in origins.items() if (o == "walked") != (k == only)][:4]))
    caps = [(s["id"], c) for s in doc10["screens"] for c in doc10["combos"]
            if not os.path.isfile(os.path.join(seed_dir, s["captures"].get(c, "missing")))]
    check(not caps, "every screen of the seeded walk has its captures, carried ones from the seed", str(caps[:3]))
    lines = p10.stdout.splitlines()
    evs = [json.loads(ln) for ln in lines if ln.startswith("{")]
    check({e["id"] for e in evs if e.get("event") == "screen"} == set(origins) and any(e.get("event") == "level" for e in evs)
          and any(e.get("event") == "done" for e in evs) and any(ln.startswith("[new] depth ") for ln in lines),
          "--events prints a JSON line per screen and level, and the human lines stay", str(evs[:2]))
    check(secs_g < secs_a / 5, "the seeded walk takes under a fifth of the full walk's time", f"{secs_g:.0f} s against {secs_a:.0f} s")
    for extra, what in ((["--seed", wpath(a_dir)], "--seed without --only"),
                        (["--seed", wpath(a_dir), "--only", only, "--base", args.apk], "--seed with --base")):
        p = subprocess.run([sys.executable, CRAWL, args.apk, "--out", os.path.join(args.out, "unit"), *extra],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        check(p.returncode == 2 and "--seed" in p.stdout, f"{what} is refused", p.stdout[-200:])

    # 9: an --only screen whose path now arrives at another structure
    rs_dir, rw_dir = os.path.join(args.out, "replace-seed"), os.path.join(args.out, "replaced")
    two = ["--start", "apksim://composeapp/home", "--start", "apksim://slot", "--max-depth", "1", "--matrix", "appearance=light",
           "-j", str(args.j)]
    doc11, _, _ = crawl(args.apk, rs_dir, *two, "--setting", "global:apksim_slot_seed=" + "long-" * 120)
    old = [s["id"] for s in doc11["screens"] if s["activity"].endswith("SlotStateActivity") and not s["path"]["steps"]]
    doc12, _, _ = crawl(args.apk, rw_dir, *two, "--seed", wpath(rs_dir), "--only", old[0] if old else "none")
    now = [s for s in doc12["screens"] if s.get("replaces")]
    check(len(old) == 1 and len(now) == 1 and now[0]["replaces"] == old[0] and now[0]["id"] != old[0]
          and now[0]["origin"] == "walked" and now[0]["path"] == {"start": "apksim://slot", "steps": []}
          and old[0] not in {s["id"] for s in doc12["screens"]} | {e["to"] for e in doc12["edges"]},
          "an --only screen that arrives at another structure is replaced at its position", f"old {old}, now {[(s['id'], s['replaces']) for s in now]}")
    check(all(s["origin"] == "carried" for s in doc12["screens"] if s is not (now[0] if now else None))
          and len(doc12["screens"]) == len(doc11["screens"]), "the rest of that seed is carried",
          str([(s["id"], s["origin"]) for s in doc12["screens"]]))

    # 10: per-screen dependencies (--record-deps)
    dep_starts = ["--start", "apksim://composeapp/home", "--start", "apksim://stack", "--start", "apksim://appcompat",
                  "--max-depth", "2", "--no-captures", "-j", str(args.j)]
    nodeps_dir, deps_dir, dseed_dir = (os.path.join(args.out, n) for n in ("nodeps", "deps", "deps-seeded"))
    doc13, secs_plain, _ = crawl(args.apk, nodeps_dir, *dep_starts)
    check("start_deps" not in doc13 and not any("deps" in s for s in doc13["screens"]),
          "without --record-deps no deps field is written")
    doc14, secs_deps, _ = crawl(args.apk, deps_dir, *dep_starts, "--record-deps")
    dscreens = doc14["screens"]

    def deps_of(pred):
        hit = [s for s in dscreens if pred(s)]
        return hit[0].get("deps") if hit else None
    dtrips = deps_of(lambda s: s["path"] == {"start": "apksim://composeapp/home", "steps": ["tap-text Trips"]})
    dstack = deps_of(lambda s: s["path"] == {"start": "apksim://stack", "steps": []})
    dcompat = deps_of(lambda s: s["path"] == {"start": "apksim://appcompat", "steps": []})
    src = lambda d: d["sources"] if d else []
    check(bool(dtrips) and "dev/apksim/composeapp/MainActivity.kt" in src(dtrips)
          and not any(x.endswith("/SecondActivity.kt") for x in src(dtrips)),
          "Trips depends on MainActivity.kt and not on SecondActivity.kt", str(src(dtrips)))
    check("dev/apksim/composeapp/SecondActivity.kt" in src(dstack), "the apksim://stack screen depends on SecondActivity.kt",
          str(src(dstack)))
    check(bool(dcompat) and "drawable/appcompat_check_square" in dcompat["resources"]
          and "color/appcompat_check_mode" in dcompat["resources"],
          "a screen lists the drawable and colour it resolves", str(dcompat and dcompat["resources"][:8]))
    explored = [s for s in dscreens if s["id"] in {e["from"] for e in doc14["edges"]}]
    check(all(isinstance(s.get("deps"), dict) and s["deps"]["classes"] == sorted(s["deps"]["classes"])
              and set(s["deps"]) == {"classes", "sources", "resources"} for s in explored),
          "every explored screen has deps of classes, sources and resources, sorted",
          str([s["id"] for s in explored if not s.get("deps")][:4]))
    sd = doc14.get("start_deps") or {}
    check("dev/apksim/composeapp/ComposeApp.kt" in sd.get("sources", []) and "deps_unreliable" not in doc14,
          "start_deps has the Application's file, and a debug build's map is reliable",
          f"{sd.get('sources')} {doc14.get('deps_unreliable')}")
    donly = dstack and next(s["id"] for s in dscreens if s.get("deps") is dstack)
    doc15, _, _ = crawl(args.apk, dseed_dir, "--no-captures", "-j", str(args.j), "--seed", wpath(deps_dir),
                        "--only", donly or "none", "--record-deps")
    seed_deps = {s["id"]: s.get("deps") for s in dscreens}
    carried = [s for s in doc15["screens"] if s.get("origin") == "carried"]
    walked = [s for s in doc15["screens"] if s["id"] == donly]
    check(bool(carried) and all(s.get("deps") == seed_deps.get(s["id"]) for s in carried)
          and bool(walked) and "dev/apksim/composeapp/SecondActivity.kt" in src(walked[0].get("deps"))
          and "start_deps" in doc15, "a seeded walk carries its seed's deps and records the walked screen's again",
          str([s["id"] for s in carried if s.get("deps") != seed_deps.get(s["id"])][:3]))
    print(f"     walk with --record-deps {secs_deps:.0f} s, without {secs_plain:.0f} s ({len(dscreens)} screens)")

    print(f"timings: walk+captures {secs_a:.0f} s ({len(screens)} screens, {len(doc['edges'])} transitions), repeat walk {secs_b:.0f} s, "
          f"compare {secs_c:.0f} s, prepared {secs_d:.0f} s, sweep {secs_e:.0f} s ({secs_f:.0f} s with an allow-list), "
          f"seeded walk of one screen {secs_g:.0f} s, walk with --record-deps {secs_deps:.0f} s (without {secs_plain:.0f} s)")
    if failures:
        print(f"crawl-check: {len(failures)} check(s) failed", file=sys.stderr)
        sys.exit(1)
    print("crawl-check: all passed")


main()
