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
11. One instance on the slot screen, whose Write button writes a Settings value and an /sdcard file the screen shows:
   after a walk that tapped Write, the screen's capture is the picture a walk that tapped nothing takes (the instance
   was launched again on an emptied slot, `relaunches`, for those two files), and with `--keep-app-state` it is not
   (what a walk did before: later captures saw what earlier taps had written).
12. `--keep-data -j 2` from a slot prepared by a launch that tapped Write: the slot screen shows the prepared values,
   the second instance is used, the prepared slot's files are unchanged and no copy is left (not with --docker).
13. A walk stopped with SIGTERM while it explores depth 1 exits 143 within 4 s with crawl.json written (`stopped`,
   `cut_by`, `unexplored` ids); `--seed` on it with `--only-file` of those ids ends with walk 1's screens and
   transitions, every screen captured.
14. A walk from `apksim://login` (a username and password form) without `--autofill` finds the form alone and warns
   that its start screen has a password field; with `--autofill` and the test app's sign-in saved (in the state
   folder, or mounted into the image), each launch signs in first and the walk finds the account screen behind it, with
   no relaunch; crawl.json records the zone (UTC). With a wrong password saved the walk stops (exit 2) with nothing
   captured and the password in none of its files. --autofill with anything that records the network (--freeze-network,
   --network record, --rec net) is refused. The walks are skipped on the Mac when a sign-in is already saved for the
   test app.
15. `apksim://unstable?mode=tick` (content that changes between reads) and `?mode=fail` (a request that fails as it
   opens) are `unstable` in a seed walk, home is not, and the same build walked against that seed with `--diff-seed`
   reports none of them changed, new or gone (exit 0). No screen of walk 1 is unstable.

