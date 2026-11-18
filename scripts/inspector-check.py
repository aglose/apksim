#!/usr/bin/env python3
"""The report button's server side and the network inspector against a running `apksim serve` (docs/pr-preview.md).

    scripts/inspector-check.py URL [--token FILE] [--app ID] [--chrome [PATH]] [--forward-port N]

URL is a server started with the test app listed and `--allow-rec --inspect-network --issue-url
'https://tracker.example/new?title={title}&body={body}'`; against one started without them it checks that the tab's commands are
refused and the session announces neither. Standard library only. As a tab does (WebSocket), on the test app's
`apksim://network` screen, whose server api.apksim.test does not exist:

  - the session announces `rec`, `inspect` and the issue template, and only then;
  - tapping Fetch gives a row for the failed lookup (status 0), in the event stream a tab reads (`n e {...}`);
  - a rule (`inspect rule add`) that answers POST /v1/greeting with 200 and a JSON body (and 600 ms of latency) makes the same tap
    an exchange: status 200, answered by the rule, the added latency in its time, nothing sent to a server; the rule list says it was
    hit; its detail has the request's headers with Authorization redacted as recordings redact it, and the body the rule gave;
  - removing and clearing rules work, and a rule the server does not accept (status 999, a space in a pattern, a body that is not
    base64, a command that names a file) is refused before it reaches the instance;
  - a forward rule (`inspect rule add POST PATTERN forward 0 - URL64`) is refused for a target the server does not allow; with
    --forward-port N (the server on this machine, started with `--inspect-forward-allow http://127.0.0.1:N/mock`) this script is
    the upstream: Fetch's POST and the URL button's GET reach it with their method, headers and body and `X-Apksim-Original-Url`, its answers
    (status, a header of its own, body) reach the app, the rows say `forwarded`, a status rule placed first still wins and a
    pass-through one keeps going to the real server, and a target that is gone is a 502 row;
  - a patch rule (`inspect rule add GET PATTERN patch 0 - BODY64`, or `"patch": "JSON text"` in a set) is accepted and listed, and
    one that is not JSON or has a status or body of its own is refused;
  - a second socket for the inspector alone (`/ws?inspect=ID`, issue 497) gets the remembered exchanges and live ones, lists, adds and
    clears rules and reads a detail, is sent no frame and counts as no viewer, needs the token, closing it leaves the session, and the
    session ending tells it;
  - `rec on input logcat` then `rec save` gives a recording the server serves.

With --chrome (a Mac with Chrome): the page's report button downloads one zip holding the note, the settings, a screenshot, the
recording and the network list, and opens the issue template filled in; the panel lists the exchanges and a rule added in it
changes the app's answer. Exits 1 on any failure.
"""
import argparse
import base64
import importlib.util
import io
import json
import os
import sys
import tempfile
import time
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sc", os.path.join(HERE, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)
check = sc.check
FETCH = (130, 175)  # the Fetch button on apksim://network, in a phone's pixels
URL_BUTTON = (564, 175)  # "URL": GET /v1/plain through HttpsURLConnection
GREETING = b'{"greeting":"Hello, rule!"}'


class Tab(sc.Session):
    """A session that keeps the inspector's messages apart."""

    def __init__(self, url, token, app, query):
        hdr = {"Origin": sc.parts(url)[2]}
        if token:
            hdr["Cookie"] = "apksim_token=" + token
        self.ws = sc.WS(url, f"/ws?app={app}&{query}", hdr)
        self.frames, self.codec, self.acking, self.texts, self.stats, self.acks = [], "jpeg", True, [], {}, []
        self.offset, self.best_rtt, self.ack_delay = 0.0, float("inf"), sc.ACK_DELAY_MS / 1000

    def events(self):
        out = []
        for t in self.texts:
            if t.startswith("n e "):
                out.append(json.loads(t[4:]))
        return out

    def replies(self, kind):
        return [t[len("n r " + kind + " "):] for t in self.texts if t.startswith("n r " + kind + " ")]

    def wait_text(self, pred, seconds):
        return self.pump(seconds, until=lambda: any(pred(t) for t in self.texts))

    def tap(self, x, y):
        self.ws.send(f"touch down {x} {y}")
        self.ws.send(f"touch up {x} {y}")


