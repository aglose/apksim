#!/usr/bin/env python3
"""Compare mode and PR previews against a running `apksim serve` (docs/pr-preview.md).

    scripts/compare-check.py URL [--token FILE] [--app ID] [--app2 ID] [--chrome] [--fill] [--wait-expiry] [--apk APK]

URL is the server, started with the test app listed (`--apps`; ids from /api/apps). `--app2` is a second listed app whose
launch options change a screen (a catalog line with `-- --status-bar off`: no status bar, so the layout moves).
Standard library only. Two layers:

  Protocol (any machine, CI's image): `/?compare=A,B` is served and a malformed one refused; two sessions of one app, driven as
  the page drives them (every tap to both): both answer the mirrored taps with new frames, their frames are byte for byte
  the same where the screens match, differ once one of them is dark, and agree again once both are; the two count against
  the server's `max` (with --fill, and one more than it allows is refused); a build added with a `ttl` reports `expiresInS`, a ttl outside
  60 s to 7 days is refused, and with --wait-expiry the id is gone after its time. `pr-preview.py --dry-run` prints its comment
  and, against this server with a stand-in GitHub, adds the builds, posts one comment and updates that same comment on a second
  run, with no token in it.

  Page (--chrome, a Mac with Chrome: the page's own pixel count, read through the DevTools protocol): the page opened on two
  sessions of one app shows a difference of 0 pixels, a tap on either screen reaches both, 'Drive: A only' with Dark gives
  a non-zero count with a bounding box, both dark gives 0 again; with --app2 two apps whose launch options differ show a non-zero count and a box; a device
  of another size on one side is reported as different sizes; one session ending (its socket closed) ends the other and
  leaves no session on the server. Counts are read once both screens have stopped changing.

Exits 1 on any failure.
"""
import argparse
import http.server
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sc = load("sc", "serve-check.py")
check = sc.check
FAB = (733, 1533)


def sessions(url, token):
    status, body = sc.http(url, "/api/status", token)
    return json.loads(body)["sessions"] if status == 200 else []


def wait_frames(s, n, seconds=120):
    return s.pump(seconds, until=lambda: len(s.frames) >= n)


def settle(sess, quiet=1.5, limit=15):
    """Pumps until none of the sessions has sent a frame for `quiet` seconds (H.264's sharpening passes included)."""
    end = time.time() + limit
    last = {id(s): len(s.frames) for s in sess}
    quiet_since = time.time()
    while time.time() < end:
        for s in sess:
            s.pump(0.2)
        now = {id(s): len(s.frames) for s in sess}
        if now != last:
            last, quiet_since = now, time.time()
        elif time.time() - quiet_since >= quiet:
            return True
    return False


def last_digest(s):
    return s.frames[-1][6] if s.frames else None


def tap(sess, x, y):
    for s in sess:
        s.ws.send(f"touch down {x} {y}")
    for s in sess:
        s.ws.send(f"touch up {x} {y}")


