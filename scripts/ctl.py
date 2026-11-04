#!/usr/bin/env python3
"""Drives running apksim instances without knowing their ports, like `xcrun simctl`.

  ctl.py list                      running instances with a control port: PID, port, APK, device
  ctl.py CMD [ARG...]              one command to the only running instance (e.g. ctl.py appearance dark)
  ctl.py @SEL CMD [ARG...]         to the instance whose PID, port or APK name contains SEL
  ctl.py @SEL CMD ARG ';' CMD ...  several commands, separated by ';' ('wait MS' sleeps between them)

App data, like simctl's listapps / get_app_container / erase (no instance needed):
  ctl.py apps                      packages with data, and their data folders (default, instance-N)
  ctl.py container PKG             a package's data folder(s)
  ctl.py erase PKG                 deletes a package's data, as uninstalling would (refused while in use)

Instances need --control PORT or --control auto. Commands are those of the control channel (see AGENTS.md).
"""
import fcntl, glob, os, shutil, socket, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def instances():
    found = []
    for f in glob.glob(os.path.join(ROOT, "work", "tmp", "*", "control.port")):
        pid = int(os.path.basename(os.path.dirname(f)))
        try:
            os.kill(pid, 0)
        except OSError:
            continue
        port = int(open(f).read().strip())
        info = os.path.join(os.path.dirname(f), "launch.info")
        if os.path.exists(info):
            # Written by the instance: one from a standby has no APK on its command line.
            kv = dict(l.split("=", 1) for l in open(info).read().splitlines() if "=" in l)
            apk, device = os.path.basename(kv.get("apk", "?")), kv.get("device", "phone")
        else:
            cmd = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout.split()
            apk = os.path.basename(cmd[-1]) if cmd else "?"
            device = next((a.split("=", 1)[1] for a in cmd if a.startswith("-Dapksim.device=")), "phone")
        found.append((pid, port, apk, device))
    return sorted(found)


DATA = os.path.join(ROOT, "work", "cache", "data")


def in_use(slot):
    """Each running instance holds a lock on its data folder (Storage.dataDir)."""
    try:
        with open(os.path.join(slot, ".lock"), "a") as f:
            fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.lockf(f, fcntl.LOCK_UN)
        return False
    except OSError:
        return True


def data_command(argv):
    if argv[0] == "apps":
        for pkg in sorted(os.listdir(DATA)) if os.path.isdir(DATA) else []:
            slots = sorted(os.listdir(os.path.join(DATA, pkg)))
            print(f"{pkg:48} {', '.join(slots)}")
        return 0
    if len(argv) < 2:
        print(f"usage: ctl.py {argv[0]} PKG", file=sys.stderr)
        return 2
    pkg_dir = os.path.join(DATA, argv[1])
    if not os.path.isdir(pkg_dir):
        print(f"no data for {argv[1]}", file=sys.stderr)
        return 1
    slots = [os.path.join(pkg_dir, s) for s in sorted(os.listdir(pkg_dir))]
    if argv[0] == "container":
        print("\n".join(slots))
        return 0
    busy = [s for s in slots if in_use(s)]
    if busy:
        print(f"in use by a running instance: {', '.join(busy)}; quit it first", file=sys.stderr)
        return 1
    shutil.rmtree(pkg_dir)
    print(f"erased {pkg_dir}")
    return 0


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0
    if argv[0] in ("apps", "container", "erase"):
        return data_command(argv)
    live = instances()
    if argv[0] == "list":
        for pid, port, apk, device in live:
            print(f"{pid:>7}  port {port:<6} {device:<9} {apk}")
        return 0
    sel = None
    if argv[0].startswith("@"):
        sel, argv = argv[0][1:], argv[1:]
    match = [i for i in live if sel is None or sel in (str(i[0]), str(i[1])) or sel in i[2]]
    if len(match) != 1:
        print(f"{len(match)} instances match; use ctl.py list and @PID", file=sys.stderr)
        return 2
    port = match[0][1]
    cmds = [c.strip() for c in " ".join(argv).split(";") if c.strip()]
    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    s.settimeout(60)
    f = s.makefile("rw")
    for cmd in cmds:
        if cmd.startswith("wait "):
            time.sleep(int(cmd.split()[1]) / 1000)
            continue
        f.write(cmd + "\n")
        f.flush()
        reply = f.readline().strip()
        print(reply if len(cmds) == 1 else f"{cmd} -> {reply}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
