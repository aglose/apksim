#!/usr/bin/env python3
"""Checks --diff-seed's gone screens and `edges` section on a synthetic graph (no app, no instance)."""
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
print("ok")