def protocol_layer(url, token, app, app2, apk, wait_expiry, fill):
    status, body = sc.http(url, f"/?compare={app},{app}", token)
    check(status == 200 and b"apksim compare" in body and b"apksimCompare" in body, f"/?compare=A,B serves the compare page ({status})")
    check(b"src=" not in body.lower().split(b"<script", 1)[1][:20], "and it loads no external script")
    for bad in ("x", f"{app},{app},{app}", "a/b,c", f"{app},"):
        status, _ = sc.http(url, "/?compare=" + urllib.parse.quote(bad), token)
        check(status == 400, f"/?compare={bad!r} is refused ({status})")
    status, body = sc.http(url, "/", token)
    check(status == 200 and b"apksim compare" not in body, "the plain page is unchanged")

    a, b = sc.Session(url, token, app, None, codec="jpeg"), sc.Session(url, token, app, None, codec="jpeg")
    sess = [a, b]
    try:
        check(all(wait_frames(s, 1) for s in sess), "two sessions of one app both show a first frame")
        st = sessions(url, token)
        check(len(st) >= 2, f"the two count as two sessions on the server ({len(st)})")
        mx = json.loads(sc.http(url, "/api/status", token)[1])["max"]
        check(settle(sess), "both screens settle")
        check(last_digest(a) == last_digest(b), "identical apps, identical screens: the frames are the same bytes")
        n = [len(s.frames) for s in sess]
        tap(sess, *FAB)
        for s in sess:
            s.pump(1.5)
        check(all(len(s.frames) > n[i] for i, s in enumerate(sess)), "a mirrored tap on the FAB gives both new frames")
        settle(sess)
        check(last_digest(a) == last_digest(b), "and they still show the same screen (FAB clicks: 1 in both)")
        a.ws.send("appearance dark")  # the page's 'Drive: A only'
        a.pump(2)
        settle(sess)
        check(last_digest(a) != last_digest(b), "dark on one side only: the screens differ")
        b.ws.send("appearance dark")
        b.pump(2)
        settle(sess)
        check(last_digest(a) == last_digest(b), "dark on both: the same again")
        # With --fill: sessions up to the server's limit, then one more is refused: the compare's two count against it.
        extra = []
        try:
            if not fill:
                raise StopIteration
            for _ in range(mx - 2):
                extra.append(sc.Session(url, token, app, None, codec="jpeg"))
                extra[-1].pump(3)
            over = sc.Session(url, token, app, None, codec="jpeg")
            over.pump(5, until=lambda: any(t.startswith("x ") for t in over.texts))
            check(any(t.startswith("x all") for t in over.texts), f"with {mx - 2} more sessions, one more than --max {mx} is refused")
            over.ws.s.close()
        except StopIteration:
            pass
        finally:
            for e in extra:
                e.ws.s.close()
    finally:
        for s in sess:
            s.ws.s.close()
    end = time.time() + 30
    while time.time() < end and sessions(url, token):
        time.sleep(0.5)
    check(not sessions(url, token), "closing the sockets ends both sessions")

    # A time to live on an added build.
    if apk:
        with open(apk, "rb") as f:
            data = f.read()
        status, body = sc.http(url, "/api/upload?ttl=5", token, data=b"x" * 10)
        check(status == 400 and b"ttl" in body, f"a ttl of 5 s is refused ({status})")
        status, body = sc.http(url, "/api/upload?ttl=soon", token, data=b"x" * 10)
        check(status == 400, f"a ttl that is not a number is refused ({status})")
        ttl = 60
        status, body = sc.http(url, f"/api/upload?ttl={ttl}", token, data=data, headers={"X-Filename": "preview.apk"})
        ident = json.loads(body)["id"] if status == 200 else None
        check(status == 200, f"an upload with ttl={ttl} is stored ({status})")
        if ident:
            status, body = sc.http(url, "/api/apps/" + ident, token)
            left = json.loads(body).get("expiresInS") if status == 200 else None
            check(left is not None and 0 < left <= ttl, f"it reports expiresInS ({left})")
            status, body = sc.http(url, "/api/apps", token, data=json.dumps({"url": "https://example.invalid/a.apk", "ttl": 5}).encode(),
                                   headers={"Content-Type": "application/json"})
            check(status == 400 and b"ttl" in body, f"a JSON add with ttl 5 s is refused too ({status})")
            if wait_expiry:
                time.sleep(ttl + 2)
                status, _ = sc.http(url, "/api/apps/" + ident, token)
                check(status == 404, f"after its time to live the id is gone ({status})")
    preview_layer(url, token, app, apk)


class StubGitHub(http.server.BaseHTTPRequestHandler):
    """The two comment calls of a pull request: what a run posts, and whether the second updates the first."""
    comments = {}
    log = []

    def do_GET(self):
        self.log.append(("GET", self.path))
        body = json.dumps([{"id": i, "body": b, "html_url": f"http://stub/c/{i}", "user": {"login": "github-actions[bot]"}}
                           for i, b in self.comments.items()]).encode()
        self.reply(body)

    def do_POST(self):
        n = int(self.headers["Content-Length"])
        text = json.loads(self.rfile.read(n))["body"]
        i = len(self.comments) + 1
        self.comments[i] = text
        self.log.append(("POST", self.path))
        self.reply(json.dumps({"id": i, "html_url": f"http://stub/c/{i}"}).encode(), 201)

    def do_PATCH(self):
        n = int(self.headers["Content-Length"])
        text = json.loads(self.rfile.read(n))["body"]
        i = int(self.path.rsplit("/", 1)[1])
        self.comments[i] = text
        self.log.append(("PATCH", self.path))
        self.reply(json.dumps({"id": i}).encode())

    def reply(self, body, code=200):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def run_preview(args, env):
    return subprocess.run([sys.executable, os.path.join(HERE, "pr-preview.py"), *args], capture_output=True, text=True,
                          env={**os.environ, **env}, timeout=600)


