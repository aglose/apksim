#!/usr/bin/env python3
"""Checks --diff-seed's gone screens, `edges` section and unstable screens on a synthetic graph (no app, no instance)."""
import importlib.util
import os
import sys
import types

spec = importlib.util.spec_from_file_location("crawl", os.path.join(os.path.dirname(os.path.abspath(__file__)), "crawl.py"))
crawl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(crawl)


def scr(i, origin, parent=None, **kw):
    return dict(id=i, origin=origin, parent=parent, **kw)


def edge(f, a, t):
    return {"from": f, "action": a, "to": t}


# Seed: A (start) -> B -> D, A -> C, A -> E (unexplored by the seed), E -> F.  B and A are walked again.
seed = {"screens": [{"id": x} for x in "ABCDEF"], "unexplored": ["E"],
        "edges": [edge("A", "b", "B"), edge("A", "c", "C"), edge("A", "e", "E"), edge("B", "d", "D"), edge("E", "f", "F")]}
# New: B lost its control to D; A lost "c" and gained "g" to a new screen G; B's "x" now goes to C.
new = types.SimpleNamespace(
    explored=None, partial=set(),
    screens={"A": scr("A", "walked"), "B": scr("B", "walked", "A"), "C": scr("C", "carried", "A"),
             "D": scr("D", "carried", "B"), "E": scr("E", "carried", "A"), "F": scr("F", "carried", "E"),
             "G": scr("G", "walked", "A")},
    edges=[edge("A", "b", "B"), edge("A", "e", "E"), edge("A", "g", "G"), edge("B", "x", "C"), edge("E", "f", "F")])
lost = crawl.lost_carried(new, seed)
assert lost == {"D": "gone"}, lost
# C is reached through B's new "x" edge, F through E.
ed = crawl.compare_edges(new, seed)
assert [e["action"] for e in ed["added"]] == ["g", "x"], ed
assert [(e["action"], e["base_to"]) for e in ed["removed"]] == [("c", "C"), ("d", "D")], ed
assert ed["retargeted"] == [], ed
# E's only way in was A, walked again, which no longer leads to it: gone. F's was E, which the seed never explored.
new.edges = [e for e in new.edges if e["to"] != "E"] + [edge("A", "b2", "B")]
lost = crawl.lost_carried(new, seed)
assert lost.get("E") == "gone" and lost.get("F") == "unknown", lost  # the seed never explored E: its way on to F is unknown
seed["unexplored"] = []
lost = crawl.lost_carried(new, seed)
assert lost.get("E") == "gone" and lost.get("F") == "gone" and lost.get("D") == "gone", lost
# A retarget.
new.edges.append(edge("B", "y", "G")); seed["edges"].append(edge("B", "y", "C"))
assert crawl.compare_edges(new, seed)["retargeted"] == [{"from": "B", "action": "y", "to": "G", "base_to": "C"}]
# A walked screen whose walk was cut short says nothing about what it led to.
new.explored = {"A", "G"}  # B was not explored
assert crawl.lost_carried(new, seed).get("D") == "unknown"
new.explored = {"A", "B", "G"}; new.partial = {"B"}
assert crawl.lost_carried(new, seed).get("D") == "unknown"
# --diff-controls none never reads every control: nothing is gone for sure, and removed edges are not listed.
new.partial = set(); new.diff_controls = "none"
assert set(crawl.lost_carried(new, seed).values()) == {"unknown"}
ed = crawl.compare_edges(new, seed)
assert ed["removed"] == [] and ed.get("note"), ed
# A transition a budget kept from being followed: what that screen led to is unknown.
new.diff_controls = "changed"; new.edges.append(edge("B", "z", None))
assert crawl.lost_carried(new, seed).get("D") == "unknown"