class Upstream:
    """The mock server forward rules send to: remembers each request, answers with the method and a counter (so it has state)."""

    def __init__(self, port):
        self.seen = []
        up = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def answer(self):
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n) if n else b""
                up.seen.append({"method": self.command, "path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}, "body": body})
                out = json.dumps({"greeting": f"forwarded {self.command} #{len(up.seen)}"}).encode()
                self.send_response(201 if self.command == "POST" else 200)
                self.send_header("Content-Type", "application/json")
                self.send_header("X-Mock-State", str(len(up.seen)))
                self.send_header("Content-Length", str(len(out)))
                self.send_header("Connection", "close")  # so no connection outlives close(), for the "upstream gone" check
                self.end_headers()
                self.wfile.write(out)

            do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = answer

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", port), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def forward_checks(t, port):
    """Forward rules (issue 363): refusals always, the round trip when this script may be the upstream."""
    def add(method, pattern, target, latency=0):
        k = len(t.texts)
        t.ws.send(f"inspect rule add {method} {pattern} forward {latency} - {base64.b64encode(target.encode()).decode()}")
        t.pump(5, until=lambda: any(x.startswith("n r rule ") or x.startswith("r refused") for x in t.texts[k:]))
        return [x for x in t.texts[k:] if x.startswith("n r rule ") or x.startswith("r refused")]

    t.ws.send("inspect rule clear")
    t.pump(1.5)
    for bad in ("http://127.0.0.1:9/elsewhere", "https://example.com/", f"http://127.0.0.1:{port or 1}/mockx", f"http://127.0.0.1:{port or 1}/mock/../x",
                f"http://user@127.0.0.1:{port or 1}/mock", "file:///etc/passwd"):
        got = add("POST", "/v1/greeting", bad)
        check(any(x.startswith("n r rule error: forward target not allowed") for x in got), f"a forward target the server does not allow is refused: {bad}")
    k = len(t.texts)
    t.ws.send("inspect rule add POST /v1/greeting forward 0 text/plain aHR0cDovL3gv")
    t.pump(1.5)
    check(any(x.startswith("r refused") for x in t.texts[k:]), "a forward rule with a content type is refused")
    t.ws.send("inspect rule list")
    check(t.pump(5, until=lambda: t.replies("rule") and t.replies("rule")[-1] == "[]"), "none of them became a rule")
    if not port:
        print("     (no --forward-port: the forward round trip is not checked)")
        return
    up = Upstream(port)
    try:
        target = f"http://127.0.0.1:{port}/mock/answer?from=check"
        got = add("*", "*api.apksim.test/v1/*", target, 300)
        check(any(x.startswith("n r rule {") and '"forward":"' + target + '"' in x for x in got), f"a forward rule to an allowed target is added ({got})")
        time.sleep(1)
        before = len(t.events())
        t.tap(*FETCH)
        t.tap(*URL_BUTTON)
        check(t.pump(30, until=lambda: len([e for e in t.events()[before:] if e.get("forwarded")]) >= 2), "Fetch and URL: the POST and the GET are forwarded")
        rows = [e for e in t.events()[before:] if e.get("forwarded")]
        methods = sorted(e["method"] for e in rows)
        check(methods[:2] == ["GET", "POST"], f"rows for both, marked forwarded, with the rule's id ({[(e['method'], e['status'], e.get('rule')) for e in rows]})")
        check(all(e.get("rule") and e.get("addedMs") == 300 and e["ms"] >= 300 for e in rows), "each with the rule's latency")
        post = next((r for r in up.seen if r["method"] == "POST"), None)
        get = next((r for r in up.seen if r["method"] == "GET"), None)
        check(post is not None and get is not None, f"the upstream saw a POST and a GET ({[r['method'] for r in up.seen]})")
        if post and get:
            check(post["path"] == "/mock/answer?from=check" and post["body"].startswith(b'{"name"'), f"the POST came to the rule's URL with the app's body ({post['body'][:40]!r})")
            check(post["headers"].get("x-apksim-original-url") == "https://api.apksim.test/v1/greeting", f"naming where the app sent it ({post['headers'].get('x-apksim-original-url')})")
            check(get["headers"].get("x-apksim-original-url") == "https://api.apksim.test/v1/plain" and get["body"] == b"",
                  f"the GET's original URL, no body ({get['headers'].get('x-apksim-original-url')})")
            check("content-type" in post["headers"] and "user-agent" in post["headers"], f"with the app's headers ({sorted(post['headers'])})")
            check("authorization" not in post["headers"], "but not the ones recordings redact (Authorization)")
        prow = next((e for e in rows if e["method"] == "POST"), None)
        if prow:
            check(prow["status"] == 201, f"the upstream's status reaches the app's row ({prow['status']})")
            t.ws.send(f"inspect detail {prow['id']}")
            n = len(t.replies("detail"))
            check(t.pump(10, until=lambda: len(t.replies("detail")) > n), "its detail comes back")
            d = json.loads(t.replies("detail")[-1])
            hs = {h[0].lower(): h[1] for h in d["respHeaders"]}
            check(hs.get("x-mock-state") and hs.get("x-apksim-rule") and b"forwarded POST" in base64.b64decode(d["respBody"]),
                  f"with the upstream's own header and body ({hs.get('x-mock-state')}, {base64.b64decode(d['respBody'])[:50]!r})")
        # In a set (issue 364's command): a forward rule beside a status rule; a target that is not allowed refuses the whole set.
        k = len(t.replies("rule"))
        t.ws.send("inspect rule set " + json.dumps([{"pattern": "/a", "status": 200}, {"method": "POST", "pattern": "/b", "forward": target, "latencyMs": 5}]))
        check(t.pump(10, until=lambda: any(x.startswith("[") and '"forward"' in x for x in t.replies("rule")[k:])), "a set with a forward rule is taken")
        for wrong in ([{"pattern": "/b", "forward": "http://127.0.0.1:9/elsewhere"}], [{"pattern": "/b", "forward": target, "status": 200}]):
            k = len(t.replies("rule"))
            t.ws.send("inspect rule set " + json.dumps(wrong))
            check(t.pump(5, until=lambda: any(x.startswith("error: rule 1") for x in t.replies("rule")[k:])), f"a set with {json.dumps(wrong)[:70]} is refused")
        k = len(t.replies("rule"))
        t.ws.send("inspect rule list")
        check(t.pump(5, until=lambda: len(t.replies("rule")) > k) and [r["pattern"] for r in json.loads(t.replies("rule")[-1])] == ["/a", "/b"], "the refused sets changed nothing")
        # Ordering: a status rule added first wins; then a pass-through rule first lets the request go on to the (absent) server.
        t.ws.send("inspect rule clear")
        t.ws.send(f"inspect rule add POST /v1/greeting 200 0 application/json {base64.b64encode(GREETING).decode()}")
        t.pump(1.5)
        add("*", "*api.apksim.test/v1/*", target)
        time.sleep(1)
        before, seen = len(t.events()), len(up.seen)
        t.tap(*FETCH)
        t.tap(*URL_BUTTON)
        t.pump(30, until=lambda: len([e for e in t.events()[before:] if e.get("rule")]) >= 2)
        rows = [e for e in t.events()[before:] if e.get("rule")]
        check(any(e["method"] == "POST" and not e.get("forwarded") and e["status"] == 200 for e in rows)
              and any(e["method"] == "GET" and e.get("forwarded") for e in rows) and all(r["method"] != "POST" for r in up.seen[seen:]),
              f"an earlier status rule answers the POST, the forward rule the GET ({[(e['method'], e['status'], e.get('forwarded')) for e in rows]})")
        # The upstream gone: a 502 row that says why.
        up.close()
        up = None
        t.ws.send("inspect rule clear")
        t.pump(1.5)
        add("POST", "/v1/greeting", target)
        time.sleep(1)
        before = len(t.events())
        t.tap(*FETCH)
        check(t.pump(40, until=lambda: any(e.get("forwarded") and e["status"] == 502 for e in t.events()[before:])), "the upstream gone: a 502 row, marked forwarded")
    finally:
        if up:
            up.close()
        t.ws.send("inspect rule clear")
        t.pump(1)


def patch_checks(t):
    """Patch rules (issue 373): what the server accepts and passes on. The merge itself is check-inspector-rules.sh's: this
    server's app has no real server to answer."""
    def send(text):
        k = len(t.texts)
        t.ws.send(text)
        t.pump(5, until=lambda: any(x.startswith("n r rule ") or x.startswith("r refused") for x in t.texts[k:]))
        return [x for x in t.texts[k:] if x.startswith("n r rule ") or x.startswith("r refused")]

    patch = json.dumps({"banner": {"title": "New"}, "old": None})
    b64 = base64.b64encode(patch.encode()).decode()
    t.ws.send("inspect rule clear")
    t.pump(1.5)
    got = send(f"inspect rule add GET /v1/schedule* patch 0 - {b64}")
    check(any(x.startswith("n r rule {") and '"patch":true' in x and '"status":0' in x for x in got), f"a patch rule is added: {got}")
    got = send("inspect rule add GET /x patch 0 - " + base64.b64encode(b"{nope").decode())
    check(any(x.startswith("n r rule error") for x in got), f"a patch that is not JSON is refused: {got}")
    got = send("inspect rule set " + json.dumps([{"pattern": "/a", "status": 200}, {"method": "GET", "pattern": "/b", "patch": patch, "latencyMs": 5}]))
    check(any(x.startswith("n r rule [") and '"patch":true' in x and x.index("/a") < x.index("/b") for x in got), f"a set with a patch rule: {got}")
    for wrong in ([{"pattern": "/b", "patch": patch, "status": 200}], [{"pattern": "/b", "patch": patch, "body": "eA=="}],
                  [{"pattern": "/b", "patch": {"a": 1}}], [{"pattern": "/b", "patch": "{nope"}], [{"pattern": "/b", "patch": ""}]):
        got = send("inspect rule set " + json.dumps(wrong))
        check(any(x.startswith("n r rule error") for x in got), f"a set with a wrong patch rule is refused: {json.dumps(wrong)[:60]}")
    got = send("inspect rule list")
    check(any(x.count('"id"') == 2 for x in got), "a refused set changed nothing")
    t.ws.send("inspect rule clear")
    t.pump(1.5)


def inspector_socket_checks(url, token, t, sid):
    """Issue 497: a second socket for the inspector alone (`/ws?inspect=ID`), while the tab's own socket streams frames."""
    hdr = {"Origin": sc.parts(url)[2]}
    if token:
        hdr["Cookie"] = "apksim_token=" + token

    def read(ws, seconds, until=None):
        got, end = [], time.time() + seconds
        while time.time() < end:
            m = ws.recv(0.2)
            if m is None:
                if until and until(got):
                    break
                continue
            if m[0] == 8:
                got.append((8, b""))
                break
            if m[0] in (1, 2):
                got.append((m[0], m[1].decode("utf-8", "replace") if m[0] == 1 else m[1]))
            if until and until(got):
                break
        return got

    def texts(got):
        return [x[1] for x in got if x[0] == 1]

    bad = sc.WS(url, "/ws?inspect=" + "0" * 12, hdr)
    check(bad.status == 404, f"a session that does not exist has no inspector socket ({bad.status})")
    bad.s.close()
    if token:
        anon = sc.WS(url, f"/ws?inspect={sid}", {"Origin": sc.parts(url)[2]})
        check(anon.status == 401, f"it needs the server's token as the session's own socket does ({anon.status})")
        anon.s.close()
        cross = sc.WS(url, f"/ws?inspect={sid}", dict(hdr, Origin="https://elsewhere.example"))
        check(cross.status == 403, f"and refuses another origin ({cross.status})")
        cross.s.close()
    w = sc.WS(url, f"/ws?inspect={sid}", hdr)
    check(w.status == 101, f"the inspector socket upgrades ({w.status})")
    backlog = read(w, 5, until=lambda g: any(x.startswith("n e ") for x in texts(g)))
    check(any(x.startswith("n e ") for x in texts(backlog)), "it gets the exchanges the inspector remembers on connect")
    w.send("inspect rule list")
    got = read(w, 5, until=lambda g: "n r rule []" in texts(g))
    check("n r rule []" in texts(got), "it lists the rules, answered to it")
    w.send("inspect rule add GET /socket-two 200 0 - -")
    got = read(w, 5, until=lambda g: any(x.startswith("n r rule [") and "/socket-two" in x for x in texts(g)))
    check(any("/socket-two" in x for x in texts(got)), "it adds a rule")
    n = len(t.frames)
    before = len(t.events())
    t.tap(*FETCH)
    live = read(w, 30, until=lambda g: sum(x.startswith("n e ") for x in texts(g)) >= 1)
    check(any(x.startswith("n e ") for x in texts(live)), "a new exchange reaches it live")
    check(t.pump(10, until=lambda: len(t.frames) > n), "the tab's socket kept streaming frames meanwhile")
    check(t.pump(10, until=lambda: len(t.events()) > before), "and still gets its own events")
    w.send("inspect detail 999999999")
    got = read(w, 5, until=lambda g: any(x.startswith("n r detail") for x in texts(g)))
    check(any(x.startswith("n r detail") for x in texts(got)), "a detail request is answered to it")
    w.send("inspect rule clear")
    read(w, 3, until=lambda g: "n r rule []" in texts(g))
    w.send("touch down 10 10")
    got = read(w, 3, until=lambda g: any(x.startswith("r refused") for x in texts(g)))
    check(any(x.startswith("r refused") for x in texts(got)), "anything but inspect commands is refused")
    check(not any(x[0] == 2 for x in backlog + live + got), "no frame was ever sent to it")
    check(not any(x.startswith("d {\"viewers\"") for x in t.texts), "and it did not count as a viewer for pacing")
    w.s.close()
    time.sleep(1)
    k = len(t.replies("rule"))
    t.ws.send("inspect rule list")
    check(t.pump(5, until=lambda: len(t.replies("rule")) > k), "closing it does not end the session")


def inspector_socket_end_check(url, token, t, sid):
    """The session ending closes an inspector socket with the usual `e` and `x`."""
    hdr = {"Origin": sc.parts(url)[2]}
    if token:
        hdr["Cookie"] = "apksim_token=" + token
    w = sc.WS(url, f"/ws?inspect={sid}", hdr)
    check(w.status == 101, "a second inspector socket opens for the end check")
    time.sleep(1)
    t.ws.s.close()
    end, got = time.time() + 30, []
    while time.time() < end:
        m = w.recv(0.5)
        if m is None:
            continue
        if m[0] == 1:
            got.append(m[1].decode("utf-8", "replace"))
        if m[0] == 8 or any(x.startswith("x ") for x in got):
            break
    check(any(x.startswith("x ") for x in got), f"the session ending tells it ({[x[:60] for x in got][-2:]})")
    w.s.close()


def optional_checks(url, token, app):
    """Issue 371, a server started with `--inspect-network optional`: only a session that asks for the inspector has it."""
    for ask in (False, True):
        t = Tab(url, token, app, "codec=jpeg&open=apksim%3A%2F%2Fnetwork" + ("&inspect=1" if ask else ""))
        try:
            check(t.wait_text(lambda x: x.startswith("i "), 60), f"a session starts (inspect={'1' if ask else 'not asked'})")
            i = json.loads(next(x for x in t.texts if x.startswith("i "))[2:])
            check(i.get("inspectAllowed") is True, "it says the server would allow the inspector")
            check(i.get("inspect") is ask, f"and has it only when it asked ({i.get('inspect')})")
            check(("inspectMaxRules" in i) is ask, "inspectMaxRules is told only to a session that has it")
            t.ws.send("inspect rule list")
            if ask:
                check(t.wait_text(lambda x: x == "n r rule []", 10), "its inspect commands are answered")
                check(t.pump(90, until=lambda: len(t.frames) >= 1), "the app shows a frame")
                t.pump(4)
                t.tap(*FETCH)
                check(t.pump(30, until=lambda: any(e["method"] == "DNS" for e in t.events())), "Fetch: the failed lookup is a row")
            else:
                t.pump(2)
                check(any(x.startswith("r refused: inspect") for x in t.texts), "without asking, its inspect commands are refused")
        finally:
            t.ws.s.close()
    sc.finish()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("url")
    ap.add_argument("--token")
    ap.add_argument("--app")
    ap.add_argument("--chrome", nargs="?", const="", default=None)
    ap.add_argument("--forward-port", type=int, default=0)
    ap.add_argument("--optional", action="store_true", help="the server runs with --inspect-network optional: check per-session opt-in")
    a = ap.parse_args()
    url = a.url.rstrip("/")
    token = open(a.token).readline().strip() if a.token else None
    status, body = sc.http(url, "/api/apps", token)
    apps = json.loads(body) if status == 200 else []
    app = a.app or (apps[0]["id"] if apps else None)
    if not app:
        sys.exit("inspector-check.py: the server lists no app")

    if a.optional:
        optional_checks(url, token, app)
        return

    t = Tab(url, token, app, "codec=jpeg&open=apksim%3A%2F%2Fnetwork")
    try:
        check(t.wait_text(lambda x: x.startswith("i "), 60), "a session starts")
        i = json.loads(next(x for x in t.texts if x.startswith("i "))[2:])
        inspect, rec = i.get("inspect") is True, i.get("rec") is True
        print(f"     the session says rec={rec} inspect={inspect} issueUrl={i.get('issueUrl')}")
        if not inspect:
            check("issueUrl" not in i or rec, "the issue template is announced only with the recorder")
            t.ws.send("inspect rule list")
            t.pump(2)
            check(any(x.startswith("r refused: inspect") for x in t.texts), "without --inspect-network the tab's inspect commands are refused")
            t.ws.send("rec save")
            t.pump(2)
            if not rec:
                check(any(x.startswith("r refused: rec") for x in t.texts), "without --allow-rec the tab's rec commands are refused")
            t.ws.s.close()
            sc.finish()
            return
        # Issue 389: --inspect-network is the default, and a session may decline it (/ws?inspect=0).
        d = Tab(url, token, app, "codec=jpeg&open=apksim%3A%2F%2Fnetwork&inspect=0")
        try:
            check(d.wait_text(lambda x: x.startswith("i "), 60), "a session that declines the inspector starts (inspect=0)")
            di = json.loads(next(x for x in d.texts if x.startswith("i "))[2:])
            check(di.get("inspect") is False and di.get("inspectAllowed") is True and "inspectMaxRules" not in di,
                  f"it runs without the inspector, which the server would allow ({di.get('inspect')}, {di.get('inspectAllowed')})")
            d.ws.send("inspect rule list")
            d.pump(2)
            check(any(x.startswith("r refused: inspect") for x in d.texts), "and its inspect commands are refused")
        finally:
            d.ws.s.close()
        check(rec and i.get("issueUrl", "").startswith("https://") and "{title}" in i["issueUrl"], "it announces rec and the issue template")
        check(t.pump(90, until=lambda: len(t.frames) >= 2), "the app shows its first frames")
        time.sleep(2)

        t.ws.send("inspect rule list")
        check(t.wait_text(lambda x: x == "n r rule []", 10), "the rule list starts empty")
        n = len(t.events())
        t.tap(*FETCH)
        check(t.pump(30, until=lambda: any(e["method"] == "DNS" for e in t.events())), "Fetch: the failed lookup is a row")
        dns = next(e for e in t.events() if e["method"] == "DNS")
        check(dns["status"] == 0 and "apksim.test" in dns["url"], f"with status 0 and the host ({dns['url']})")

        body = base64.b64encode(GREETING).decode()
        t.ws.send(f"inspect rule add POST /v1/greeting 200 600 application/json {body}")
        check(t.wait_text(lambda x: x.startswith("n r rule {") and '"status":200' in x, 10), "a rule is added")
        time.sleep(1)
        before = len(t.events())
        t.tap(*FETCH)
        check(t.pump(30, until=lambda: any(e.get("rule") for e in t.events()[before - 1:])), "Fetch again: an exchange the rule answered")
        ex = next(e for e in t.events()[before - 1:] if e.get("rule"))
        check(ex["method"] == "POST" and ex["url"] == "https://api.apksim.test/v1/greeting" and ex["status"] == 200,
              f"{ex['method']} {ex['url']} -> {ex['status']}")
        check(ex["respBytes"] == len(GREETING) and ex.get("addedMs") == 600 and ex["ms"] >= 600,
              f"{ex['respBytes']} bytes, {ex['ms']} ms of which {ex.get('addedMs')} are the rule's latency")
        t.ws.send("inspect rule list")
        t.pump(2)
        check(any('"hits":1' in x for x in t.replies("rule")), "the rule list counts the hit")
        t.ws.send(f"inspect detail {ex['id']}")
        check(t.wait_text(lambda x: x.startswith("n r detail "), 10), "its detail comes back")
        d = json.loads(t.replies("detail")[-1])
        auth = [h[1] for h in d["reqHeaders"] if h[0].lower() == "authorization"]
        check(auth == ["REDACTED"], f"the Authorization header is redacted as recordings redact it ({auth})")
        check(base64.b64decode(d["respBody"]) == GREETING and base64.b64decode(d["reqBody"]).startswith(b'{"name"'),
              "the bodies are there: the request's and the rule's answer")

        # Issue 314: a rule body over 64 KB (up to 512 KB) through the tab's socket; the detail keeps 256 KB of it and says it was cut.
        large = b'{"greeting":"large","pad":"' + b"x" * (400 * 1024) + b'"}'
        t.ws.send("inspect rule clear")
        t.ws.send(f"inspect rule add POST /v1/greeting 200 0 application/json {base64.b64encode(large).decode()}")
        check(t.wait_text(lambda x: x.startswith("n r rule {") and f'"bytes":{len(large)}' in x, 15), f"a rule with a {len(large)}-byte body is added")
        before = len(t.events())
        t.tap(*FETCH)
        check(t.pump(30, until=lambda: any(e.get("rule") and e["respBytes"] == len(large) for e in t.events()[before - 1:])),
              "Fetch: the app is answered with all of it")
        exl = [e for e in t.events()[before - 1:] if e.get("rule") and e["respBytes"] == len(large)]
        if exl:
            k = len(t.replies("detail"))
            t.ws.send(f"inspect detail {exl[0]['id']}")
            check(t.pump(15, until=lambda: len(t.replies("detail")) > k), "its detail comes back")
            d = json.loads(t.replies("detail")[-1])
            got = base64.b64decode(d["respBody"])
            check(d["respCut"] is True and len(got) == 256 * 1024 and large.startswith(got), f"the detail has the first {len(got)} bytes and says it is cut")
        k = len(t.texts)
        t.ws.send("inspect rule add POST /x 200 0 - " + base64.b64encode(b"x" * (512 * 1024 + 1)).decode())
        t.pump(3)
        check(any(x.startswith("r refused") or x.startswith("n r rule error") for x in t.texts[k:]), "a body of 512 KB + 1 is refused")

        for bad in ("inspect rule add POST /x 999 0 - -", "inspect rule add POST /x 103 0 - -", "inspect rule add POST /a b 200 0 - -", "inspect rule add POST /x 200 0 - !!!notb64",
                    "inspect rule add ../x 200 0 - -", "inspect detail ../../etc/passwd", "inspect shot /tmp/x.png", "inspect rule add GET /x 0 0 - -"):
            k = len(t.texts)
            t.ws.send(bad)
            t.pump(1.5)
            new = t.texts[k:]
            check(any(x.startswith("r refused") or x.startswith("n r rule error") or x.startswith("n r detail error") for x in new), f"refused: {bad[:50]}")
        t.ws.send("inspect rule clear")
        t.ws.send("inspect rule list")
        check(t.pump(5, until=lambda: t.replies("rule") and t.replies("rule")[-1] == "[]"), "clear removes the rules")

        # Issue 364: a whole set in one command, in order; response headers on a rule; the session's rule limit.
        limit = i.get("inspectMaxRules")
        check(isinstance(limit, int) and limit >= 1, f"the session's i message gives the rule limit ({limit})")
        t.ws.send("inspect rule add GET /one 200 0 - -")
        rules = [{"method": "GET", "pattern": "/never", "status": 404},
                 {"method": "POST", "pattern": "/v1/greeting", "status": 200, "type": "application/json", "body": base64.b64encode(GREETING).decode(),
                  "headers": [["ETag", '"v7"'], ["Retry-After", "3"], ["X-Mock", "set by a rule"]]},
                 {"pattern": "*", "latencyMs": 5}]
        k = len(t.replies("rule"))
        t.ws.send("inspect rule set " + json.dumps(rules))
        check(t.pump(10, until=lambda: any(x.startswith("[") for x in t.replies("rule")[k:])), "rule set answers with a list")
        got = next((json.loads(x) for x in t.replies("rule")[k:] if x.startswith("[")), [])
        check([r["pattern"] for r in got] == ["/never", "/v1/greeting", "*"], f"exactly the three rules, in order ({[r['pattern'] for r in got]})")
        check(len(got) == 3 and got[1].get("headers") == rules[1]["headers"], "the list shows a rule's headers")
        before = len(t.events())
        t.tap(*FETCH)
        check(t.pump(30, until=lambda: any(e.get("rule") and e["status"] == 200 for e in t.events()[before:])), "Fetch: the set's rule answers")
        exs = [e for e in t.events()[before:] if e.get("rule") and e["status"] == 200]
        if exs:
            kd = len(t.replies("detail"))
            t.ws.send(f"inspect detail {exs[0]['id']}")
            check(t.pump(10, until=lambda: len(t.replies("detail")) > kd), "its detail comes back")
            rh = {h[0]: h[1] for h in json.loads(t.replies("detail")[-1])["respHeaders"]}
            check(rh.get("ETag") == '"v7"' and rh.get("Retry-After") == "3" and rh.get("X-Mock") == "set by a rule", f"the answer carried the rule's headers ({rh})")
        for name in ("Content-Length", "Transfer-Encoding"):
            k = len(t.replies("rule"))
            t.ws.send("inspect rule set " + json.dumps([{"pattern": "/h", "status": 200, "headers": [[name, "1"]]}]))
            check(t.pump(5, until=lambda: any(x.startswith("error") and name in x for x in t.replies("rule")[k:])), f"a set with a {name} header is refused")
            k = len(t.replies("rule"))
            t.ws.send("inspect rule add GET /h 200 0 - - " + base64.b64encode(f"{name}: 1".encode()).decode())
            check(t.pump(5, until=lambda: any(x.startswith("error") and name in x for x in t.replies("rule")[k:])), f"and an add with it")
        k = len(t.replies("rule"))
        t.ws.send("inspect rule list")
        check(t.pump(5, until=lambda: len(t.replies("rule")) > k) and [r["pattern"] for r in json.loads(t.replies("rule")[-1])] == ["/never", "/v1/greeting", "*"],
              "the refused sets changed nothing")
        for wrong in ({"pattern": "/h", "status": 200, "method": 5}, {"pattern": "/h", "latency": 100}):
            k = len(t.replies("rule"))
            t.ws.send("inspect rule set " + json.dumps([wrong]))
            check(t.pump(5, until=lambda: any(x.startswith("error: rule 1") for x in t.replies("rule")[k:])), f"a set with {json.dumps(wrong)} is refused")
        if isinstance(limit, int):
            k = len(t.replies("rule"))
            t.ws.send("inspect rule set " + json.dumps([{"pattern": f"/r{n}", "status": 200} for n in range(limit + 1)]))
            check(t.pump(5, until=lambda: any(x.startswith("error: at most") for x in t.replies("rule")[k:])), f"a set of {limit + 1} rules is over the limit")
            k = len(t.replies("rule"))
            t.ws.send("inspect rule set " + json.dumps([{"pattern": f"/r{n}", "status": 200} for n in range(limit)]))
            check(t.pump(10, until=lambda: any(x.startswith("[") and len(json.loads(x)) == limit for x in t.replies("rule")[k:])), f"a set of {limit} is taken")
            k = len(t.replies("rule"))
            t.ws.send("inspect rule add GET /more 200 0 - -")
            check(t.pump(5, until=lambda: any(x.startswith("error: at most") for x in t.replies("rule")[k:])), "and one more add is refused")
        t.ws.send("inspect rule set []")
        t.ws.send("inspect rule list")
        check(t.pump(5, until=lambda: t.replies("rule") and t.replies("rule")[-1] == "[]"), "an empty set removes the rules")

        inspector_socket_checks(url, token, t, i["id"])
        forward_checks(t, a.forward_port)
        patch_checks(t)

        if rec:
            t.ws.send("rec on input logcat")
            t.pump(1)
            t.tap(*FETCH)
            t.pump(2)
            t.ws.send("rec save 60")
            check(t.wait_text(lambda x: x.startswith("r rec: saved "), 60), "rec save answers with a recording")
            rid = next(x for x in t.texts if x.startswith("r rec: saved "))[13:].strip()
            sid = i["id"]
            end = time.time() + 20
            status, data = 404, b""
            while time.time() < end and status == 404:
                status, data = sc.http(url, f"/api/sessions/{sid}/recordings/{rid}", token)
                time.sleep(0.5)
            check(status == 200 and zipfile.is_zipfile(io.BytesIO(data)), f"the server serves it as a zip ({status}, {len(data)} bytes)")
        inspector_socket_end_check(url, token, t, i["id"])
    finally:
        t.ws.s.close()
    if a.chrome is not None:
        page_layer(url, token, app, a.chrome or None)
    sc.finish()


def page_layer(url, token, app, chrome):
    bp = importlib.util.spec_from_file_location("bp", os.path.join(HERE, "beam-parity.py"))
    bp_mod = importlib.util.module_from_spec(bp)
    bp.loader.exec_module(bp_mod)
    import subprocess
    import urllib.request
    cp = bp_mod.free_port()
    prof, dl = tempfile.mkdtemp(prefix="inspector-chrome-"), tempfile.mkdtemp(prefix="inspector-dl-")
    proc = subprocess.Popen([chrome or bp_mod.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run",
                             "--no-default-browser-check", "--window-size=1300,900", "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(60):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{cp}/json/version", timeout=2)
                break
            except OSError:
                time.sleep(0.3)
        cdp = bp_mod.Cdp(cp)
        cdp.call("Browser.setDownloadBehavior", {"behavior": "allow", "downloadPath": dl})
        tg = cdp.call("Target.createTarget", {"url": "about:blank"})
        s = cdp.call("Target.attachToTarget", {"targetId": tg["targetId"], "flatten": True})["sessionId"]
        q = f"app={app}&codec=jpeg&open=apksim%3A%2F%2Fnetwork"
        cdp.call("Page.navigate", {"url": f"{url}/?{('token=' + token + '&') if token else ''}{q}"}, s)

        def wait(cond, seconds):
            end = time.time() + seconds
            while time.time() < end:
                try:
                    if cond():
                        return True
                except Exception:
                    pass
                time.sleep(0.4)
            return False

        check(wait(lambda: cdp.ev(s, "window.apksimStats().frames > 1"), 90), "[page] the session shows frames")
        time.sleep(2)
        check(cdp.ev(s, "document.getElementById('reportCard').classList.contains('on') && document.getElementById('inspectCard').classList.contains('on')"),
              "[page] the Report and Network cards are shown")
        cdp.ev(s, "window.__opened = []; window.open = () => { const t = { close() {} }; Object.defineProperty(t, 'location', { set(v) { window.__opened.push(v); } }); return t; }; 1")
        cdp.ev(s, "document.getElementById('inspToggle').click(); 1")
        # A rule typed into the panel, then Fetch: the panel lists the exchange.
        cdp.ev(s, """(() => { document.getElementById('rulePattern').value = '/v1/greeting'; document.getElementById('ruleStatus').value = '200';
          document.getElementById('ruleType').value = 'application/json'; document.getElementById('ruleBody').value = '{"greeting":"From the panel"}';
          document.getElementById('inspAdd').requestSubmit(); })()""")
        check(wait(lambda: cdp.ev(s, "document.getElementById('inspRules').children.length") == 1, 10), "[page] a rule typed into the panel is listed")
        r = json.loads(cdp.ev(s, "JSON.stringify(document.querySelector('canvas').getBoundingClientRect())"))
        x, y = r["x"] + FETCH[0] / 822 * r["width"], r["y"] + FETCH[1] / 1782 * r["height"]
        for typ in ("mousePressed", "mouseReleased"):
            cdp.call("Input.dispatchMouseEvent", {"type": typ, "x": x, "y": y, "button": "left", "clickCount": 1}, s)
        check(wait(lambda: any(e.get("rule") for e in json.loads(cdp.ev(s, "JSON.stringify(window.apksimInspector().exchanges)"))), 30),
              "[page] Fetch: the panel lists the exchange the rule answered")
        cdp.ev(s, "document.getElementById('reportNote').value = 'The greeting looks wrong'; 1")
        cdp.ev(s, "document.getElementById('reportBtn').click(); 1")
        got = None
        end = time.time() + 90
        while time.time() < end and not got:
            files = [f for f in os.listdir(dl) if f.endswith(".zip")]
            if files:
                got = os.path.join(dl, files[0])
            time.sleep(0.5)
        check(got is not None, "[page] the report button downloads one zip")
        if got:
            time.sleep(0.5)
            z = zipfile.ZipFile(got)
            names = sorted(z.namelist())
            check(z.testzip() is None, "[page] the zip is sound (every CRC)")
            print("     " + ", ".join(f"{i.filename} {i.file_size} B" for i in z.infolist()) + f" (zip {os.path.getsize(got)} B)")
            check({"note.txt", "settings.json", "screenshot.png", "recording.apkrec", "network.json"} <= set(names), f"[page] it holds {names}")
            check(z.read("note.txt").decode().startswith("The greeting looks wrong") and z.read("screenshot.png")[:4] == b"\x89PNG"
                  and zipfile.is_zipfile(io.BytesIO(z.read("recording.apkrec"))), "[page] the note, a PNG and a recording that is itself a zip")
            st = json.loads(z.read("settings.json"))
            check(st["app"] and st["screen"]["width"] > 0 and (not token or token not in json.dumps(st)), "[page] the settings name the app and screen, and hold no token")
            net = json.loads(z.read("network.json"))
            check(any(e.get("rule") for e in net), "[page] network.json lists the exchanges")
        opened = cdp.ev(s, "window.__opened")
        check(len(opened) == 1 and opened[0].startswith("https://tracker.example/") or len(opened) == 1 and opened[0].startswith("https://"),
              f"[page] the issue page is opened by the page, filled in ({opened[0][:90] if opened else None})")
        check(not opened or "The%20greeting%20looks%20wrong" in opened[0], "[page] with the note as its title")
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except Exception:
            proc.kill()
            proc.wait()


if __name__ == "__main__":
    main()
