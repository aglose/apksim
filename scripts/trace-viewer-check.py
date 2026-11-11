#!/usr/bin/env python3
"""Checks the trace viewer (docs/trace-viewer.md) against a real flight recording and a real failure bundle.

Usage: trace-viewer-check.py APP.apk TEST.apk [OUT] [--chrome PATH] [--skip-make]
       trace-viewer-check.py --csp-only          only the CSP hashes (CI's smoke job)
       trace-viewer-check.py --update-csp        rewrite the page's CSP hashes after editing its script or style

1. Makes a recording with scripts/rec-check.py (the test app, `--rec all`) and a failure bundle with instrument.py
   (`-e failOnPurpose true --bundles`), zipped. --skip-make reuses OUT/rec and OUT/bundle.zip.
2. Without Chrome (or when it is not installed) parses both formats with a small script that mirrors the page: the
   zip, manifest, the AVI's frames, a touch in input.jsonl, a network row and a log line.
3. With Chrome (headless, DevTools protocol) opens the page: the recording through `apksim rec view` (the loopback
   route) and the bundle through the file picker, and asserts frames, an input marker on the frame, a network row
   with its headers and body, a log line, the filters, a checkpoint, and for the bundle its failure, screen, nodes,
   threads and looper. Also: the CSP hashes match the page (the script runs at all), no CSP violation, a hostile
   recording (markup in every field) renders as text and runs nothing, and a large synthetic recording (50,000 log
   lines, 20,000 input events, 3,000 frames) loads and scrubs; load times are printed.
OUT defaults to work/trace-viewer-check and is emptied first.
"""
import argparse, base64, hashlib, importlib.util, json, os, re, shutil, struct, subprocess, sys, tempfile, time, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE = os.path.join(ROOT, "runtime/src/main/resources/dev/apksim/serve/trace-viewer.html")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
OK = True


def check(c, what):
    global OK
    OK &= bool(c)
    print(("ok   " if c else "FAIL ") + what, flush=True)
    return c


def hashes(html):
    s = re.search(r"<script>(.*?)</script>", html, re.S).group(1)
    st = re.search(r"<style>(.*?)</style>", html, re.S).group(1)
    h = lambda x: "'sha256-" + base64.b64encode(hashlib.sha256(x.encode()).digest()).decode() + "'"
    return h(s), h(st)


def update_csp():
    html = open(PAGE).read()
    hs, hst = hashes(html)
    html = re.sub(r"script-src '[^']*'", "script-src " + hs, html, 1)
    html = re.sub(r"style-src '[^']*'", "style-src " + hst, html, 1)
    open(PAGE, "w").write(html)
    print("CSP hashes updated")


def csp_ok():
    html = open(PAGE).read()
    hs, hst = hashes(html)
    return f"script-src {hs};" in html and f"style-src {hst};" in html


# ---------- what the page reads, in Python ----------
LOG = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) +(\d+) +(\d+) ([VDIWEF]) (.*?)\s*: (.*)$")


def avi_frames(data):
    n, pos = [0], 12
    def walk(p, end):
        while p + 8 <= end:
            cid, sz = data[p:p + 4], struct.unpack("<I", data[p + 4:p + 8])[0]
            if cid == b"LIST":
                if data[p + 8:p + 12] in (b"movi", b"hdrl"):
                    walk(p + 12, min(end, p + 8 + sz))
            elif cid[2:] in (b"dc", b"db"):
                n[0] += 1
            p += 8 + sz + (sz & 1)
    walk(12, len(data))
    return n[0]


def parse_recording(path):
    z = zipfile.ZipFile(path)
    man = json.loads(z.read("manifest.json"))
    r = {"format": man["format"], "frames": avi_frames(z.read("screen.avi")) if "screen.avi" in z.namelist() else 0}
    inp = [json.loads(l) for l in z.read("input.jsonl").decode().splitlines() if l]
    r["touch"] = next((e for e in inp if e.get("ev") == "down"), None)
    r["input"] = len(inp)
    log = [LOG.match(l) for l in z.read("logcat.txt").decode().splitlines() if l]
    r["log"] = sum(1 for m in log if m)
    r["errors"] = sum(1 for m in log if m and m.group(9) in "EF")
    r["warns"] = sum(1 for m in log if m and m.group(9) in "WEF")
    har = json.loads(z.read("network.har"))["log"]["entries"]
    r["net"] = [e["request"]["url"] for e in har]
    r["checkpoint"] = man.get("checkpoint")
    return r


