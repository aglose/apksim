#!/usr/bin/env python3
"""The report button's server side and the network inspector against a running `apksim serve` (docs/pr-preview.md).

    scripts/inspector-check.py URL [--token FILE] [--app ID] [--chrome [PATH]]

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
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sc", os.path.join(HERE, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)
check = sc.check
FETCH = (130, 175)  # the Fetch button on apksim://network, in a phone's pixels
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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("url")
    ap.add_argument("--token")
    ap.add_argument("--app")
    ap.add_argument("--chrome", nargs="?", const="", default=None)
    a = ap.parse_args()
    url = a.url.rstrip("/")
    token = open(a.token).readline().strip() if a.token else None
    status, body = sc.http(url, "/api/apps", token)
    apps = json.loads(body) if status == 200 else []
    app = a.app or (apps[0]["id"] if apps else None)
    if not app:
        sys.exit("inspector-check.py: the server lists no app")

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