--quick (CI, where the image is slow) walks to depth 2. --docker IMAGE runs crawl.py in the Linux image
(`--entrypoint crawl.py`, OUT mounted at /work) as scripts/ci-smoke.sh does. Exit status 1 on any failed expectation.
"""
import argparse, hashlib, json, os, re, shutil, signal, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CRAWL = os.path.join(ROOT, "scripts", "crawl.py")
DEFAULT_APK = os.path.join(ROOT, "testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk")
failures = []



def descendants(pid):
    """Every process below pid (pgrep -P, level by level)."""
    out, level = [], [pid]
    while level:
        nxt = []
        for p in level:
            r = subprocess.run(["pgrep", "-P", str(p)], capture_output=True, text=True)
            nxt += [int(x) for x in r.stdout.split()]
        out += nxt
        level = nxt
    return out

def check(ok, what, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (f": {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        failures.append(what)


DOCKER = None  # (image, platform, work folder on the host)


def crawl(apk, out, *extra, expect=(0, 1), mounts=()):
    """[mounts]: (host file, container path) pairs added with --docker (the image's state folder is /opt/apksim/work)."""
    if DOCKER:
        image, platform, work = DOCKER
        vols = [a for host, cont in mounts for a in ("-v", f"{host}:{cont}:ro")]
        cmd = ["docker", "run", "--rm", "--platform", platform, "-v", f"{work}:/work", *vols, "--entrypoint", "crawl.py", image,
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
    # The default skip list, and the Settings tab's "Play clip": a 3 s clip whose screen changes with how far it has
    # played, so what a tap after it reaches depends on timing (a freshly launched instance plays later than a warm one).
    sys.path.insert(0, os.path.dirname(CRAWL))
    from crawl import DEFAULT_SKIP
    skip = ["--skip-text", DEFAULT_SKIP, "--skip-text", r"\bplay clip\b"]
    opts = ["-j", str(args.j)] + (["--max-depth", "2"] if args.quick else []) + skip + rest
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
    shaky = [(s["id"], s["unstable"]) for s in screens if s.get("unstable")]
    check(not shaky, "no screen of the test app's walk is unstable (read twice alike, no request failed)", str(shaky)[:600])

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
    # Where a difference is (issue 630): bounds beside ui_diff's entries, regions of a difference map
    ud = crawl_mod.ui_diff({"texts": ["a", "b"], "text_boxes": [[0, 0, 1, 1], [2, 2, 3, 3]], "actions": [], "size": [822, 1782]},
                           {"texts": ["a", "c", "d"], "text_boxes": [[0, 0, 1, 1], [5, 5, 6, 6], [7, 7, 8, 8]], "actions": []})
    check(ud["texts"]["bounds"] == {"replaced": [[[2, 2, 3, 3], [5, 5, 6, 6]]], "added": [[7, 7, 8, 8]]} and ud["size"] == [822, 1782],
          "ui_diff gives each changed text its bounds in the build it exists in", str(ud))
    check("bounds" not in crawl_mod.ui_diff({"texts": ["a"]}, {"texts": ["b"]}).get("texts", {}),
          "ui_diff of a crawl with no recorded bounds has none", "")
    import struct, zlib
    mw, mh = 100, 60
    rows = []
    for y in range(mh):
        row = bytearray(b"\xdc\xdc\xdc\xff" * mw)
        if 10 <= y < 20:
            row[8 * 4:30 * 4] = b"\xff\x00\x00\xff" * 22
        if 40 <= y < 45:
            row[70 * 4:80 * 4] = b"\xff\x00\x00\xff" * 10
        rows.append(bytes([0]) + bytes(row))
    def chunk(k, d):
        return struct.pack(">I", len(d)) + k + d + struct.pack(">I", zlib.crc32(k + d))
    mp = os.path.join(args.out, "regions-map.png")
    os.makedirs(args.out, exist_ok=True)
    with open(mp, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", mw, mh, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
    reg = crawl_mod.diff_regions(mp)
    check(reg == {"size": [100, 60], "regions": [[8, 10, 30, 20], [70, 40, 80, 45]], "more": 0},
          "a difference map's red areas become boxes, largest first", str(reg))
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
    # --quick (CI's image) walks to depth 2 only, so the full walk is short and the seeded one's fixed costs weigh more.
    share = 2 if args.quick else 5
    check(secs_g < secs_a / share, f"the seeded walk takes under 1/{share} of the full walk's time", f"{secs_g:.0f} s against {secs_a:.0f} s")
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

    # 9b: --diff-seed compares walked/replaced screens directly against the seed (semantic ui_diff + pixel diff)
    ds_same_dir, ds_diff_dir = os.path.join(args.out, "diff-seed-same"), os.path.join(args.out, "diff-seed-changed")
    doc12_same, _, p12_same = crawl(args.apk, ds_same_dir, *two, "--seed", wpath(rs_dir), "--only", old[0] if old else "none",
                                    "--diff-seed", "--setting", "global:apksim_slot_seed=" + "long-" * 120)
    same_differs = [p for p in doc12_same.get("compare", []) if p["status"] != "same"]
    check(p12_same.returncode == 0 and not same_differs,
          "--diff-seed on an unchanged --only screen passes with no differences",
          f"exit {p12_same.returncode}, {same_differs[:2]}")
    doc12_diff, _, p12_diff = crawl(args.apk, ds_diff_dir, *two, "--seed", wpath(rs_dir), "--only", old[0] if old else "none",
                                    "--diff-seed", "--events", "--setting", "global:apksim_slot_seed=changed-in-pr")
    diff_changed = [p for p in doc12_diff.get("compare", []) if p["status"] != "same"]
    cmp_evs = [json.loads(l) for l in p12_diff.stdout.splitlines() if l.startswith("{") and '"event": "compare"' in l]
    diff_maps = [d.get("map") for s in doc12_diff.get("diffs", {}).values() for d in s.values() if d.get("map")]
    check(p12_diff.returncode == 1 and len(diff_changed) == 1 and bool(diff_changed[0].get("ui_diff"))
          and bool(diff_maps) and os.path.isfile(os.path.join(ds_diff_dir, diff_maps[0])),
          "--diff-seed detects a changed --only screen with semantic ui_diff and a pixel diff image",
          f"exit {p12_diff.returncode}, changed={diff_changed}, maps={diff_maps}")

    walked_ids = {s["id"] for s in doc12_diff["screens"] if s["origin"] == "walked"}
    check({e["id"] for e in cmp_evs} == walked_ids and any(e["status"] == "changed" and e.get("diffs") and e.get("ui_diff") for e in cmp_evs),
          "--diff-seed --events prints a compare line per walked screen, with its ui_diff and pixel diffs", str(cmp_evs[:2]))

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

    # 11: replays from the data the app started with. The slot screen's structure does not change when Write is
    # tapped (only its texts), so the leak shows in its capture: after a walk that tapped Write it must be the same
    # picture as from a walk that tapped nothing (depth 0), and with --keep-app-state it is not.
    slot_walk = ["--start", "apksim://slot", "-j", "1", "--matrix", "appearance=light"]

    def root_capture(doc, out):
        root = [s for s in doc["screens"] if s["path"] == {"start": "apksim://slot", "steps": []}]
        caps = list((root[0].get("captures") or {}).values()) if root else []
        if not caps:
            return None
        with open(os.path.join(out, caps[0]), "rb") as f:
            return hashlib.sha256(f.read()).hexdigest()
    ref_dir, state_dir, kept_dir = (os.path.join(args.out, n) for n in ("state-ref", "state", "state-kept"))
    doc18, _, _ = crawl(args.apk, ref_dir, *slot_walk, "--max-depth", "0")
    doc16, secs_h, _ = crawl(args.apk, state_dir, *slot_walk, "--max-depth", "1")
    doc17, _, _ = crawl(args.apk, kept_dir, *slot_walk, "--max-depth", "1", "--keep-app-state")
    ref, fresh, kept = root_capture(doc18, ref_dir), root_capture(doc16, state_dir), root_capture(doc17, kept_dir)
    why = doc16.get("relaunch_files", {})
    bad = [f for f in doc16["findings"] if f["kind"] in ("replay", "capture", "flaky")]
    check(ref is not None and fresh == ref and not bad and doc16.get("relaunches", 0) >= 1
          and ("settings.properties" in why or "sdcard/apksim-slot-check.txt" in why),
          "a tap that writes the app's data leaves later captures alone (the instance is launched again)",
          f"same picture as a walk that tapped nothing: {fresh == ref}, relaunches {doc16.get('relaunches')} ({why}), "
          f"findings {[f['detail'][:80] for f in bad]}")
    check(kept is not None and kept != ref and doc17.get("relaunches") == 0,
          "with --keep-app-state the slot screen is captured as Write left it (the check sees the leak)",
          f"differs: {kept != ref}, relaunches {doc17.get('relaunches')}")

    # 13: a walk stopped (SIGTERM, as Greenroom's Stop) once it is exploring depth 1 writes what it has; continued
    # with --seed and its unexplored ids, it ends with walk 1's graph.
    stop_dir, cont_dir = os.path.join(args.out, "stopped"), os.path.join(args.out, "continued")
    try:
        os.remove(os.path.join(stop_dir, "crawl.json"))
    except OSError:
        pass
    if DOCKER:
        image, platform, work = DOCKER
        cmd = ["docker", "run", "--rm", "--platform", platform, "-v", f"{work}:/work", "--entrypoint", "crawl.py", image,
               "/work/app.apk", "--out", "/work/" + os.path.relpath(stop_dir, work)]
    else:
        cmd = [sys.executable, CRAWL, args.apk, "--out", stop_dir]
    proc = subprocess.Popen(cmd + [*starts, *matrix, *opts], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    t_stop, kids = None, []
    for line in proc.stdout:
        if line.startswith("[new] depth 1:"):
            time.sleep(3)
            kids = [] if DOCKER else descendants(proc.pid)  # the instances: none may outlive the stop
            t_stop = time.time()
            proc.send_signal(signal.SIGTERM)
            break
    rest = proc.stdout.read()
    code = proc.wait()
    took = time.time() - t_stop if t_stop else None
    try:
        with open(os.path.join(stop_dir, "crawl.json")) as f:
            sdoc = json.load(f)
    except (OSError, ValueError):
        sdoc = {}
    ids = {x["id"] for x in sdoc.get("screens", [])}
    time.sleep(1)
    alive = [k for k in kids if subprocess.run(["kill", "-0", str(k)], capture_output=True).returncode == 0]
    check(DOCKER or t_stop is None or (kids and not alive), "no process the walk started outlives its stop",
          f"{len(kids)} started, still running: {alive}")
    check(t_stop is not None and code == 143 and sdoc.get("stopped") is True and "stopped" in sdoc.get("cut_by", [])
          and sdoc.get("unexplored") and set(sdoc["unexplored"]) <= ids
          and any("stopped (SIGTERM)" in x for x in sdoc.get("incomplete", [])) and took is not None and took < 4,
          "a stopped walk writes crawl.json with its screens, `unexplored` ids and `stopped`, within 4 s",
          f"exit {code}, stopped {sdoc.get('stopped')}, cut_by {sdoc.get('cut_by')}, unexplored "
          f"{len(sdoc.get('unexplored') or [])} of {len(ids)}, {took and round(took, 1)} s; {rest[-300:]}")
    if sdoc.get("unexplored"):
        only_file = os.path.join(args.out, "stopped-unexplored.txt")  # stop_dir is the container's (root) with --docker
        with open(only_file, "w") as f:
            f.write("\n".join(sdoc["unexplored"]) + "\n")
        doc20, _, _ = crawl(args.apk, cont_dir, *matrix, *opts, "--seed", wpath(stop_dir), "--only-file", wpath(only_file))
        sa, sc = shape(doc), shape(doc20)
        check(set(sa[0]) == set(sc[0]) and set(sa[1]) == set(sc[1])
              and set(doc20.get("unexplored", ["?"])) == set(doc.get("unexplored", [])),
              "the stopped walk continued (--seed, --only-file its unexplored ids) ends with walk 1's screens and transitions",
              f"screens only in walk 1 {sorted(set(sa[0]) - set(sc[0]))[:3]}, only in the continuation "
              f"{sorted(set(sc[0]) - set(sa[0]))[:3]}, edges differing {len(set(sa[1]) ^ set(sc[1]))}, "
              f"still unexplored {doc20.get('unexplored', [])[:4]}")
        caps = [(x["id"], c) for x in doc20["screens"] for c in doc20["combos"]
                if not os.path.isfile(os.path.join(cont_dir, x["captures"].get(c, "missing")))]
        check(not caps, "every screen of the continued walk has its captures (the stopped walk took none)", str(caps[:3]))

    # 12: --keep-data with two instances: each runs on its own copy of a prepared slot (Write tapped in it beforehand),
    # so the slot screen shows the prepared values; the prepared slot is not written and no copy is left. Mac only (a
    # prepared slot in the image would need a launch in the walk's own container).
    secs_k = 0
    if not DOCKER:
        state = os.environ.get("APKSIM_HOME") or (os.path.expanduser("~/.apksim") if os.path.exists(
            os.path.join(ROOT, "work", "release")) else os.path.join(ROOT, "work"))
        data = os.path.join(state, "cache", "data", "dev.apksim.composeapp")
        prepared = os.path.join(data, "ckprep-0")
        prep = subprocess.Popen([os.path.join(ROOT, "apksim"), "--headless", "--fresh", "--control", "7487",
                                 "--data-slots", "ckprep", args.apk], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        drive = [sys.executable, os.path.join(ROOT, "scripts", "drive.py"), "7487"]
        for _ in range(90):
            if "pong" in subprocess.run(drive + ["ping"], capture_output=True, text=True).stdout:
                break
            time.sleep(1)
        subprocess.run(drive + ["wait 1500", "open apksim://slot", "wait 2000", "tap-text Write", "wait 1500", "quit"],
                       capture_output=True)
        prep.wait(timeout=60)

        def tree_sum():
            h = hashlib.sha256()
            for root in (prepared, prepared + ".device"):
                for d, dirs, files in sorted(os.walk(root)):
                    dirs.sort()
                    for n in sorted(files):
                        if n != ".lock":
                            with open(os.path.join(d, n), "rb") as f:
                                h.update(os.path.relpath(os.path.join(d, n), data).encode() + f.read())
            return h.hexdigest()
        before = tree_sum()
        kd_dir = os.path.join(args.out, "keep-data")
        doc19, secs_k, _ = crawl(args.apk, kd_dir, "--start", "apksim://slot", "-j", "2", "--matrix", "appearance=light",
                                 "--max-depth", "1", "--keep-data", "--", "--data-slots", "ckprep")
        seen = root_capture(doc19, kd_dir)
        bad = [f for f in doc19["findings"] if f["kind"] in ("replay", "capture", "flaky")]
        left = [n for n in os.listdir(data) if re.match(r"k[0-9a-f]{8}\d+-0(\.device)?$", n)]
        logs = os.listdir(os.path.join(kd_dir, "logs"))
        check(seen is not None and seen != ref and not bad and tree_sum() == before and not left
              and any("instance-1." in n for n in logs),
              "--keep-data -j 2: both instances start from copies of the prepared slot, which is left as it was",
              f"prepared values shown: {seen != ref}, findings {[f['detail'][:80] for f in bad]}, prepared slot unchanged: "
              f"{tree_sum() == before}, copies left {left}, second instance used: {any('instance-1.' in n for n in logs)}")
        for d in (prepared, prepared + ".device"):
            shutil.rmtree(d, ignore_errors=True)

    # 14: an app behind a sign-in form (apksim://login, the test app's own test values saved as its autofill sign-in).
    # Without --autofill the walk finds the form alone and warns that it stays signed out; with it every launch signs in
    # before the walk reads the screen, so it finds the signed-in screen and the account screen behind it, with no
    # relaunch for the sign-in's write, and the warning does not fire. crawl.json records the zone (UTC).
    signin = "Username=tester\nPassword=apksim-test\nsubmit=Sign in\n"
    wrong = "not-the-password-5c1d"  # a made-up value, looked for in what the refused walk wrote
    secs_s = 0
    for extra in (["--freeze-network"], ["--", "--network", "record", os.path.join(args.out, "refused.har")],
                  ["--", "--rec", "input,net"], ["--base-option", "--rec all"]):
        p = subprocess.run([sys.executable, CRAWL, args.apk, "--out", os.path.join(args.out, "refused"), "--autofill", *extra],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
        check(p.returncode == 2 and "--autofill cannot be used with" in p.stdout,
              f"--autofill with {' '.join(extra)[:40]} is refused", f"exit {p.returncode}: {p.stdout[-200:]!r}")
    frozen = os.path.join(args.out, "frozen-seed")
    os.makedirs(frozen, exist_ok=True)
    with open(os.path.join(frozen, "crawl.json"), "w") as f:
        json.dump({"screens": [], "network": {"recording": "network.har"}}, f)
    p = subprocess.run([sys.executable, CRAWL, args.apk, "--out", os.path.join(args.out, "refused"), "--autofill", "--seed", frozen,
                        "--only-file", os.devnull], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
    check(p.returncode == 2 and "--autofill cannot be used with --seed of a --freeze-network walk" in p.stdout,
          "--autofill with --seed of a frozen walk is refused", f"exit {p.returncode}: {p.stdout[-200:]!r}")
    # A SIGTERM or hangup meanwhile still removes the sign-in this check saved.
    handlers = {s: signal.signal(s, lambda n, _f: sys.exit(128 + n)) for s in (signal.SIGTERM, signal.SIGHUP)}
    if DOCKER:
        saved = os.path.join(args.out, "autofill.properties")
        with open(saved, "w") as f:
            f.write(signin)
        os.chmod(saved, 0o644)  # read by the image's user; the test app's own values
        mounts = [(saved, "/opt/apksim/work/autofill/dev.apksim.composeapp.properties")]
        restore = lambda: None  # noqa: E731
    else:
        state = os.environ.get("APKSIM_HOME") or (os.path.expanduser("~/.apksim") if os.path.exists(
            os.path.join(ROOT, "work", "release")) else os.path.join(ROOT, "work"))
        saved = os.path.join(state, "autofill", "dev.apksim.composeapp.properties")
        os.makedirs(os.path.dirname(saved), exist_ok=True)
        mounts = []
        restore = lambda: os.remove(saved)  # noqa: E731
        if os.path.exists(saved):  # someone's own sign-in for the test app: left alone, and this step with it
            print(f"skip 14: {saved} exists (a sign-in saved for the test app); move it away to run the sign-in walks")
            saved = None
        else:
            fd = os.open(saved, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(signin)
    if saved:
        try:
            login = ["--start", "apksim://login", "--max-depth", "2", "-j", "1", "--matrix", "appearance=light"]
            out_d = os.path.join(args.out, "signed-out")
            doc_o, secs_o, p_o = crawl(args.apk, out_d, *login, mounts=mounts)
            out_i = os.path.join(args.out, "signed-in")
            doc_i, secs_s, p_i = crawl(args.apk, out_i, *login, "--autofill", mounts=mounts)
            with open(saved, "w") as f:
                f.write(f"Username=tester\nPassword={wrong}\nsubmit=Sign in\n")
            out_w = os.path.join(args.out, "signin-refused")
            shutil.rmtree(out_w, ignore_errors=True)
            doc_w, _secs, p_w = crawl(args.apk, out_w, *login, "--autofill", expect=(2,), mounts=mounts)
        finally:
            restore()
        files = [os.path.join(d, n) for d, _dirs, names in os.walk(out_w) for n in names]
        leaked = []
        for path in files:
            with open(path, "rb") as f:
                if wrong.encode() in f.read():
                    leaked.append(os.path.relpath(path, out_w))
        pictures = [os.path.relpath(p, out_w) for p in files if p.endswith(".png")]
        check("the sign-in did not succeed" in p_w.stdout and doc_w.get("stopped") and not doc_w["screens"] and not pictures
              and not leaked,
              "a refused sign-in stops the walk: no screen read or captured, the password in none of its files",
              f"screens {len(doc_w['screens'])}, pictures {pictures}, leaked {leaked}, {p_w.stdout[-300:]!r}")
        acts_o = sorted({s["activity"].rsplit(".", 1)[-1] for s in doc_o["screens"]})
        acts_i = sorted({s["activity"].rsplit(".", 1)[-1] for s in doc_i["screens"]})
        texts_i = " ".join(t for s in doc_i["screens"] for t in s.get("texts", []))
        check(acts_o == ["LoginActivity"] and doc_o["signin"]["warnings"] and "password field" in p_o.stdout
              and not doc_o["signin"]["autofill"],
              "signed out: the walk finds only the sign-in form and warns that its start screen has a password field",
              f"activities {acts_o}, warnings {doc_o['signin']['warnings']}")
        check("LoginAccountActivity" in acts_i and "Signed in as tester" in texts_i and not doc_i["signin"]["warnings"]
              and any(k.startswith("done: filled Username, Password; pressed Sign in") for k in doc_i["signin"]["states"])
              and doc_i["relaunches"] == 0,
              "--autofill: each launch signs in before the walk reads it; the account screen behind the form is found",
              f"activities {acts_i}, signin {doc_i['signin']}, relaunches {doc_i['relaunches']}, texts {texts_i[:120]!r}")
        check(doc_o.get("timezone") == "UTC" and doc_i.get("timezone") == "UTC", "crawl.json records the zone the walk ran in (UTC)",
              f"{doc_o.get('timezone')!r}, {doc_i.get('timezone')!r}")
        bad = [f["detail"][:80] for d in (doc_o, doc_i) for f in d["findings"] if f["kind"] in ("replay", "capture", "flaky", "crash")]
        check(not bad, "the sign-in walks replay and capture every screen", str(bad))

    for s, h in handlers.items():
        signal.signal(s, h)

    # 15: unstable screens (docs/crawl.md "Unstable screens"). apksim://unstable?mode=tick counts every 100 ms (two reads
    # never agree), mode=fail fails a request as it opens; home is still. A seed walk records each as `unstable` with its
    # reason, and the same build walked against it with --diff-seed reports none of them changed, new or gone.
    u_seed, u_diff = os.path.join(args.out, "unstable-seed"), os.path.join(args.out, "unstable-diff")
    tick_u, fail_u = "apksim://unstable?mode=tick", "apksim://unstable?mode=fail"
    ustarts = ["--start", tick_u, "--start", fail_u, "--start", "apksim://composeapp/home", "--max-depth", "1",
               "--matrix", "appearance=light", "-j", str(min(args.j, 3))]
    doc_us, secs_u, _ = crawl(args.apk, u_seed, *ustarts)
    by_start = {s["path"].get("start"): s for s in doc_us["screens"] if not s["path"]["steps"]}
    tick_s, fail_s, home_s = (by_start.get(u, {}) for u in (tick_u, fail_u, "apksim://composeapp/home"))
    check("content changed" in (tick_s.get("unstable") or ""), "a screen whose content changes between two reads is unstable",
          str(tick_s.get("unstable")))
    check("failed while it was reached" in (fail_s.get("unstable") or "") and "127.0.0.1:9" in fail_s.get("unstable", ""),
          "a screen reached while a request failed is unstable, the request named", str(fail_s.get("unstable")))
    check(bool(home_s) and not home_s.get("unstable"), "a still screen is not", str(home_s.get("unstable")))
    ids = [s["id"] for s in (tick_s, fail_s, home_s) if s]
    doc_ud, _, p_ud = crawl(args.apk, u_diff, "--matrix", "appearance=light", "-j", str(min(args.j, 3)), "--seed", wpath(u_seed),
                            "--only", ",".join(ids), "--diff-seed", expect=(0, 1))
    noisy = [(p["status"], p["id"] or p["base"]) for p in doc_ud.get("compare", []) if p["status"] in ("changed", "new", "gone")]
    left = {u["id"] or u["base"] for u in doc_ud.get("unstable", [])}
    check(not noisy and p_ud.returncode == 0 and left <= set(ids) - {home_s.get("id")}
          and all(p["status"] == "same" for p in doc_ud.get("compare", []) if p["id"] == home_s.get("id")),
          "the same build against that seed: no unstable screen is changed, new or gone, and the run passes",
          f"exit {p_ud.returncode}, {noisy}, unstable {sorted(left)}")
    check(tick_s.get("id") in left, "the ticking screen, whose text differs from the seed's, is listed as unstable",
          str(doc_ud.get("unstable")))

    print(f"timings: walk+captures {secs_a:.0f} s ({len(screens)} screens, {len(doc['edges'])} transitions), repeat walk {secs_b:.0f} s, "
          f"compare {secs_c:.0f} s, prepared {secs_d:.0f} s, sweep {secs_e:.0f} s ({secs_f:.0f} s with an allow-list), "
          f"seeded walk of one screen {secs_g:.0f} s, walk with --record-deps {secs_deps:.0f} s (without {secs_plain:.0f} s), "
          f"slot walk with a relaunch {secs_h:.0f} s, kept-data walk on two instances {secs_k:.0f} s, "
          f"signed-in walk {secs_s:.0f} s, unstable seed walk {secs_u:.0f} s")
    if failures:
        print(f"crawl-check: {len(failures)} check(s) failed", file=sys.stderr)
        sys.exit(1)
    print("crawl-check: all passed")


main()
