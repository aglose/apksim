#!/usr/bin/env python3
"""Checks crawl.py --freeze-network's recording (NetFreeze) without apksim, a second.

- A walk starts from an empty recording, or from its seed's (a copy: the seed's own is not written).
- Each launch replays a copy of the recording as it is when the launch starts, and fetches into a file of its own.
- What a launch fetched is merged in at its next launch and at the end: requests new to the recording added, a request
  it already has kept as it was (the first answer wins), so two instances that fetched the same thing agree from then on.
- A fetched file that is cut short (a launch killed while writing) is left out, not merged half.
- crawl.json records the clock and the recording (walk_settings), counting what launches fetched until then.
- At the end the launches' copies of the recording are removed.

Exit status 1 on any failed expectation.
"""
import json, os, sys, tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crawl  # noqa: E402

failures = []


def check(ok, what, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (f": {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        failures.append(what)


def entry(url, body, method="GET", post=None):
    req = {"method": method, "url": url, "headers": [], "queryString": []}
    if post is not None:
        req["postData"] = {"mimeType": "application/json", "text": post}
    return {"request": req, "response": {"status": 200, "content": {"text": body}}}


def har(*entries):
    return {"log": {"version": "1.2", "creator": {"name": "t"}, "entries": list(entries)}}


def entries(path):
    with open(path) as f:
        return [(e["request"]["url"], e["response"]["content"]["text"]) for e in json.load(f)["log"]["entries"]]


def write(path, doc):
    with open(path, "w") as f:
        json.dump(doc, f)


def main():
    out = tempfile.mkdtemp(prefix="crawl-freeze-check-")
    net = crawl.NetFreeze(out)
    check(entries(net.master) == [], "a walk starts from an empty recording")
    opts = net.options("new-0")
    check(opts[:2] == ["--network", "replay"] and opts[3] == "--network-unmatched" and opts[4].startswith("fetch:"),
          "a launch replays the recording and fetches what it lacks", str(opts))
    replay0, fetched0 = opts[2], opts[4][len("fetch:"):]
    check(os.path.abspath(replay0) != os.path.abspath(net.master), "a launch replays a copy, not the recording itself")
    fetched1 = net.options("new-1")[4][len("fetch:"):]

    write(fetched0, har(entry("https://api.example/weather?city=Boston", "41F"), entry("https://api.example/me", "A")))
    write(fetched1, har(entry("https://api.example/weather?city=Boston", "43F"), entry("https://api.example/radar", "R")))
    check(entries(replay0) == [], "a merge does not change a running launch's copy")
    net.merge("new-0")
    net.merge("new-1")
    got = dict(entries(net.master))
    check(got.get("https://api.example/weather?city=Boston") == "41F" and len(entries(net.master)) == 3,
          "two launches that fetched the same request: the first answer is kept, new ones added", str(entries(net.master)))
    check(not os.path.exists(fetched0) and not os.path.exists(fetched1), "merged fetch files are removed")
    check(net.fetched == 3, "the walk counts what it added", str(net.fetched))

    with open(os.path.join(net.dir, "fetched-new-2.har"), "w") as f:
        f.write('{"log": {"entries": [{"request": ')  # killed while writing
    net.merge_all()
    check(len(entries(net.master)) == 3, "a fetched file cut short is not merged")

    post = crawl.NetFreeze(tempfile.mkdtemp(prefix="crawl-freeze-check-post-"))
    write(os.path.join(post.dir, "fetched-a.har"), har(entry("https://api.example/q", "1", "POST", '{"q":1}'),
                                                        entry("https://api.example/q", "2", "POST", '{"q":2}')))
    post.merge_all()
    check(len(entries(post.master)) == 2, "requests that differ only in their body are both kept")

    seeded_out = tempfile.mkdtemp(prefix="crawl-freeze-check-seeded-")
    seeded = crawl.NetFreeze(seeded_out, out)
    check(entries(seeded.master) == entries(net.master), "a seeded walk starts from its seed's recording")
    write(os.path.join(seeded.dir, "fetched-new-0.har"), har(entry("https://api.example/new", "N")))
    seeded.merge_all()
    check(len(entries(net.master)) == 3 and len(entries(seeded.master)) == 4, "the seed's own recording is not written")

    write(os.path.join(net.dir, "fetched-new-3.har"), har(entry("https://api.example/late", "L")))
    crawl.NET, crawl.CLOCK = net, "2026-11-25T21:04:00Z"
    ws = crawl.walk_settings()
    check(ws.get("clock") == "2026-11-25T21:04:00Z" and ws.get("network", {}).get("entries") == 4,
          "crawl.json records the clock and the recording, with what launches fetched until then", str(ws))
    net.finish()
    check(not [n for n in os.listdir(net.dir) if n.startswith("replay-")], "the launches' copies are removed at the end")
    crawl.NET = None
    if failures:
        print(f"{len(failures)} check(s) failed")
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
