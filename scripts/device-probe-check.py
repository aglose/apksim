#!/usr/bin/env python3
"""Runs DeviceProbeTest#probe on apksim and compares it with a device's table (docs/conformance.md "Device-difference probe").

  scripts/device-probe-check.py APP.apk TEST.apk [--apksim CMD] [--table FILE] [--known FILE] [--os mac|linux]
                                [--show-known] [--from FILE] [-- APKSIM_OPTION...]

Runs `CMD --instrument TEST.apk -e class dev.apksim.composeapp.DeviceProbeTest APP.apk` (CMD is ./apksim, or a
`docker run ...` line for the Linux image), reads its `probe.KEY=VALUE` status lines and compares them with
testapks/composeapp/device-probe-api36.tsv (made by scripts/gen-device-probe.py on an API 36 emulator). Prints every
key whose value differs (device value, apksim's value) and exits 1 for
  - a difference that device-probe-known.tsv (KEY, apksim's value, reason) does not list with that value,
  - a known difference that no longer differs (a fix landed: delete its line, so the list only shrinks),
  - a key missing from either side.
A known line whose reason starts with `linux:` is a difference of the Linux image only, `mac:` of the Mac only: on the
other OS it is neither required to differ nor refused (--os says which one this run is: linux when this script runs on
Linux or CMD is a container run, else mac). --from FILE reads a saved run's output instead of running it.
"""
import argparse, os, pathlib, re, shlex, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TESTAPP = ROOT / "testapks/composeapp"
CLASS = "dev.apksim.composeapp.DeviceProbeTest"


def parse(text):
    rows = {}
    for line in text.splitlines():
        m = re.match(r"(?:INSTRUMENTATION_STATUS: )?probe\.(.+?)=(.*)$", line.rstrip("\r"))
        if m:
            rows[m.group(1)] = m.group(2)
    return rows


def table(path, cols):
    rows = {}
    for n, line in enumerate(pathlib.Path(path).read_text().splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        f = line.split("\t")
        if len(f) < cols:
            raise SystemExit("%s:%d: expected %d tab-separated fields" % (path, n, cols))
        rows[f[0]] = f[1:]
    return rows


def main():
    argv = sys.argv[1:]
    opts = []
    if "--" in argv:
        i = argv.index("--")
        argv, opts = argv[:i], argv[i + 1:]
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("app", nargs="?")
    ap.add_argument("test", nargs="?")
    ap.add_argument("--apksim", default=str(ROOT / "apksim"))
    ap.add_argument("--table", default=str(TESTAPP / "device-probe-api36.tsv"))
    ap.add_argument("--known", default=str(TESTAPP / "device-probe-known.tsv"))
    ap.add_argument("--os", choices=["mac", "linux"])
    ap.add_argument("--show-known", action="store_true", help="also list the known differences")
    ap.add_argument("--from", dest="src", help="read a saved run's output")
    a = ap.parse_args(argv)
    words = shlex.split(a.apksim)
    host = a.os or ("linux" if sys.platform.startswith("linux") or any(os.path.basename(w) in ("docker", "podman") for w in words) else "mac")
    if a.src:
        text = pathlib.Path(a.src).read_text()
    else:
        if not (a.app and a.test):
            ap.error("APP.apk and TEST.apk are required")
        r = subprocess.run(words + ["--instrument", a.test, "-e", "class", CLASS] + opts + [a.app], capture_output=True, text=True)
        text = r.stdout
        if "probe.wifi.state=" not in text:
            sys.stderr.write(r.stderr[-3000:] + "\n" + text[-3000:] + "\n")
            raise SystemExit("the probe printed no values (exit %d)" % r.returncode)
    got = parse(text)
    want = {k: v[0] for k, v in table(a.table, 2).items()}
    known = {k: (v[0], v[1] if len(v) > 1 else "") for k, v in table(a.known, 3).items()}

    def other_os(why):
        return (why.startswith("linux:") and host != "linux") or (why.startswith("mac:") and host != "mac")

    differing = {}
    for k in sorted(set(want) | set(got)):
        d, s = want.get(k, "<missing>"), got.get(k, "<missing>")
        if d != s:
            differing[k] = (d, s)
    print("%d probe values on apksim, %d in the device table, %d differ" % (len(got), len(want), len(differing)))
    bad = []
    for k, (d, s) in differing.items():
        kv = known.get(k)
        if kv is None:
            bad.append("new difference: %s (device %s, apksim %s)" % (k, d, s))
            tag = "NEW   "
        elif kv[0] != s:
            if other_os(kv[1]):
                bad.append("new difference: %s (device %s, apksim %s; known only for the other OS)" % (k, d, s))
            else:
                bad.append("known difference changed: %s (known apksim %s, now %s)" % (k, kv[0], s))
            tag = "MOVED "
        else:
            tag = "known "
        if tag != "known " or a.show_known:
            print("%s%-48s device=%-14s apksim=%s%s" % (tag, k, d, s, "" if kv is None else "   # " + kv[1]))
    for k, (v, why) in known.items():
        if k not in differing and not other_os(why):
            bad.append("known difference no longer differs: %s (delete its line from %s)" % (k, os.path.basename(a.known)))
    if bad:
        print("\nFAIL")
        for b in bad:
            print("  " + b)
        sys.exit(1)
    print("ok: every difference is a known one")


main()