def preview_layer(url, token, app, apk):
    r = run_preview(["--serve", url, "--base-apk", "x", "--name", "Check", "--dry-run"], {})
    check(r.returncode == 0 and "?compare=BASE_ID%2CBUILD_ID" in r.stdout and "appearance=dark" in r.stdout and "device=tablet" in r.stdout
          and "apksim-preview:default" in r.stdout, "pr-preview.py --dry-run prints the comment with its comparison, dark and tablet links")
    if not apk:
        return
    # A reviewer's comment quoting an earlier preview (the marker inside, not leading) comes first: it is never the one updated.
    quote = "> an earlier preview, quoted: <!-- apksim-preview:default -->"
    StubGitHub.comments, StubGitHub.log = {1: quote}, []
    gh = http.server.ThreadingHTTPServer(("127.0.0.1", 0), StubGitHub)
    threading.Thread(target=gh.serve_forever, daemon=True).start()
    env = {"GITHUB_TOKEN": "gh-stub", "GITHUB_API_URL": f"http://127.0.0.1:{gh.server_port}"}
    if token:
        env["APKSIM_SERVE_TOKEN"] = token
    args = ["--serve", url, "--public-url", "https://sim.example.com/preview", "--apk", apk, "--base-apk", apk, "--name", "PR check",
            "--ttl", "120", "--repo", "o/r", "--pr", "7", "--wait", "120"]
    try:
        r1 = run_preview(args, env)
        check(r1.returncode == 0 and "posted" in r1.stdout, f"pr-preview.py adds the builds and posts the comment ({r1.stdout.strip()[:80]} {r1.stderr.strip()[:120]})")
        r2 = run_preview(args, env)
        check(r2.returncode == 0 and "updated" in r2.stdout and len(StubGitHub.comments) == 2 and StubGitHub.comments[1] == quote
              and [m for m, _ in StubGitHub.log if m in ("POST", "PATCH")] == ["POST", "PATCH"],
              f"a second run updates that comment in place, not a reviewer's quote of it ({[m for m, _ in StubGitHub.log]})")
        body = StubGitHub.comments.get(2, "")
        check("https://sim.example.com/preview/?compare=" in body and (not token or token not in body), "its links use the public address and carry no token")
        ids = __import__("re").findall(r"\?app=(u[0-9a-f]{32})", body)
        check(bool(ids), "the comment links to the build by its id")
        if ids:
            status, resp = sc.http(url, "/api/apps/" + ids[0], token)
            check(status == 200 and json.loads(resp).get("expiresInS", 0) > 0, f"the build in the comment is on the server with a time to live ({status})")
    finally:
        gh.shutdown()