def make_bundle_zip(src, dst):
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(os.listdir(src)):
            z.write(os.path.join(src, f), f)


# ---------- making the artifacts ----------
def make(app, test, out):
    env = dict(os.environ, RECCHECK_PORT=os.environ.get("RECCHECK_PORT", "7497"))
    r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/rec-check.py"), app, os.path.join(out, "rec")], env=env,
                       capture_output=True, text=True)
    rec = os.path.join(out, "rec/bundles/window.apkrec")
    check(os.path.isfile(rec), "rec-check.py made a recording" + ("" if os.path.isfile(rec) else ": " + r.stdout[-400:]))
    bdir = os.path.join(out, "bundles")
    r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/instrument.py"), app, test, "--xml", os.path.join(out, "t.xml"),
                        "--bundles", bdir, "-e", "failOnPurpose", "true", "-e", "class", "dev.apksim.composeapp.FailureBundleTest",
                        "-e", "bundleDir", bdir], capture_output=True, text=True)
    found = [os.path.dirname(os.path.join(d, f)) for d, _, fs in os.walk(bdir) for f in fs if f == "failure.txt"]
    if check(bool(found), "instrument.py --bundles left a failure bundle" + ("" if found else ": " + r.stderr[-300:])):
        make_bundle_zip(found[0], os.path.join(out, "bundle.zip"))
    return rec, os.path.join(out, "bundle.zip")


# ---------- synthetic recordings ----------
def synth(src, dst, hostile=False, big=False):
    zin = zipfile.ZipFile(src)
    man = json.loads(zin.read("manifest.json"))
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for n in zin.namelist():
            if n.startswith("checkpoint/"):
                continue
            data = zin.read(n)
            if n == "manifest.json":
                man["package"] = "<img src=x onerror=window.__pwned=1>"
                data = json.dumps(man).encode()
            elif n == "logcat.txt" and hostile:
                data = data + b"\n11-10 10:00:00.000  1  1 E <b>EVIL</b>: <img src=x onerror=window.__pwned=1> " + b"</script>\n"
            elif n == "network.har" and hostile:
                har = json.loads(data)
                e = har["log"]["entries"][0]
                e["request"]["url"] = "http://evil.test/<img src=x onerror=window.__pwned=1>"
                e["response"]["content"] = {"mimeType": "text/html", "text": "<script>window.__pwned=1</script>"}
                data = json.dumps(har).encode()
            elif big and n == "logcat.txt":
                lines = data.decode().splitlines()
                lines = [l for l in lines if LOG.match(l)] or ["01-01 00:00:00.000  1  1 I T: x"]
                data = ("\n".join(lines[i % len(lines)] for i in range(50000)) + "\n").encode()
            elif big and n == "input.jsonl":
                base = [json.loads(l) for l in data.decode().splitlines() if l and "w" in l][:1]
                w0, w1 = man["window"]["from"], man["window"]["to"]
                data = "\n".join(json.dumps({"t": i, "w": w0 + (w1 - w0) * i // 20000, "ev": "down" if i % 2 == 0 else "up",
                                             "type": 0, "x": 300 + i % 200, "y": 900, "v": 0}) for i in range(20000)).encode()
            elif big and n == "screen.avi":
                data = grow_avi(data, 3000)
            z.writestr(n, data)


def grow_avi(data, n):
    """The AVI with its first frame repeated n times (a valid RIFF is enough for the page: it indexes '00dc' chunks)."""
    i = data.index(b"00dc")
    sz = struct.unpack("<I", data[i + 4:i + 8])[0]
    chunk = data[i:i + 8 + sz + (sz & 1)]
    movi = b"movi" + chunk * n
    hdrl = data[12:data.index(b"movi") - 8]
    return b"RIFF" + struct.pack("<I", 4 + len(hdrl) + 8 + len(movi)) + b"AVI " + hdrl + b"LIST" + struct.pack("<I", len(movi)) + movi