# --diff-seed variant protection: a PR change on a carried child screen must NOT be silently collapsed
# into the old carried screen, while a known seed variant alias or non-protected variant still collapses.
import re, threading
ex = object.__new__(crawl.Explorer)
ex.dedup_variants = True
ex.args = types.SimpleNamespace(diff_seed=True)
ex.lock = threading.Lock()
ex.skip_re = re.compile(crawl.DEFAULT_SKIP, re.I)
ex.screens = {"P": scr("P", "walked"), "C": scr("C", "carried", "P")}
ex.order = ["P", "C"]
ex.variant_aliases = {"V_OLD": "C"}
ex.seed_edges_by_from = {"P": [{"from": "P", "cmd": "tap-text Child", "to": "C", "note": ""},
                               {"from": "P", "cmd": "tap-text Row2", "to": "C", "note": "variant of C"}]}
def fake_recs(labels):
    return [crawl.Rec(0, "android.view.View", "", "v", (0, 0, 800, 1600), "", "", "")] + [
        crawl.Rec(1, "android.widget.TextView", "", "vc", (0, i * 40, 400, i * 40 + 30), t, "", "")
        for i, t in enumerate(labels)
    ]
base_labels = [f"Setting option {w}" for w in ("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta", "Iota", "Kappa", "Lambda", "Mu")]
ex.variant_sigs = {"C": ex._variant_sig("MainActivity", fake_recs(base_labels), "P")}
# PR adds one new label on C -> new id C_CHANGED reached via the canonical edge "tap-text Child" -> MUST NOT collapse!
changed_obs = {"id": "C_CHANGED", "activity": "MainActivity", "recs": fake_recs(base_labels + ["New PR toggle"])}
assert ex.find_variant_match("P", changed_obs, edge_cmd="tap-text Child") is None, "PR change on carried child was hidden!"
# Same obs reached via a known variant edge ("tap-text Row2") -> still collapses to C!
assert ex.find_variant_match("P", changed_obs, edge_cmd="tap-text Row2") == "C"
# Known seed variant alias V_OLD via variant edge -> collapses to C!
assert ex.find_variant_match("P", {"id": "V_OLD", "activity": "MainActivity", "recs": []}, edge_cmd="tap-text Row2") == "C"

# Unstable screens (docs/crawl.md "Unstable screens"): a pair whose screen is unstable in the seed or in this walk is
# left out of changed/new/gone, listed in crawl.json's `unstable`, and does not fail the exit status.
import tempfile
out_dir = tempfile.mkdtemp(prefix="crawl-diff-seed-check-")
seed2 = {"screens": [
    {"id": "H", "title": "Home", "texts": ["Home"], "actions": [], "parent": None},
    {"id": "L", "title": "List", "texts": ["Item 1"], "actions": [], "parent": "H"},  # walked again, unstable in the seed
    {"id": "E", "title": "Error", "texts": ["Could not load"], "actions": [], "parent": "H",
     "unstable": "1 request of the app failed while it was reached"},  # the seed's error state, gone now
    {"id": "T", "title": "Tick", "texts": ["tick 1"], "actions": [], "parent": "H"},  # walked again, unstable now
    {"id": "S", "title": "Same", "texts": ["Same"], "actions": [], "parent": "H"},
], "edges": [edge("H", "l", "L"), edge("H", "e", "E"), edge("H", "t", "T"), edge("H", "s", "S")], "unexplored": []}
seed2["screens"][1]["unstable"] = "its content changed when read again 200 ms later: 'Item 1' gone"
events2 = []
new2 = types.SimpleNamespace(
    order=["H", "L", "E", "T", "S", "N"], explored={"H", "L", "T", "N"}, partial=set(), instances=[],
    screens={"H": dict(seed2["screens"][0], origin="walked", captures={}),
             "L": dict(seed2["screens"][1], texts=["Item 1", "Item 2"], origin="walked", captures={}, unstable=None),
             "E": dict(seed2["screens"][2], origin="carried", captures={}),
             "T": dict(seed2["screens"][3], texts=["tick 2"], origin="walked", captures={},
                       unstable="its content changed when read again 200 ms later: 'tick 2' gone; 'tick 3' appeared"),
             "S": dict(seed2["screens"][4], origin="carried", captures={}),
             "N": {"id": "N", "title": "Oops", "texts": ["Oops"], "actions": [], "parent": "H", "origin": "walked",
                   "captures": {}, "unstable": "2 requests of the app failed while it was reached"}},
    edges=[edge("H", "l", "L"), edge("H", "t", "T"), edge("H", "s", "S"), edge("H", "n", "N")],
    event=lambda kind, **f: events2.append((kind, f)))