# ---------------------------------------------------------------- the page, in Chrome
def page_layer(url, token, app, app2, chrome):
    bp = load("bp", "beam-parity.py")
    cp = bp.free_port()
    prof = tempfile.mkdtemp(prefix="compare-chrome-")
    proc = subprocess.Popen([chrome or bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run",
                             "--no-default-browser-check", "--disable-backgrounding-occluded-windows",
                             "--disable-renderer-backgrounding", "--window-size=1500,1000", "about:blank"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        end = time.time() + 20
        while time.time() < end:
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{cp}/json/version", timeout=2)
                break
            except OSError:
                time.sleep(0.3)
        cdp = bp.Cdp(cp)
        page_run(cdp, url, token, app, None, "jpeg")
        page_run(cdp, url, token, app, None, "h264")
        if app2:
            page_run(cdp, url, token, app, app2, "jpeg")
        if token:
            page_run(cdp, url, token, app, None, "jpeg", embed=True)
    finally:
        proc.terminate()


def page_run(cdp, url, token, app, app2, codec, embed=False):
    t = cdp.call("Target.createTarget", {"url": "about:blank"})
    s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
    tail = ("&codec=jpeg" if codec == "jpeg" else "") + ("&embed=1" if embed else "")
    q = f"compare={app},{app2 or app}&labels=Base,New{tail}"
    cdp.call("Page.navigate", {"url": f"{url}/?{('token=' + token + '&') if token else ''}{q}"}, s)

    def info(recompute=True):
        return json.loads(cdp.ev(s, f"JSON.stringify(window.apksimCompare({'true' if recompute else ''}))"))

    def counts():
        i = info(False)
        return i["a"]["frames"], i["b"]["frames"]

    def wait(cond, seconds):
        end = time.time() + seconds
        while time.time() < end:
            if cond():
                return True
            time.sleep(0.3)
        return cond()

    def settled(quiet=1.5, limit=20):
        last, since, end = counts(), time.time(), time.time() + limit
        while time.time() < end:
            time.sleep(0.25)
            now = counts()
            if now != last:
                last, since = now, time.time()
            elif time.time() - since >= quiet:
                return True
        return False

    def click(canvas_id, fx, fy):
        r = json.loads(cdp.ev(s, f"JSON.stringify(document.getElementById('{canvas_id}').getBoundingClientRect())"))
        x, y = r["x"] + fx * r["width"], r["y"] + fy * r["height"]
        for typ in ("mousePressed", "mouseReleased"):
            cdp.call("Input.dispatchMouseEvent", {"type": typ, "x": x, "y": y, "button": "left", "clickCount": 1}, s)

    label = f"[{codec}{' embedded' if embed else ''}] "
    ok = wait(lambda: min(counts()) >= 1, 120)
    check(ok, label + "the page shows both sessions' first frames")
    if embed:  # served, not redirected: no cookie, the token goes on the sockets as ?token=
        check(token not in cdp.ev(s, "location.href"), label + "the token is taken out of the address")
        cdp.call("Target.closeTarget", {"targetId": t["targetId"]})
        return
    check(info()["a"]["codec"] == codec, label + f"the stream is {codec} ({info()['a']['codec']})")
    settled()
    i = info()
    if app2:  # two apps whose launch options differ (the second has --status-bar off): their home screens differ, input is still mirrored
        check(i["diff"]["computed"] and i["diff"]["count"] > 0 and i["diff"]["box"] is not None,
              label + f"two apps whose launch options differ: {i['diff']['count']} pixels differ, box {i['diff']['box']}")
        n = counts()
        click("cvA", FAB[0] / 822, FAB[1] / 1782)
        check(wait(lambda: counts()[0] > n[0] and counts()[1] > n[1], 10), label + "and a tap still reaches both")
        cdp.call("Target.closeTarget", {"targetId": t["targetId"]})
        return
    check(i["diff"]["computed"] and i["diff"]["count"] == 0 and i["diff"]["total"] == 822 * 1782,
          label + f"the same app twice: difference {i['diff']['count']} of {i['diff']['total']} pixels")
    n = counts()
    click("cvA", FAB[0] / 822, FAB[1] / 1782)
    check(wait(lambda: counts()[0] > n[0] and counts()[1] > n[1], 10), label + "a tap on A's screen gave both sessions new frames")
    settled()
    i = info()
    check(i["diff"]["count"] == 0, label + f"and the screens still match ({i['diff']['count']})")
    n = counts()
    click("cvB", FAB[0] / 822, FAB[1] / 1782)
    check(wait(lambda: counts()[0] > n[0] and counts()[1] > n[1], 10), label + "a tap on B's screen reaches both too")
    settled()
    n = counts()
    cdp.ev(s, "document.getElementById('drive').value = 'a'; document.getElementById('dark').click(); 1")
    check(wait(lambda: counts()[0] > n[0], 15), label + "Drive A only, Dark: A changes")
    settled(2.5)
    i = info()
    check(i["diff"]["count"] > 100000 and i["diff"]["box"] is not None,
          label + f"the difference is counted: {i['diff']['count']} pixels, box {i['diff']['box']}")
    n = counts()
    # B follows: the checkbox is on already, so switch it off and on again to send "appearance dark" to B alone.
    cdp.ev(s, "(() => { document.getElementById('drive').value = 'b'; const d = document.getElementById('dark'); d.checked = false; d.click(); })()")
    wait(lambda: counts()[1] > n[1], 15)
    settled(2.5)
    check(info()["diff"]["count"] == 0, label + f"both dark: difference {info()['diff']['count']}")
    cdp.ev(s, "document.getElementById('drive').value = 'both'; 1")
    # Different sizes: the tablet on A only.
    cdp.ev(s, "(() => { document.getElementById('drive').value = 'a'; const d = document.getElementById('device'); d.value = 'tablet'; d.onchange(); })()")
    check(wait(lambda: not info()["diff"]["sameSize"], 20), label + "A on a tablet, B on a phone: reported as different sizes")
    # One ends, both end.
    cdp.ev(s, "A.sock.close(); 1")
    check(wait(lambda: info(False)["ended"], 10), label + "one session's socket closing ends the comparison")
    check(wait(lambda: not sessions(url, token), 30), label + "and both sessions are gone from the server")
    cdp.call("Target.closeTarget", {"targetId": t["targetId"]})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=__import__("argparse").RawDescriptionHelpFormatter)
    ap.add_argument("url")
    ap.add_argument("--token")
    ap.add_argument("--app")
    ap.add_argument("--app2")
    ap.add_argument("--apk", help="the test app's APK: adds the ttl and pr-preview.py checks that add a build")
    ap.add_argument("--chrome", nargs="?", const="", default=None, help="also check the page in Chrome (the path of the executable, else beam-parity's)")
    ap.add_argument("--fill", action="store_true", help="open sessions up to the server's --max and see one more refused (memory: --max sessions at once)")
    ap.add_argument("--wait-expiry", action="store_true", help="wait out a 60 s time to live and see the id gone")
    a = ap.parse_args()
    url = a.url.rstrip("/")
    token = open(a.token).readline().strip() if a.token else None
    status, body = sc.http(url, "/api/apps", token)
    apps = json.loads(body) if status == 200 else []
    app = a.app or (apps[0]["id"] if apps else None)
    if not app:
        sys.exit("compare-check.py: the server lists no app (--apps), and --app names none")
    protocol_layer(url, token, app, a.app2, a.apk, a.wait_expiry, a.fill)
    if a.chrome is not None:
        page_layer(url, token, app, a.app2, a.chrome or None)
    sc.finish()


if __name__ == "__main__":
    main()
