#!/usr/bin/env python3
"""Checks how scripts/crawl.py walks screens whose replay is not exact, against a stand-in app (no apksim, a second).

The stand-in plays a live-data app: its Home has an "Alerts" button that does nothing until its data has loaded (a
tap-text step that fails once), a "Weather" screen whose structure changes at every visit (a replay never reaches the
structure first seen), a "Refresh" there that shows Weather again with other data, and a "Today 7°" button whose label
changes once it has been used (a stored tap-text step that never matches again). "News 5" changes its label once used
and moves down a row, a "Sign out" taking its old place; "Feed" leads to Feed once and to Alerts after. "Shop" leads to
Shop twice (found, then listed) and to Alerts after, with eight controls. The walk must:

- reach Alerts and its detail: a failed step is tried again after a longer wait;
- mark Weather flaky and still try its controls, finding Forecast and its day, and Resorts and its resort; Refresh
  leads back to Weather, not to a new screen;
- mark Today flaky and reach it by the button's coordinates, finding Hourly;
- never tap Sign out at News' old coordinates (News is flaky and explored no further);
- not take Alerts for Feed (Feed is flaky and has no transitions);
- stop replaying Shop's path once it has missed crawl.ARRIVE_MISSES times in a row: its other controls are FAILED edges
  marked not tried, not a launch each;
- capture the flaky Weather where its path leads now.

Exit status 1 on any failed expectation.
"""
import os, sys, tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crawl  # noqa: E402

failures = []


