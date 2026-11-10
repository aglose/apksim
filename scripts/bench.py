#!/usr/bin/env python3
"""Times launches: Enter to window, and Enter to first app frame. Usage: bench.py RUNS APK [APK...] [--cold|--warm] [--mem]

Cold launches start a JVM (APKSIM_STANDBY=0). Warm ones are handed to a standby (Standby.java), the normal case on a
Mac once anything has launched from this folder: each waits until the standby is ready, as it is when launches are
seconds apart. Both are measured unless --cold or --warm is given.

--mem also reports memory: each launch then runs headless with a control port and, 5 s after its first frame, reads
`stats` footprintMb (the physical footprint, as `footprint PID` shows it) before quitting; with warm launches, the
standby's footprint too."""
import os, re, socket, statistics, subprocess, sys, threading, time
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
args = [a for a in sys.argv[1:] if not a.startswith("--")]
modes = [m for m in ("cold", "warm") if "--" + m in sys.argv] or ["cold", "warm"]
runs = int(args[0])
launcher = os.path.join(root, "apksim")
client = os.path.join(root, "work", "runtime", "apksim-client")
mem = "--mem" in sys.argv


def ctl(port, cmd):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as s:
        s.sendall((cmd + "\n").encode())
        return s.makefile().readline().strip()


def footprint_mb(pid):
    """A process's physical footprint (macOS `footprint`), or None."""
    out = subprocess.run(["footprint", str(pid)], capture_output=True, text=True).stdout
    m = re.search(r"Footprint: ([\d.]+) (MB|GB|KB)", out)
    return None if not m else float(m[1]) * {"KB": 1 / 1024, "MB": 1, "GB": 1024}[m[2]]


def launch_mem(apk, env):
    """One launch, held 5 s past its first frame: (window ms, first frame ms, footprint MB)."""
    p = subprocess.Popen([launcher, "--no-network", "--headless", "--control", "auto", apk], stderr=subprocess.PIPE,
                         stdout=subprocess.DEVNULL, text=True, env=env)
    marks = {}
    first_frame = threading.Event()

    def drain():
        # Read stderr for the whole launch: a pipe left full would block the app's logging, and `stats` or `quit` with it.
        for line in p.stderr:
            for key, pattern in (("port", r"control port (\d+)"), ("window", r"window-open\s+(\d+) ms"),
                                 ("frame", r"first-frame\s+(\d+) ms")):
                if key not in marks and (m := re.search(pattern, line)):
                    marks[key] = int(m[1])
            if "frame" in marks:
                first_frame.set()
        first_frame.set()  # the process ended without one

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    fp = None
    try:
        first_frame.wait(120)
        if "port" in marks and "frame" in marks:
            time.sleep(5)
            fp = int(re.search(r"footprintMb=(-?\d+)", ctl(marks["port"], "stats"))[1])
            ctl(marks["port"], "quit")
            p.wait(10)
    except (OSError, subprocess.TimeoutExpired, TypeError):
        pass
    finally:
        if p.poll() is None:
            p.kill()
        p.wait()
        reader.join(5)
    return marks.get("window"), marks.get("frame"), fp


def standby_ready(timeout=20):
    sock = subprocess.run([client, "sock", root, "n"], capture_output=True, text=True).stdout.strip()
    if not os.path.exists(sock):
        subprocess.run([launcher, "--spawn-standby", "n"])
    end = time.time() + timeout
    while time.time() < end and not os.path.exists(sock):
        time.sleep(0.05)
    time.sleep(0.3)
    return os.path.exists(sock)


for apk in args[1:]:
    for mode in modes:
        if mode == "warm" and not os.path.exists(client):
            print(f"{apk.split('/')[-1]:32} warm: no standby client (scripts/build-native.sh)")
            continue
        window, frame, fps, standbys = [], [], [], []
        for _ in range(runs):
            env = dict(os.environ)
            if mode == "cold":
                env["APKSIM_STANDBY"] = "0"
            elif not standby_ready():
                print("standby did not come up", file=sys.stderr)
                break
            if mem and mode == "warm":
                sock = subprocess.run([client, "sock", root, "n"], capture_output=True, text=True).stdout.strip()
                try:
                    standbys.append(footprint_mb(int(open(sock + ".pid").read().strip())))
                except (OSError, ValueError):
                    pass
            env["APKSIM_T0"] = str(int(time.time() * 1000))
            if mem:
                w, f, fp = launch_mem(apk, env)
                if fp is not None: fps.append(fp)
            else:
                p = subprocess.run([launcher, "--no-network", "--exit-after-first-frame", apk], capture_output=True, text=True, env=env)
                w = re.search(r"window-open\s+(\d+) ms", p.stderr)
                f = re.search(r"first-frame\s+(\d+) ms", p.stderr)
                w, f = w and int(w[1]), f and int(f[1])
            # Headless (--mem) has no window-open mark.
            if f is not None and (w is not None or mem):
                frame.append(f)
                if w is not None: window.append(w)
            time.sleep(0.5)
        if not frame:
            print(apk.split("/")[-1], mode, "FAILED"); continue
        shown = f"window median {statistics.median(window):5.0f} ms   " if window else ""
        line = (f"{apk.split('/')[-1]:32} {mode}  {shown}first frame median "
                f"{statistics.median(frame):5.0f} ms   min {min(frame)}  max {max(frame)}  ({len(frame)}/{runs} ok)")
        if fps:
            line += f"   footprint median {statistics.median(fps):.0f} MB"
        standbys = [s for s in standbys if s is not None]
        if standbys:
            line += f"   standby {statistics.median(standbys):.0f} MB"
        print(line)