# ---------- Chrome ----------
def drive_chrome(a, rec, bundle, out):
    spec = importlib.util.spec_from_file_location("bp", os.path.join(ROOT, "scripts/beam-parity.py"))
    bp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bp)
    cp = bp.free_port()
    prof = tempfile.mkdtemp(prefix="trace-viewer-chrome-")
    chrome = subprocess.Popen([a.chrome, "--headless=new", f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run",
                               "--window-size=1300,1000", "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    procs = [chrome]
    try:
        for _ in range(100):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{cp}/json/version", timeout=1)
                break
            except Exception:
                time.sleep(0.2)
        cdp = bp.Cdp(cp)

        def tab(url):
            t = cdp.call("Target.createTarget", {"url": "about:blank"})
            s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
            cdp.call("Page.enable", {}, s)
            cdp.call("Page.addScriptToEvaluateOnNewDocument", {"source": "window.__csp=[];document.addEventListener('securitypolicyviolation',"
                     "e=>window.__csp.push(e.violatedDirective+' '+e.blockedURI));"}, s)
            cdp.call("Page.navigate", {"url": url}, s)
            return s

        def ev(s, e):
            return cdp.ev(s, e)

        def wait(s, expr, seconds=60):
            end = time.time() + seconds
            while time.time() < end:
                try:
                    if ev(s, expr):
                        return True
                except RuntimeError:
                    pass
                time.sleep(0.25)
            return False

        def pick(s, path):
            doc = cdp.call("DOM.getDocument", {}, s)
            node = cdp.call("DOM.querySelector", {"nodeId": doc["root"]["nodeId"], "selector": "#file"}, s)["nodeId"]
            cdp.call("DOM.setFileInputFiles", {"files": [path], "nodeId": node}, s)

        # --- the recording, through `apksim rec view` ---
        view = subprocess.Popen([os.path.join(ROOT, "apksim"), "rec", "view", "--no-open", "--linger", "30", rec], stdout=subprocess.PIPE, text=True)
        procs.append(view)
        url = view.stdout.readline().strip()
        check(url.startswith("http://127.0.0.1:") and "?src=/rec/" in url, "apksim rec view prints a loopback URL: " + url.split("?")[0])
        s = tab(url)
        check(wait(s, "!!(window.__trace && window.__trace.summary() && window.__trace.summary().kind==='recording')"), "the recording loads through rec view")
        sm = ev(s, "window.__trace.summary()")
        print("     summary:", sm)
        ref = parse_recording(rec)
        check(sm["frames"] > 0 and sm["frames"] == ref["frames"], f"frames indexed: {sm['frames']} (script: {ref['frames']})")
        check(sm["input"] == ref["input"] and sm["input"] > 0, f"input events: {sm['input']}")
        check(sm["net"] == len(ref["net"]) and sm["net"] >= 2, f"network exchanges: {sm['net']}")
        check(sm["log"] >= ref["log"] > 0, f"log lines: {sm['log']}")
        check(sm["checkpoints"] == 1, "the checkpoint is on the timeline")
        check(ev(s, "window.__csp.length") == 0, "no CSP violation (the hashes match the page)")
        # a touch: scrub to it and the marker is on the frame
        t_down = ref["touch"]["w"]
        ev(s, f"window.__trace.setTime({t_down}); 1")
        check(wait(s, "document.getElementById('frame').naturalWidth > 0", 10), "a frame decodes and shows")
        check(ev(s, "document.querySelectorAll('#marks .mk').length") >= 1, "the touch is marked on the frame where it landed")
        check(ev(s, "document.querySelector('#p-input .row.cur') && document.querySelector('#p-input .row.cur').textContent.indexOf('down')>=0"),
              "the input pane lines up on that moment")
        # network: list, then headers and body on click
        ev(s, "document.querySelector('#tabs button[data-id=net]').click(); 1")
        row = ev(s, "[...document.querySelectorAll('#p-net .vl .row')].map(r=>r.textContent).find(t=>t.indexOf('/')>=0)")
        check(bool(row) and re.search(r"GET|POST", row), "a network row renders: " + (row or "")[:70])
        ev(s, "document.querySelector('#p-net .vl .row').click(); 1")
        check("User-Agent" in (ev(s, "document.getElementById('nbody').textContent") or "") or ":" in (ev(s, "document.getElementById('nbody').textContent") or ""),
              "a click shows the request headers")
        ev(s, "[...document.querySelectorAll('#ndet button')][2].click(); 1")
        body = ev(s, "document.getElementById('nbody').textContent")
        check(bool(body) and "(no body" not in body, "and the response body: " + (body or "")[:50].replace("\n", " "))
        # logcat and filters
        ev(s, "document.querySelector('#tabs button[data-id=log]').click(); 1")
        n_all = ev(s, "document.querySelectorAll('#p-log .vl .row').length")
        check(n_all > 0, "log lines render (only the visible ones are in the DOM: %d rows)" % n_all)
        cnt0 = ev(s, "document.querySelector('#p-log .tools span').textContent")
        ev(s, "(()=>{const i=document.querySelector('#p-log .tools select');i.value='E';i.dispatchEvent(new Event('input'))})()")
        cnt1 = ev(s, "document.querySelector('#p-log .tools span').textContent")
        check(cnt1.startswith(f"{ref['errors']} of {ref['log']} "), f"level filter E keeps the {ref['errors']} error lines: {cnt0} -> {cnt1}")
        ev(s, "(()=>{const i=document.querySelector('#p-log .tools select');i.value='W';i.dispatchEvent(new Event('input'))})()")
        cnt2 = ev(s, "document.querySelector('#p-log .tools span').textContent")
        check(cnt2.startswith(f"{ref['warns']} of {ref['log']} "), f"level filter W keeps {ref['warns']}: {cnt2}")
        ev(s, "(()=>{const i=document.querySelector('#p-log .tools select');i.value='V';i.dispatchEvent(new Event('input'));"
              "const t=document.querySelectorAll('#p-log .tools input')[0];t.value='zzzz-no-such-tag';t.dispatchEvent(new Event('input'))})()")
        check(ev(s, "document.querySelector('#p-log .tools span').textContent").startswith("0 of"), "tag filter narrows to nothing for an unknown tag")
        ev(s, "document.querySelector('#tabs button[data-id=db]').click(); 1")
        check(ev(s, "window.__trace.summary().db") >= 1, "SQLite calls load: %d" % ev(s, "window.__trace.summary().db"))
        ev(s, "document.querySelector('#tabs button[data-id=cp]').click(); 1")
        check("MainActivity" in ev(s, "document.getElementById('cpd').textContent"), "the checkpoint pane shows its activity stack")

        # --- the failure bundle, through the file picker on file:// ---
        s2 = tab("file://" + PAGE)
        wait(s2, "!!window.__trace")
        pick(s2, bundle)
        check(wait(s2, "document.body.classList.contains('loaded')"), "the failure bundle loads from the picker")
        tabs = ev(s2, "[...document.querySelectorAll('#tabs button')].map(b=>b.textContent)")
        for want in ("Failure", "Screen", "nodes.txt", "threads.txt", "looper.txt"):
            check(want in tabs, f"bundle tab {want}")
        ev(s2, "document.querySelector('#tabs button[data-id=fail]').click(); 1")
        check(len(ev(s2, "document.querySelector('#p-fail pre').textContent")) > 20, "failure.txt shows")
        check(wait(s2, "document.getElementById('frame').naturalWidth > 0", 10), "the bundle's screen.png decodes")
        check(ev(s2, "window.__csp.length") == 0, "no CSP violation on file://")

        # --- a hostile recording ---
        hostile = os.path.join(out, "hostile.apkrec")
        synth(rec, hostile, hostile=True)
        s3 = tab("file://" + PAGE)
        wait(s3, "!!window.__trace")
        pick(s3, hostile)
        check(wait(s3, "document.body.classList.contains('loaded')"), "the hostile recording loads")
        ev(s3, "document.querySelector('#tabs button[data-id=net]').click(); 1")
        ev(s3, "document.querySelector('#p-net .vl .row').click(); 1")
        ev(s3, "[...document.querySelectorAll('#ndet button')][2].click(); 1")
        time.sleep(0.5)
        check(ev(s3, "!window.__pwned"), "markup in the data ran nothing")
        check(ev(s3, "document.querySelector('img[src=x]') === null && document.querySelectorAll('#app b').length === 0"), "and made no elements")
        check("<img src=x" in ev(s3, "document.getElementById('app').textContent") or "<img src=x" in ev(s3, "document.getElementById('nbody').textContent"),
              "the text is shown literally")

        # --- a large recording ---
        big = os.path.join(out, "big.apkrec")
        synth(rec, big, big=True)
        s4 = tab("file://" + PAGE)
        wait(s4, "!!window.__trace")
        pick(s4, big)
        check(wait(s4, "document.body.classList.contains('loaded')", 120), "the large recording loads (%.1f MB)" % (os.path.getsize(big) / 1e6))
        sm = ev(s4, "window.__trace.summary()")
        print("     large:", sm)
        check(sm["log"] >= 50000 and sm["input"] >= 20000 and sm["frames"] >= 3000, "all of it indexed")
        t0 = time.time()
        for k in range(20):
            ev(s4, f"window.__trace.setTime({sm['t0']} + ({sm['t1']} - {sm['t0']}) * {k} / 20); 1")
        per = (time.time() - t0) / 20 * 1000
        check(per < 150, "scrubbing the large recording: %.0f ms a step" % per)
        ev(s4, "document.querySelector('#tabs button[data-id=log]').click(); 1")
        check(ev(s4, "document.querySelectorAll('#p-log .vl .row').length") < 200, "the log pane holds only the visible rows")
        print("     LOAD MS: real recording %s, large %s, bundle %s" % (sm0["loadMs"] if (sm0 := ev(s, "window.__trace.summary()")) else "?", sm["loadMs"],
              ev(s2, "window.__trace.summary()") and "n/a"))
    finally:
        for p in procs:
            p.terminate()
        shutil.rmtree(prof, ignore_errors=True)


def main():
    if "--update-csp" in sys.argv:
        return update_csp()
    if "--csp-only" in sys.argv:  # CI's smoke job: the page's hashes match (the rest needs the Mac and Chrome)
        check(csp_ok(), "trace-viewer.html's CSP hashes match its script and style")
        return 0 if OK else 1
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("app")
    ap.add_argument("test")
    ap.add_argument("out", nargs="?", default=os.path.join(ROOT, "work/trace-viewer-check"))
    ap.add_argument("--chrome", default=os.environ.get("CHROME", CHROME))
    ap.add_argument("--skip-make", action="store_true")
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    if not a.skip_make:
        shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out, exist_ok=True)
    check(csp_ok(), "the page's CSP hashes match its script and style (run with --update-csp after editing them)")
    if a.skip_make:
        rec, bundle = os.path.join(out, "rec/bundles/window.apkrec"), os.path.join(out, "bundle.zip")
    else:
        rec, bundle = make(os.path.abspath(a.app), os.path.abspath(a.test), out)
    if not (os.path.isfile(rec) and os.path.isfile(bundle)):
        print("FAIL nothing to open")
        return 1
    ref = parse_recording(rec)
    check(ref["frames"] > 0 and ref["touch"] and ref["log"] > 0 and any("/" in u for u in ref["net"]) and ref["checkpoint"],
          f"the formats parse (script): {ref['frames']} frames, {ref['input']} inputs, {len(ref['net'])} exchanges, {ref['log']} log lines")
    names = zipfile.ZipFile(bundle).namelist()
    check({"failure.txt", "screen.png", "nodes.txt", "threads.txt", "looper.txt"} <= set(names), "the bundle zip holds the failure files")
    if os.path.exists(a.chrome):
        drive_chrome(a, rec, bundle, out)
    else:
        print("note: Chrome not found (%s): only the script-level parse ran" % a.chrome)
    print("trace viewer check: " + ("passed" if OK else "FAILED"))
    return 0 if OK else 1


if __name__ == "__main__":
    sys.exit(main())