def check(ok, what, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (f": {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        failures.append(what)


class StandIn(crawl.Instance):
    """crawl.Instance with the control channel answered by a model of an app, one activity."""

    def __init__(self, ctx):
        self.i, self.ctx, self.crashes = 0, ctx, ctx["crashes"]
        self.screen = "Home"
        self.settles = 0          # settles since the last restart: Alerts loads after two
        self.weather_visits = 0
        self.today_used = False   # Home's "Today 7°" reads "Today 9°" once Today has been shown
        self.news_used = False    # Home's "News 5" reads "News 6" a row lower once News has been shown, Sign out above
        self.feed_used = False    # Home's "Feed" leads to Alerts once Feed has been shown
        self.shop_visits = 0      # Home's "Shop" leads to Alerts once Shop has been shown twice
        self.shop_misses = 0      # taps on Shop that led to Alerts
        self.signed_out = 0
        self.pending_shop = False
        self.stage = tempfile.mkdtemp(prefix="crawl-replay-check-stage-")
        self.log = os.devnull
        self.baseline = self.slot = self.proc = self.file = self.sock = None

    # nothing to launch, configure or relaunch
    def ensure(self):
        pass

    def fresh_start(self):
        pass

    def configure(self, combo):
        pass

    def from_launch_data(self):
        return False

    def quit(self):
        pass

    def buttons(self):
        """(label, target, works) of the current screen, top to bottom."""
        s = self.screen
        if s == "Home":
            news = [("Sign out", "SignedOut", True), ("News 6", "News", True)] if self.news_used else [("News 5", "News", True)]
            return [("Weather", "Weather", True), ("Alerts", "Alerts", self.settles >= 2),
                    ("Today 9°" if self.today_used else "Today 7°", "Today", True),
                    ("Feed", "Alerts" if self.feed_used else "Feed", True),
                    ("Shop", "Shop" if self.shop_visits < 2 else "Alerts", True)] + news
        if s == "Weather":
            return [("Forecast", "Forecast", True), (f"Ski {self.weather_visits}°", "Resorts", True), ("Refresh", "Weather", True)]
        return {"Alerts": [("Alert 1", "AlertDetail", True)], "Forecast": [("Day 1", "Day", True)],
                "Resorts": [("Resort A", "Resort", True)], "Today": [("Hourly", "Hourly", True)],
                "News": [("Story", "Story", True)], "Feed": [("Article", "Article", True)],
                "Shop": [(f"Item {k}", "Item", True) for k in range(1, 9)]}.get(s, [])

    def go(self, target):
        if self.screen == "Home" and target == "Alerts" and self.shop_visits >= 2 and self.pending_shop:
            self.shop_misses += 1
        self.screen = target
        self.shop_visits += target == "Shop"
        if target == "Weather":
            self.weather_visits += 1
        self.today_used |= target == "Today"
        self.news_used |= target == "News"
        self.feed_used |= target == "Feed"
        self.signed_out += target == "SignedOut"

    def tree(self):
        recs = ["0\tFrameLayout\t\tv\t0,0,800,1600\t\t\t", f"1\tTextView\t{self.screen}\tv\t0,0,800,100\t{self.screen}\t\t"]  # the id: a structure per screen
        if self.screen == "Weather":  # live data: another structure at every visit
            recs += [f"1\tRow{self.weather_visits}\t\tv\t0,100,800,190\t\t\t"]
        for k, (label, _, _) in enumerate(self.buttons()):
            top = 200 + 200 * k
            recs.append(f"1\tButton\t\tvc\t0,{top},800,{top + 150}\t{label}\t\t")
        return "\x1e".join(recs)

    def say(self, cmd):
        if cmd.startswith("settle"):
            self.settles += 1
            return "settled"
        if cmd == "activity":
            return "dev.example.Main"
        if cmd == "nodes tree":
            return self.tree()
        if cmd.startswith("restart"):
            self.screen, self.settles = "Home", 0
            return "ok"
        if cmd.startswith("tap-text "):
            for label, target, works in self.buttons():
                if label == cmd[len("tap-text "):] and works:
                    self.pending_shop = label == "Shop"
                    self.go(target)
                    return "ok"
            return f"not found: {cmd[len('tap-text '):]}"
        if cmd.startswith("tap "):
            x, y = (int(v) for v in cmd.split()[1:3])
            for k, (label, target, works) in enumerate(self.buttons()):
                if 200 + 200 * k <= y < 350 + 200 * k and works:
                    self.pending_shop = label == "Shop"
                    self.go(target)
            return "ok"
        if cmd.startswith("shot "):
            with open(cmd.split()[1], "wb") as f:
                f.write(self.screen.encode())
            return "ok"
        return "ok"  # clock, pick cancel


def main():
    out = tempfile.mkdtemp(prefix="crawl-replay-check-")
    args = crawl.parse(["stand-in.apk", "--out", out, "-j", "1", "--max-depth", "6"])
    ex = crawl.Explorer("new", "stand-in.apk", args, out, [])
    ex.ctx.update(patience_s=0, stable_ms=0)
    ex.instances = [StandIn(ex.ctx)]
    try:
        ex.explore()
        weather = [ex.screens[i] for i in ex.order if ex.screens[i]["title"] == "Weather"]
        if weather:
            ex.capture_task(ex.instances[0], (weather[0], [dict(crawl.DEFAULT_COMBO)]))
    finally:
        ex.close()
    titles = {}
    for sid in ex.order:
        titles.setdefault(ex.screens[sid]["title"], []).append(ex.screens[sid])
    print("screens: " + ", ".join(f"{sid} ({ex.screens[sid]['title']}{', flaky' if ex.screens[sid].get('flaky') else ''})"
                                  for sid in ex.order))

    def one(title):
        return (titles.get(title) or [None])[0]

    alerts = one("Alerts")
    check(alerts is not None and not alerts.get("flaky"), "a step that fails until data loads is tried again: Alerts is reached, not flaky")
    check(one("AlertDetail") is not None, "Alerts' detail is found")
    weather = titles.get("Weather") or []
    check(len(weather) == 1, "Weather is one screen, whatever its data (Refresh leads back to it)", str([s["id"] for s in weather]))
    w = weather[0] if weather else {}
    check(bool(w.get("flaky")), "Weather, whose replay never reaches its first structure, is marked flaky", str(w.get("flaky")))
    check({"Forecast", "Refresh"} <= {a["label"] for a in w.get("actions", [])}, "Weather's controls are still listed",
          str(w.get("actions")))
    for t in ("Forecast", "Day", "Resorts", "Resort"):
        check(one(t) is not None, f"{t}, under the flaky Weather, is found")
    flaky = [f for f in ex.findings if f["kind"] == "flaky"]
    check(any(w.get("id", "?") in f["detail"] for f in flaky), "a flaky finding names Weather", str(flaky)[:400])
    today = one("Today")
    check(today is not None and bool(today.get("flaky")), "Today, whose label changed, is marked flaky",
          str(today and today.get("flaky")))
    check(one("Hourly") is not None, "Hourly, behind Today, is found by the tap's coordinates")
    refresh = [e for e in ex.edges if e["action"] == "Refresh"]
    check(bool(refresh) and all(e["to"] == w.get("id") for e in refresh), "Refresh is an edge back to Weather",
          str(refresh))
    check(ex.instances[0].signed_out == 0, "Sign out, now at News' old place, is never tapped",
          f"tapped {ex.instances[0].signed_out} times")
    news = one("News")
    check(news is not None and bool(news.get("flaky")) and one("Story") is None,
          "News, whose label and place changed, is flaky and not explored on another control", str(news))
    feed = one("Feed")
    feed_edges = [e for e in ex.edges if feed and e["from"] == feed["id"]]
    check(feed is not None and bool(feed.get("flaky")) and not feed_edges,
          "Feed, whose path now leads to Alerts, is flaky and Alerts' controls are not taken for its", str(feed_edges))
    shop = one("Shop")
    shop_edges = [e for e in ex.edges if shop and e["from"] == shop["id"]]
    not_tried = [e for e in shop_edges if "not tried" in (e.get("note") or "")]
    misses = ex.instances[0].shop_misses
    # the first control is tried in place after Shop is listed, the next ARRIVE_MISSES miss, the rest are not tried
    check(shop is not None and len(shop_edges) == 8 and len(not_tried) == 8 - 1 - crawl.ARRIVE_MISSES,
          "Shop, found and then missed by its path, lists its eight controls, the rest marked not tried",
          f"{len(shop_edges)} edges, {len(not_tried)} not tried: {[e.get('note') for e in shop_edges][:3]}")
    # a miss replays the path up to three times (exact, then where it leads now); without the stop, 18 here
    check(misses <= 3 * crawl.ARRIVE_MISSES + 1, "Shop's path is not replayed for each of its controls once it misses",
          f"{misses} replays landed on Alerts instead")
    check(any(shop and f["detail"].startswith(f"{shop['id']}: controls not tried") for f in ex.findings),
          "a flaky finding says Shop's controls were not tried")
    caps = w.get("captures") or {}
    check(len(caps) == 1 and os.path.exists(os.path.join(out, next(iter(caps.values())))),
          "the flaky Weather is captured where its path leads now", str(caps))
    if failures:
        print(f"{len(failures)} check(s) failed")
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
