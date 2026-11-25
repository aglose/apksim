#!/usr/bin/env python3
"""Checks how scripts/crawl.py makes a screen's id (canon, structure), on node trees written here (no apksim, a second).

- A detail screen per item whose only difference is a label's length (a place's region, left-aligned and as wide as its
  text) is one screen: the label's centre moving into the next third of the width does not make another.
- Layouts that differ are still told apart: a control in another third, a card more, another class.
- A label inside a row of a list is placed by its row, so rows that differ only in text still fold into one.
- SCREEN_IDS 1 makes ids as before (the label's own centre), and a seed without "screen_ids" walks with 1, one with 2
  with 2, so a seeded walk's ids match its seed's; a stopped or finished walk writes "screen_ids".

Exit status 1 on any failed expectation.
"""
import json, os, sys, tempfile, types

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crawl  # noqa: E402

failures = []


def check(ok, what, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (f": {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        failures.append(what)


def rec(depth, cls, box, text="", flags="v", rid=""):
    return crawl.Rec(depth, cls, rid, flags, box, text, "", "")


def place(city, region, region_right, extra=(), button_x=(700, 800)):
    """A place's page, 822 wide: a close button, the title block (name and region, left-aligned), a card, tabs."""
    return [rec(0, "FrameLayout", (0, 0, 822, 1600)),
            rec(1, "Button", (20, 60, 100, 140), flags="vc", rid="close"),
            rec(1, "View", (116, 60, 618, 161), flags="v", rid="weather_place_title"),
            rec(2, "TextView", (132, 68, 132 + 12 * len(city), 121), city),
            rec(2, "TextView", (132, 121, region_right, 153), region),
            rec(1, "View", (20, 300, 802, 600), rid="card"),
            rec(2, "TextView", (40, 320, 400, 360), "Light rain")] + list(extra) + [
            rec(1, "Button", (button_x[0], 1450, button_x[1], 1550), "Ski", flags="vc")]


def sid(recs):
    return crawl.structure("dev.example.MainActivity", recs)[0]


def main():
    crawl.SCREEN_IDS = 2
    miami = place("Miami", "Florida, United States", 389)
    boston = place("Boston", "Massachusetts, United States", 483)
    check(sid(miami) == sid(boston), "Boston and Miami, whose regions differ in length, are one screen", f"{sid(miami)} {sid(boston)}")
    alert = [rec(1, "View", (20, 620, 802, 700), rid="alert"), rec(2, "TextView", (40, 630, 300, 690), "Flood watch")]
    check(sid(place("Miami", "Florida, United States", 389, alert)) != sid(miami), "a card more is another screen")
    check(sid(place("Miami", "Florida, United States", 389, button_x=(20, 120))) != sid(miami),
          "a control in another third is another screen")
    other = [r if r.rid != "card" else rec(1, "ScrollView", r.box, flags="vs", rid="card") for r in miami]
    check(sid(other) != sid(miami), "another class is another screen")

    def lst(names):
        recs = [rec(0, "FrameLayout", (0, 0, 822, 1600)), rec(1, "RecyclerView", (0, 0, 822, 1600), flags="vs")]
        for k, n in enumerate(names):
            top = 100 + 120 * k
            recs += [rec(2, "LinearLayout", (0, top, 822, top + 110), flags="vc"), rec(3, "TextView", (20, top, 20 + 25 * len(n), top + 50), n)]
        return recs
    check(sid(lst(["Paris", "San Francisco", "Rio"])) == sid(lst(["Oslo", "Lima", "Ho Chi Minh City"])),
          "a list whose rows' labels differ in length is one screen")

    crawl.SCREEN_IDS = 1
    check(sid(miami) != sid(boston), "SCREEN_IDS 1 still places a label by its own centre (ids as walks before it made)")

    # load_seed: the seed's way of making ids
    for seed_doc, want in (({}, 1), ({"screen_ids": 2}, 2), ({"screen_ids": 1}, 1)):
        d = tempfile.mkdtemp(prefix="crawl-ids-check-")
        doc = {"screens": [{"id": "MainActivity-00000000"}], "combos": [], "screen_titles": False, **seed_doc}
        with open(os.path.join(d, "crawl.json"), "w") as f:
            json.dump(doc, f)
        crawl.SCREEN_IDS = 2
        args = types.SimpleNamespace(seed=d, only_ids=["MainActivity-00000000"], screen_titles=False)
        crawl.load_seed(args, d, [])
        check(crawl.SCREEN_IDS == want, f"a seed with {seed_doc or 'no screen_ids'} is walked with SCREEN_IDS {want}",
              str(crawl.SCREEN_IDS))

    src = open(crawl.__file__).read()
    check(src.count('doc["screen_ids"] = SCREEN_IDS') == 2, "a finished and a stopped walk both write screen_ids")
    if failures:
        print(f"{len(failures)} check(s) failed")
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
