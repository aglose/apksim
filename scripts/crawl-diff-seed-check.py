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
print("ok")