args2 = crawl.parse(["app.apk", "--out", out_dir, "--seed", out_dir, "--only", "H,L,T", "--diff-seed"])
doc2 = {"combos": [], "findings": []}
sc2 = crawl.SeedCompare(new2, seed2, out_dir, doc2, args2, out_dir)
sc2.finish(None)
st = {p["id"] or p["base"]: p for p in doc2["compare"]}
assert st["L"]["status"] == "unstable" and st["L"]["was"] == "changed" and "in the seed" in st["L"]["why"], st["L"]
assert st["T"]["status"] == "unstable" and st["T"]["was"] == "changed" and "in this walk" in st["T"]["why"], st["T"]
assert st["E"]["status"] == "unstable" and st["E"]["was"] == "gone" and st["E"]["id"] is None, st["E"]
assert st["N"]["status"] == "unstable" and st["N"]["was"] == "new", st["N"]
assert st["S"]["status"] == "same" and st["H"]["status"] == "same", (st["S"], st["H"])
assert not [p for p in doc2["compare"] if p["status"] in ("changed", "gone", "new")], doc2["compare"]
assert sorted(u["base"] or u["id"] for u in doc2["unstable"]) == ["E", "L", "N", "T"], doc2["unstable"]
ev2 = {f["id"]: f for k, f in events2 if k == "compare"}
assert ev2["L"]["status"] == "unstable" and ev2["N"]["status"] == "unstable" and ev2["N"]["was"] == "new", ev2
# Without `unstable` anywhere, the same walk reports them as before.
for x in seed2["screens"]:
    x.pop("unstable", None)
for x in new2.screens.values():
    x.pop("unstable", None)
doc3 = {"combos": [], "findings": []}
crawl.SeedCompare(new2, seed2, out_dir, doc3, args2, out_dir).finish(None)
st3 = {p["id"] or p["base"]: p["status"] for p in doc3["compare"]}
assert st3 == {"H": "same", "L": "changed", "E": "gone", "T": "changed", "S": "same", "N": "new"}, st3
assert doc3["unstable"] == []


# Explorer.instability on a stand-in instance: a second read that differs, and failed requests during the reach.
class Inst:
    def __init__(self, reads, failures):
        self.reads, self.failures, self.failures_base = list(reads), failures, 3
    def read_ui(self):
        return "dev.App", self.reads.pop(0)
    def net_failures(self):
        return self.failures
recs_a = fake_recs(["Hello", "tick 1"])
sid = crawl.structure("dev.App", recs_a)[0]
here = {"id": sid, "activity": "dev.App", "recs": recs_a, "at": 0}
ex.args = types.SimpleNamespace(stable_ms=0)
assert ex.instability(Inst([fake_recs(["Hello", "tick 1"])], (3, "")), here) is None
why = ex.instability(Inst([fake_recs(["Hello", "tick 2"])], (3, "")), here)
assert why and "'tick 1' gone" in why and "'tick 2' appeared" in why, why
why = ex.instability(Inst([fake_recs(["Hello", "tick 1"])], (5, "x.invalid:443 connect failed")), here)
assert why and why.startswith("2 requests of the app failed") and "x.invalid" in why, why
# A clock string is not content that changed (screen_sig strips HH:MM).
here_c = dict(here, recs=fake_recs(["Hello", "9:41"]))
assert ex.instability(Inst([fake_recs(["Hello", "9:42"])], None), here_c) is None
print("ok")
