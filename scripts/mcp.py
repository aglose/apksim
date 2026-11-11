#!/usr/bin/env python3
"""apksim mcp: a Model Context Protocol server (stdio, newline-delimited JSON-RPC 2.0) that launches and drives apksim
instances through their --control channel. Stdlib only. docs/mcp.md is the spec.

  apksim mcp --apps DIR [--apps DIR]... [--out DIR] [--max N] [--window]

Protocol: revision 2026-07-28 (per-request _meta, server/discover, no handshake) and the handshake revisions
2025-11-25 and 2025-06-18 (initialize), told apart by the first request, as the specification's compatibility rules say.
Nothing but JSON-RPC goes to stdout.
"""
import argparse, atexit, stat, tempfile, base64, collections, json, os, re, shutil, signal, socket, subprocess, sys, threading, time, zlib

ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
STATE = os.environ.get("APKSIM_HOME") or (
    os.path.expanduser("~/.apksim") if os.path.exists(os.path.join(ROOT, "work", "release")) else os.path.join(ROOT, "work"))
MODERN, LEGACY = "2026-07-28", ["2025-11-25", "2025-06-18"]
SERVER_INFO = {"name": "apksim", "title": "apksim", "version": "1"}

# Launch options an agent may pass: the same as serve's Catalog.LAUNCH_OPTIONS (nothing that names files, ports or the
# JVM) and its flags. --sdcard-file takes inline contents only.
LAUNCH_OPTIONS = {"--activity", "--build", "--setting", "--feature", "--timezone", "--clock", "--anim-scale", "--serial",
                  "--bluetooth", "--status-bar", "--nav-bar", "--max-fps", "--video", "--audio", "--biometrics", "--camera",
                  "--network-profile", "--device", "--sdcard-file", "--appearance", "--locale", "--font-scale",
                  "--permissions"}
LAUNCH_FLAGS = {"--soft-keyboard"}
# Device controls: setting -> allowed first words/values. Values are checked against SAFE (no spaces, no paths).
DEVICE = {"appearance": r"dark|light|toggle", "locale": r"[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*",
          "fontscale": r"[0-9.]+|\+|-", "rotate": r"left|right|portrait|landscape",
          "network": r"online|offline|3g|lossy", "permission": r"(allow|deny|reset)|(grant|revoke) [A-Za-z0-9_.]+",
          "timezone": r"[A-Za-z_/+-]+", "animscale": r"[0-9.]+|toggle", "bluetooth": r"on|off|toggle",
          "device": r"phone|foldable|tablet|desktop|tv|[0-9]+x[0-9]+(@[0-9]+)?", "lock": r"", "unlock": r"",
          "home": r"", "resume": r"", "recreate": r""}
KEYS = re.compile(r"^[A-Za-z0-9_]{1,24}$")
ANSWER_LINES = 30  # most changed nodes an action's answer lists before pointing at `nodes`


def log(*a):
    print("[apksim-mcp]", *a, file=sys.stderr, flush=True)


class Fail(Exception):
    """A tool error the agent can act on (isError result)."""


# ---------------------------------------------------------------- node tree

ENTRY = re.compile(r"\s*;\s*(?=\[-?\d+,-?\d+,-?\d+,-?\d+\] |\s*$)")
HEAD = re.compile(r"\[(-?\d+),(-?\d+),(-?\d+),(-?\d+)\] ?((?:(?:password-field|field|clickable)(?: |$))*)(.*)", re.S)
ATTR = re.compile(r'(text|hint|desc)="(.*?)"(?= (?:text|hint|desc)="|\s*$)', re.S)


def parse_nodes(raw):
    """`nodes` output -> list of dicts. A clickable box with no text that holds exactly one text node is folded into it
    (a nav item or list row is one entry, tapped at the box's centre)."""
    out = []
    for ent in ENTRY.split(raw.replace("\n", " ")):
        m = HEAD.match(ent)
        if not m:
            continue
        flags = m.group(5).split()
        attrs = dict(ATTR.findall(m.group(6).strip()))
        out.append({"b": tuple(map(int, m.group(1, 2, 3, 4))), "field": "field" in flags or "password-field" in flags,
                    "click": "clickable" in flags, "text": attrs.get("text", ""), "hint": attrs.get("hint", ""),
                    "desc": attrs.get("desc", ""), "pw": "password-field" in flags})
    labelled = [n for n in out if n["text"] or n["desc"] or n["hint"]]
    drop = set()
    for i, n in enumerate(out):
        if (n["click"] or n["field"]) and not (n["text"] or n["desc"] or n["hint"]):
            l, t, r, b = n["b"]
            inside = [c for c in labelled if c["b"][0] >= l and c["b"][1] >= t and c["b"][2] <= r and c["b"][3] <= b]
            if len(inside) == 1 and not inside[0]["click"]:
                inside[0]["click"], inside[0]["tap"] = True, ((l + r) // 2, (t + b) // 2)
                inside[0]["field"] = inside[0]["field"] or n["field"]
                drop.add(i)
    return [n for i, n in enumerate(out) if i not in drop]


def label(n):
    return n["text"] or n["desc"] or n["hint"]


def line(n):
    kind = ("pw-field" if n["pw"] else "field") if n["field"] else ("click" if n["click"] else "")
    s = n["ref"] + (' "' + label(n) + '"' if label(n) else "")
    if n["field"] and n["hint"] and n["text"]:
        s += " hint=" + n["hint"]
    return s + (" " + kind if kind else "")


def center(n):
    return n.get("tap") or ((n["b"][0] + n["b"][2]) // 2, (n["b"][1] + n["b"][3]) // 2)


class Tree:
    """The last nodes an instance answered with, and the references handed out: e1, e2... never reused, so a reference
    stays the same node while it is on screen. A node keeps its reference when its text is unchanged (it may move: a
    scroll), or when it stays in the same place with other text (a counter)."""

    def __init__(self):
        self.nodes, self.seq = [], 0

    def update(self, new):
        old = self.nodes
        for o in old:
            o.pop("_from", None)  # keep only the previous tree, not a chain of every earlier one
        free = list(range(len(old)))
        for n in new:
            n["ref"] = None
        # 1. the same label and kind, nearest in order.
        for n in new:
            if not label(n):
                continue
            for i in free:
                o = old[i]
                if label(o) == label(n) and o["field"] == n["field"]:
                    n["ref"] = o["ref"]
                    n["_from"] = o
                    free.remove(i)
                    break
        # 2. other text in the same place and kind.
        for n in new:
            if n["ref"]:
                continue
            for i in free:
                o = old[i]
                if o["b"][:2] == n["b"][:2] and o["field"] == n["field"] and o["click"] == n["click"] and (
                        label(o) or not label(n)):
                    n["ref"], n["_from"] = o["ref"], o
                    free.remove(i)
                    break
        # 3. unlabelled clickables: the same box.
        for n in new:
            if n["ref"]:
                continue
            for i in free:
                if old[i]["b"] == n["b"] and not label(old[i]) and not label(n):
                    n["ref"], n["_from"] = old[i]["ref"], old[i]
                    free.remove(i)
                    break
        for n in new:
            if not n["ref"]:
                self.seq += 1
                n["ref"] = "e%d" % self.seq
        removed = [old[i] for i in free]
        added = [n for n in new if "_from" not in n]
        changed = [n for n in new if "_from" in n and (label(n["_from"]) != label(n) or n["_from"]["click"] != n["click"])]
        self.nodes = new
        return added, removed, changed


def describe_change(added, removed, changed):
    lines = []
    for n in changed:
        o = n["_from"]
        lines.append("~ %s %s -> %s" % (n["ref"], json.dumps(label(o)), json.dumps(label(n))) if label(o) != label(n)
                     else "~ " + line(n))
    lines += ["- " + line(n) for n in removed if label(n) or n["click"]]
    lines += ["+ " + line(n) for n in added if label(n) or n["click"]]
    if len(lines) > ANSWER_LINES:
        extra = len(lines) - ANSWER_LINES
        lines = lines[:ANSWER_LINES] + ["... %d more changes: call nodes" % extra]
    return lines


# ---------------------------------------------------------------- PNG scaling (stdlib fallback; macOS has sips)

def png_scale(data, maxdim):
    """Box-average a PNG down so its long side is at most maxdim. 8-bit RGB/RGBA, non-interlaced (what `shot` writes)."""
    pos, idat, w = 8, b"", None
    while pos < len(data):
        n = int.from_bytes(data[pos:pos + 4], "big")
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, inter = int.from_bytes(body[:4], "big"), int.from_bytes(body[4:8], "big"), body[8], body[9], 0, 0, body[12]
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    if w is None or depth != 8 or ctype not in (2, 6) or inter:
        return data
    f = -(-max(w, h) // maxdim)
    if f <= 1:
        return data
    bpp = 3 if ctype == 2 else 4
    raw, stride = zlib.decompress(idat), w * bpp
    rows, prev = [], bytearray(stride)
    for y in range(h):
        ft, cur = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        if ft == 1:
            for i in range(bpp, stride): cur[i] = (cur[i] + cur[i - bpp]) & 255
        elif ft == 2:
            for i in range(stride): cur[i] = (cur[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride): cur[i] = (cur[i] + ((cur[i - bpp] if i >= bpp else 0) + prev[i]) // 2) & 255
        elif ft == 4:
            for i in range(stride):
                a, b, c = (cur[i - bpp] if i >= bpp else 0), prev[i], (prev[i - bpp] if i >= bpp else 0)
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                cur[i] = (cur[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(cur)
        prev = cur
    nw, nh, out = w // f, h // f, bytearray()
    for y in range(nh):
        out.append(0)
        block = rows[y * f:(y + 1) * f]
        for x in range(nw):
            for c in range(bpp):
                s = 0
                for r in block:
                    s += sum(r[(x * f) * bpp + c:(x * f + f) * bpp + c:bpp])
                out.append(s // (f * f))
    def chunk(k, d): return len(d).to_bytes(4, "big") + k + d + (zlib.crc32(k + d) & 0xffffffff).to_bytes(4, "big")
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", nw.to_bytes(4, "big") + nh.to_bytes(4, "big") + bytes([8, ctype, 0, 0, 0]))
            + chunk(b"IDAT", zlib.compress(bytes(out), 6)) + chunk(b"IEND", b""))


# ---------------------------------------------------------------- instances

class Instance:
    def __init__(self, iid, proc, apk):
        self.id, self.proc, self.apk = iid, proc, apk
        self.port = None
        self.sock = self.rf = None
        self.stderr = collections.deque(maxlen=4000)
        self.slock = threading.Lock()
        self.tree = Tree()
        self.cv = threading.Condition()
        self.size = (822, 1782)
        self.rec_files = []
        self.started = time.time()
        threading.Thread(target=self._drain, daemon=True).start()

    def _drain(self):
        for ln in self.proc.stderr:
            ln = ln.rstrip("\n")
            m = re.search(r"control port (\d+)", ln)
            if m and self.port is None:
                with self.cv:
                    self.port = int(m.group(1))
                    self.cv.notify_all()
            with self.slock:
                self.stderr.append(ln)
        with self.cv:
            self.cv.notify_all()

    def connect(self, timeout=90):
        end = time.time() + timeout
        with self.cv:
            while self.port is None and self.proc.poll() is None and time.time() < end:
                self.cv.wait(0.5)
        if self.port is None:
            tail = "\n".join(self.tail(8))
            raise Fail("launch failed (exit %s):\n%s" % (self.proc.poll(), tail))
        self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=60)
        self.rf = self.sock.makefile("r")

    def tail(self, n):
        with self.slock:
            return list(self.stderr)[-n:]

    def alive(self):
        return self.proc.poll() is None

    def cmd(self, text):
        if not self.alive():
            raise Fail("instance %s has exited (%s): %s" % (self.id, self.proc.poll(), "; ".join(self.tail(3))))
        if "\n" in text or "\r" in text:
            raise Fail("line breaks are not allowed")
        try:
            self.sock.sendall((text + "\n").encode())
            r = self.rf.readline()
        except OSError as e:
            raise Fail("control channel: %s" % e)
        if not r:
            raise Fail("instance %s closed its control channel" % self.id)
        return r.rstrip("\n")

    def close(self):
        try:
            if self.alive():
                self.cmd("quit")
        except Exception:
            pass
        try:
            self.proc.wait(8)
        except Exception:
            try:
                os.killpg(self.proc.pid, signal.SIGTERM)
            except Exception:
                pass


class Dir:
    """A folder held open by descriptor. The app may write the folders it shares with the server and swap a name for a
    symlink at any time, so every access is relative to the descriptor opened at start with O_NOFOLLOW, never to a path
    that is resolved again: a file is opened with O_NOFOLLOW and must be a regular file, and a write goes to a new
    name that is renamed over the target, which replaces a link instead of writing through it."""

    def __init__(self, path):
        os.makedirs(path, exist_ok=True)
        self.path = path
        self.fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)

    def read(self, name):
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=self.fd)
        with os.fdopen(fd, "rb") as f:
            if not stat.S_ISREG(os.fstat(f.fileno()).st_mode):
                raise Fail("%s is not a regular file" % name)
            return f.read()

    def write(self, name, data):
        tmp = "%s.tmp-%d" % (name, os.getpid())
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=self.fd)
        try:
            with os.fdopen(fd, "wb") as w:
                w.write(data)
            os.replace(tmp, name, src_dir_fd=self.fd, dst_dir_fd=self.fd)
        except BaseException:
            self.unlink(tmp)
            raise

    def unlink(self, name):
        try:
            os.unlink(name, dir_fd=self.fd)
        except OSError:
            pass

    def size(self, name):
        try:
            st = os.stat(name, dir_fd=self.fd, follow_symlinks=False)
        except OSError:
            return 0
        return st.st_size if stat.S_ISREG(st.st_mode) else 0


class Server:
    def __init__(self, args):
        self.apps = [os.path.realpath(os.path.expanduser(a)) for a in args.apps]
        self.out = os.path.realpath(os.path.expanduser(args.out or os.path.join(STATE, "mcp-out")))
        self.outdir = Dir(self.out)
        # The app is sandboxed and may write under the state folder only: an output folder elsewhere is filled by copies
        # from a scratch folder there.
        inside = (self.out == STATE or self.out.startswith(STATE + os.sep)) and not re.search(r"\s", self.out)
        self.scratch = self.out if inside else os.path.join(STATE, "mcp-tmp", str(os.getpid()))
        self.scratchdir = self.outdir if self.scratch == self.out else Dir(self.scratch)
        self.shotdir = None  # where the flight recorder saves by default, opened when first needed
        self.max, self.window = args.max, args.window
        self.apksim = args.apksim or os.path.join(ROOT, "apksim")
        self.inst = {}
        self.n = 0
        self.tools = TOOLS

    # --- helpers
    def get(self, a):
        iid = a.get("instance")
        live = {k: v for k, v in self.inst.items() if v.alive()}
        if iid is None:
            if len(live) == 1:
                return next(iter(live.values()))
            raise Fail("no instance running: call launch" if not live else "several instances: pass instance (%s)" % ", ".join(live))
        if iid not in self.inst:
            raise Fail("unknown instance " + str(iid))
        return self.inst[iid]

    def nodes(self, i, mode="update"):
        raw = i.cmd("nodes")
        if raw.startswith("error"):
            raise Fail(raw)
        return parse_nodes(raw)

    def settle(self, i):
        r = i.cmd("settle 2500 150")
        return "" if r.startswith("settled") else " (not settled: animating)"

    def activity(self, i):
        a = i.cmd("activity").rsplit(".", 1)[-1]
        return a

    def answer(self, i, head, before_act):
        """Settle, read the tree and answer with what changed since the last answer."""
        note = self.settle(i)
        act = self.activity(i)
        added, removed, changed = i.tree.update(self.nodes(i))
        lines = describe_change(added, removed, changed)
        if not lines:
            lines = ["no change"]
        h = "%s%s | %s" % (head, note, act)
        if act != before_act:
            h += " (was %s)" % before_act
        return h + "\n" + "\n".join(lines)

    def prepare(self, i):
        if not i.tree.nodes:
            self.settle(i)
            i.tree.update(self.nodes(i))
        return self.activity(i)

    # --- tools
    def t_launch(self, a):
        apk = os.path.realpath(os.path.expanduser(a.get("apk", "")))
        if not any(apk == d or apk.startswith(d + os.sep) for d in self.apps) or not os.path.isfile(apk):
            raise Fail("apk must be a file under the allowed folders: " + ", ".join(self.apps))
        if sum(1 for v in self.inst.values() if v.alive()) >= self.max:
            raise Fail("at most %d instances: quit one" % self.max)
        opts = a.get("options", [])
        if not isinstance(opts, list) or not all(isinstance(o, str) for o in opts):
            raise Fail("options is a list of strings")
        cmd = [self.apksim, "--control", "auto", "--no-autofill", "--data-slots", "mcp"]
        if not (a.get("window") and self.window):
            cmd.append("--headless")
        if a.get("fresh", True):
            cmd.append("--fresh")
        if a.get("record"):
            cmd += ["--rec", "input,logcat,screen"]
        k = 0
        while k < len(opts):
            o = opts[k]
            if o in LAUNCH_FLAGS:
                cmd.append(o)
            elif o in LAUNCH_OPTIONS:
                if k + 1 >= len(opts):
                    raise Fail("%s needs a value" % o)
                v = opts[k + 1]
                if o == "--camera" and v not in ("off", "fake"):
                    raise Fail("--camera: off or fake")
                if any(ord(c) < 32 for c in v) or (o == "--sdcard-file" and not re.match(r"^/sdcard/[^=]*=(text:|[A-Za-z0-9+/=]+$)", v)):
                    raise Fail("bad value for " + o)
                cmd += [o, v]
                k += 1
            else:
                raise Fail("launch option %s is not allowed (allowed: %s %s)" % (
                    o, " ".join(sorted(LAUNCH_OPTIONS)), " ".join(sorted(LAUNCH_FLAGS))))
            k += 1
        cmd.append(apk)
        env = dict(os.environ, APKSIM_LOGCAT="1")
        self.n += 1
        p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
                             env=env, start_new_session=True)
        i = Instance("a%d" % self.n, p, os.path.basename(apk))
        self.inst[i.id] = i
        try:
            i.connect()
            r = i.cmd("settle 20000 300")
            m = re.search(r"mBounds=Rect\(0, 0 - (\d+), (\d+)\)", i.cmd("config"))
            if m:
                i.size = (int(m.group(1)), int(m.group(2)))
        except Fail:
            i.close()
            raise
        added, _, _ = i.tree.update(self.nodes(i))
        shown = [line(n) for n in i.tree.nodes if label(n) or n["click"]]
        return "launched %s (%s, %.1fs)%s | %s\n%s" % (i.id, i.apk, time.time() - i.started,
                                                     "" if r.startswith("settled") else " (not settled)", self.activity(i),
                                                     "\n".join(shown[:ANSWER_LINES]))

    def t_instances(self, a):
        rows = ["%s %s %s %s" % (v.id, v.apk, "up" if v.alive() else "exited %s" % v.proc.poll(),
                                 self.activity(v) if v.alive() else "") for v in self.inst.values()]
        return "\n".join(rows) or "none"

    def t_quit(self, a):
        i = self.get(a)
        i.close()
        return "quit " + i.id

    def resolve(self, i, ref):
        """The node a reference named, found again in the screen as it is now (it may have moved or gone)."""
        old = next((n for n in i.tree.nodes if n["ref"] == ref), None)
        if not old:
            raise Fail("%s is not on screen: call nodes" % ref)
        fresh = self.nodes(i)
        same = [n for n in fresh if (label(n) == label(old) and n["field"] == old["field"]) and (
            label(old) or n["b"] == old["b"])]
        if not same:
            raise Fail("%s is no longer on screen: call nodes" % ref)
        return min(same, key=lambda n: abs(n["b"][0] - old["b"][0]) + abs(n["b"][1] - old["b"][1]))

    def tap_point(self, i, a):
        before = self.prepare(i)
        if a.get("ref"):
            x, y = center(self.resolve(i, a["ref"]))
        elif a.get("text"):
            want = a["text"].lower()
            cands = [n for n in self.nodes(i) if label(n)]
            ex = [n for n in cands if label(n).lower() == want] or [n for n in cands if want in label(n).lower()]
            if not ex:
                raise Fail("no node with text %r: call nodes" % a["text"])
            if len(ex) > 1:
                known = {tuple(n["b"]): n["ref"] for n in i.tree.nodes}
                raise Fail("%d nodes match: use ref (%s)" % (len(ex), ", ".join(
                    "%s %s" % (known.get(tuple(n["b"]), "?"), json.dumps(label(n))) for n in ex[:6])))
            x, y = center(ex[0])
        elif "x" in a and "y" in a:
            x, y = int(a["x"]), int(a["y"])
        else:
            raise Fail("give ref, text, or x and y")
        return before, x, y

    def t_tap(self, a):
        i = self.get(a)
        before, x, y = self.tap_point(i, a)
        r = i.cmd("tap %d %d" % (x, y))
        return self.answer(i, "tap %d,%d %s" % (x, y, r if r != "ok" else "ok"), before)

    def t_type(self, a):
        i = self.get(a)
        t = a.get("text")
        if not isinstance(t, str) or not t or any(ord(c) < 32 for c in t):
            raise Fail("text: a non-empty string without control characters")
        before = self.prepare(i)
        if a.get("ref") or a.get("tap_text"):
            _, x, y = self.tap_point(i, {"ref": a.get("ref"), "text": a.get("tap_text"), "instance": i.id})
            i.cmd("tap %d %d" % (x, y))
            self.settle(i)
        r = "ok"
        for part in re.split(r"( +)", t):  # the control channel collapses spaces: send them as key presses
            if part.startswith(" "):
                r = i.cmd("key SPACE %d" % len(part))
            elif part:
                r = i.cmd("type " + part)  # never logged here: only the length is echoed
        return self.answer(i, "typed %d chars %s" % (len(t), r), before)

    def t_key(self, a):
        i = self.get(a)
        k = a.get("key", "")
        if not KEYS.match(k):
            raise Fail("key: a name such as ENTER, DEL, TAB, A")
        mods = [m for m in a.get("modifiers", []) if m in ("ctrl", "shift", "alt", "meta")]
        before = self.prepare(i)
        r = i.cmd("key %s %d %s" % (k, max(1, min(int(a.get("count", 1)), 50)), " ".join(mods)))
        return self.answer(i, "key " + k + ("" if r == "ok" else " " + r), before)

    def t_swipe(self, a):
        i = self.get(a)
        before = self.prepare(i)
        m = re.search(r"mBounds=Rect\(0, 0 - (\d+), (\d+)\)", i.cmd("config"))
        if m:
            i.size = (int(m.group(1)), int(m.group(2)))
        w, h = i.size
        d = a.get("direction")
        if d:
            # the finger moves the way named: "up" scrolls a list to its later items
            cx, cy = (w // 2, h // 2) if not (a.get("ref") or a.get("text")) else center(self._node(i, a))
            vx, vy = {"up": (0, -h // 3), "down": (0, h // 3), "left": (-w // 2, 0), "right": (w // 2, 0)}.get(d, (None, None))
            if vx is None:
                raise Fail("direction: up, down, left or right")
            x1, y1, x2, y2 = [max(8, min(v, lim - 8)) for v, lim in zip(
                (cx - vx // 2, cy - vy // 2, cx + vx // 2, cy + vy // 2), (w, h, w, h))]
        else:
            try:
                x1, y1, x2, y2 = (int(a[k]) for k in ("x1", "y1", "x2", "y2"))
            except (KeyError, ValueError):
                raise Fail("give direction, or x1 y1 x2 y2")
        r = i.cmd("swipe %d %d %d %d %d" % (x1, y1, x2, y2, max(50, min(int(a.get("ms", 300)), 3000))))
        return self.answer(i, "swipe %d,%d>%d,%d %s" % (x1, y1, x2, y2, r), before)

    def _node(self, i, a):
        r = self.resolve(i, a["ref"]) if a.get("ref") else None
        if not r and a.get("text"):
            r = next((n for n in i.tree.nodes if a["text"].lower() in label(n).lower()), None)
        if not r:
            raise Fail("node not on screen: call nodes")
        return r

    def t_back(self, a):
        i = self.get(a)
        before = self.prepare(i)
        return self.answer(i, "back " + i.cmd("back"), before)

    def t_open_url(self, a):
        i = self.get(a)
        u = a.get("url", "")
        if not re.match(r"^[A-Za-z][A-Za-z0-9+.-]*://\S{0,500}$", u):
            raise Fail("url: scheme://... without spaces")
        before = self.prepare(i)
        return self.answer(i, "open " + i.cmd("open " + u), before)

    def t_device(self, a):
        i = self.get(a)
        s, v = a.get("setting", ""), str(a.get("value", ""))
        if s not in DEVICE or not re.fullmatch(DEVICE[s], v):
            raise Fail("setting/value: " + "; ".join("%s %s" % (k, p) for k, p in DEVICE.items()))
        before = self.prepare(i)
        return self.answer(i, ("%s %s: " % (s, v)).strip() + i.cmd((s + " " + v).strip()), before)

    def t_nodes(self, a):
        i = self.get(a)
        self.settle(i)
        added, removed, changed = i.tree.update(self.nodes(i))
        q = (a.get("text") or "").lower()
        rows = [n for n in i.tree.nodes if (label(n) or n["click"]) and (not q or q in label(n).lower())]
        bounds = a.get("bounds")
        out = [line(n) + (" @%d,%d,%d,%d" % n["b"] if bounds else "") for n in rows]
        return "%s | %d nodes\n%s" % (self.activity(i), len(rows), "\n".join(out))

    def t_screenshot(self, a):
        i = self.get(a)
        self.settle(i)
        if re.search(r"\s", self.scratch):
            raise Fail("screenshot needs a state folder path without spaces")
        name = "shot-%s-%d.png" % (i.id, int(time.time() * 1000))
        raw = os.path.join(self.scratch, name)
        r = i.cmd("shot " + raw)
        sd = self.scratchdir
        if not r.startswith("ok") or not sd.size(name):
            raise Fail("shot: " + r)
        png = sd.read(name)  # the bytes of a regular file, never a link the app swapped in
        data, mime = png, "image/png"
        maxdim = int(a.get("max_size", 640))
        if maxdim > 0:
            sips = shutil.which("sips")
            if sips:
                # sips opens its files by path: give it a private folder (0700, random name) the app has no way to
                # name, not the shared scratch folder.
                priv = tempfile.mkdtemp(prefix="apksim-mcp-")
                try:
                    tin, tout = os.path.join(priv, "in.png"), os.path.join(priv, "out.jpg")
                    with open(tin, "wb") as f:
                        f.write(png)
                    if subprocess.run([sips, "-s", "format", "jpeg", "-s", "formatOptions", "60", "-Z", str(maxdim), tin,
                                       "--out", tout], capture_output=True).returncode == 0 and os.path.isfile(tout):
                        with open(tout, "rb") as f:
                            data, mime = f.read(), "image/jpeg"
                finally:
                    shutil.rmtree(priv, ignore_errors=True)
            else:
                data = png_scale(png, maxdim)
        if a.get("save"):
            if self.scratchdir is not self.outdir:
                self.outdir.write(name, png)
            else:
                pass  # the app wrote it in the output folder itself
            saved = "saved " + os.path.join(self.out, name)
        else:
            sd.unlink(name)
            saved = ""
        return [{"type": "image", "data": base64.b64encode(data).decode(), "mimeType": mime},
                {"type": "text", "text": saved or "%d bytes" % len(data)}]

    def t_logcat(self, a):
        i = self.get(a)
        pat = re.compile(re.escape(a["filter"]), re.I) if a.get("filter") else None
        with i.slock:
            snap = list(i.stderr)
        lines = [l for l in snap if not l.startswith("[apksim]") and (not pat or pat.search(l))]
        n = max(1, min(int(a.get("lines", 30)), 300))
        return "\n".join(lines[-n:])[-12000:] or "(no app log lines)"

    def t_activity(self, a):
        i = self.get(a)
        return i.cmd("activity")

    def t_record(self, a):
        i = self.get(a)
        act = a.get("action", "status")
        if act == "start":
            return i.cmd("rec on input logcat screen") + "; checkpoint " + i.cmd("rec checkpoint now")
        if act == "stop":
            return i.cmd("rec off")
        if act == "status":
            return i.cmd("rec")
        if act == "save":
            name = a.get("name")
            if name is not None and not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", name):
                raise Fail("name: letters, digits, . _ -")
            r = i.cmd("rec save")
            m = re.search(r"saving (.+?\.apkrec)(?: \(|$)", r)
            if not m:
                raise Fail(r)
            src = m.group(1)
            if self.shotdir is None or os.path.dirname(src) != self.shotdir.path:
                try:
                    self.shotdir = Dir(os.path.dirname(src))
                except OSError as e:
                    raise Fail("recording folder: %s" % e)
            sname = os.path.basename(src)
            for _ in range(100):
                st = i.cmd("rec")
                if "last save: " + src + ":" in st and " KB in " in st and self.shotdir.size(sname) > 0:
                    break
                time.sleep(0.1)
            else:
                raise Fail("save did not finish: " + st)
            name = name or sname
            if not name.endswith(".apkrec"):
                name += ".apkrec"
            dst = os.path.join(self.out, name)
            if os.path.realpath(os.path.dirname(src)) == os.path.realpath(self.out) and name == sname:
                return "saved %s (%d KB)" % (dst, self.outdir.size(name) // 1024)  # it is already there
            self.outdir.write(name, self.shotdir.read(sname))
            self.shotdir.unlink(sname)
            return "saved %s (%d KB)" % (dst, self.outdir.size(name) // 1024)
        raise Fail("action: start, stop, save, status")

    def shutdown(self):
        for v in self.inst.values():
            v.close()
        if self.scratch != self.out:
            shutil.rmtree(self.scratch, ignore_errors=True)


def S(props=None, req=()):
    p = {"instance": {"type": "string", "description": "id, if several run"}}
    p.update(props or {})
    return {"type": "object", "properties": p, "required": list(req), "additionalProperties": False}


STR, INT = {"type": "string"}, {"type": "integer"}
TOOLS = [
    ("launch", "Start an APK (headless). Answers with the screen's nodes.", {
        "type": "object", "required": ["apk"], "additionalProperties": False, "properties": {
            "apk": STR, "options": {"type": "array", "items": STR, "description": "launch options, e.g. [\"--device\",\"tablet\"]"},
            "window": {"type": "boolean"}, "fresh": {"type": "boolean", "description": "empty app data first (default true)"},
            "record": {"type": "boolean", "description": "flight recorder on from the start"}}}),
    ("instances", "List instances.", S()),
    ("quit", "Stop an instance.", S()),
    ("tap", "Tap a node by ref (e3), by text, or x,y. Answers with what changed on screen.", S(
        {"ref": STR, "text": STR, "x": INT, "y": INT})),
    ("type", "Type text (optionally tap ref/tap_text first to focus a field).", S({"text": STR, "ref": STR, "tap_text": STR}, ["text"])),
    ("key", "Press a key (ENTER, DEL, TAB, ...).", S({"key": STR, "count": INT, "modifiers": {"type": "array", "items": STR}}, ["key"])),
    ("swipe", "Swipe by direction (up scrolls to later content; from the centre or a ref/text) or x1,y1,x2,y2.", S(
        {"direction": {"type": "string", "enum": ["up", "down", "left", "right"]}, "ref": STR, "text": STR,
         "x1": INT, "y1": INT, "x2": INT, "y2": INT, "ms": INT})),
    ("back", "Press Back.", S()),
    ("open_url", "Open a deep link in the app.", S({"url": STR}, ["url"])),
    ("device", "Change the device: appearance dark|light, locale fr-FR, fontscale 1.3, rotate left|right|portrait|landscape, "
              "network online|offline|3g|lossy, permission allow|deny|grant NAME|revoke NAME|reset, timezone, animscale, "
              "bluetooth, device phone|tablet|..., lock, unlock, home, resume, recreate.", S(
        {"setting": {"type": "string", "enum": sorted(DEVICE)}, "value": STR}, ["setting"])),
    ("nodes", "The screen as compact lines: ref \"label\" click|field. text filters by substring.", S(
        {"text": STR, "bounds": {"type": "boolean"}})),
    ("screenshot", "An image of the screen (downscaled to max_size px, 0 for full size).", S(
        {"max_size": INT, "save": {"type": "boolean", "description": "also keep the PNG in the output folder"}})),
    ("logcat", "Tail of the app's log.", S({"lines": INT, "filter": STR})),
    ("activity", "The top activity.", S()),
    ("record", "Flight recorder: start, stop, status, or save a .apkrec bundle to the output folder (for replay).", S(
        {"action": {"type": "string", "enum": ["start", "stop", "save", "status"]}, "name": STR}, ["action"])),
]
# Tools that only read: hints for clients that ask before acting.
READ_ONLY = {"instances", "nodes", "screenshot", "logcat", "activity"}


def tool_list():
    return [{"name": n, "description": d, "inputSchema": s,
             "annotations": {"readOnlyHint": n in READ_ONLY, "openWorldHint": False}} for n, d, s in TOOLS]


# ---------------------------------------------------------------- JSON-RPC

def main():
    ap = argparse.ArgumentParser(prog="apksim mcp")
    ap.add_argument("--apps", action="append", default=[], help="folder APKs may be launched from (repeatable)")
    ap.add_argument("--out", help="output folder for recordings and saved screenshots (default STATE/mcp-out)")
    ap.add_argument("--max", type=int, default=2, help="instances at once")
    ap.add_argument("--window", action="store_true", help="let launch open a Mac window on request")
    ap.add_argument("--apksim", help=argparse.SUPPRESS)
    args = ap.parse_args()
    if not args.apps:
        sys.exit("apksim mcp: give at least one --apps FOLDER (APKs are launched only from there)")
    srv = Server(args)
    atexit.register(srv.shutdown)
    for s in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(s, lambda *_: sys.exit(0))

    def send(o):
        sys.stdout.write(json.dumps(o, separators=(",", ":"), ensure_ascii=False) + "\n")
        sys.stdout.flush()

    def err(rid, code, msg, data=None):
        e = {"code": code, "message": msg}
        if data is not None:
            e["data"] = data
        send({"jsonrpc": "2.0", "id": rid, "error": e})

    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            m = json.loads(raw)
        except ValueError:
            err(None, -32700, "parse error")
            continue
        if not isinstance(m, dict):
            err(None, -32600, "invalid request")
            continue
        rid, method, p = m.get("id"), m.get("method"), m.get("params") or {}
        if "method" not in m or rid is None:
            continue  # a notification (initialized, cancelled) or a response: nothing to answer
        if not isinstance(p, dict) or not isinstance(p.get("_meta") or {}, dict) or not isinstance(
                p.get("arguments") or {}, dict):
            err(rid, -32602, "params, _meta and arguments must be objects")
            continue
        meta = p.get("_meta") or {}
        ver = meta.get("io.modelcontextprotocol/protocolVersion")
        if ver and ver != MODERN and method != "initialize":
            err(rid, -32022, "Unsupported protocol version", {"supported": [MODERN] + LEGACY, "requested": ver})
            continue
        try:
            if method == "initialize":
                want = p.get("protocolVersion")
                send({"jsonrpc": "2.0", "id": rid, "result": {
                    "protocolVersion": want if want in LEGACY else (LEGACY[0] if want != MODERN else MODERN),
                    "capabilities": {"tools": {"listChanged": False}}, "serverInfo": SERVER_INFO,
                    "instructions": INSTRUCTIONS}})
            elif method == "server/discover":
                send({"jsonrpc": "2.0", "id": rid, "result": {
                    "resultType": "complete", "supportedVersions": [MODERN] + LEGACY, "capabilities": {"tools": {}},
                    "instructions": INSTRUCTIONS, "ttlMs": 3600000, "cacheScope": "public",
                    "_meta": {"io.modelcontextprotocol/serverInfo": SERVER_INFO}}})
            elif method == "ping":
                send({"jsonrpc": "2.0", "id": rid, "result": {}})
            elif method == "tools/list":
                send({"jsonrpc": "2.0", "id": rid, "result": {"resultType": "complete", "tools": tool_list(),
                                                              "ttlMs": 3600000, "cacheScope": "public"}})
            elif method == "tools/call":
                name, a = p.get("name"), p.get("arguments") or {}
                fn = getattr(srv, "t_" + str(name), None) if name in {t[0] for t in TOOLS} else None
                if not fn:
                    err(rid, -32602, "unknown tool " + str(name))
                    continue
                try:
                    r = fn(a)
                    content = r if isinstance(r, list) else [{"type": "text", "text": r}]
                    send({"jsonrpc": "2.0", "id": rid, "result": {"resultType": "complete", "content": content}})
                except Fail as e:
                    send({"jsonrpc": "2.0", "id": rid, "result": {"resultType": "complete", "isError": True,
                                                                  "content": [{"type": "text", "text": str(e)}]}})
                except Exception as e:  # a bug here must not end the server
                    log("tool %s failed: %r" % (name, e))
                    send({"jsonrpc": "2.0", "id": rid, "result": {"resultType": "complete", "isError": True,
                                                                  "content": [{"type": "text", "text": "internal error: %r" % e}]}})
            else:
                err(rid, -32601, "method not found: %s" % method)
        except Exception as e:
            log("request failed: %r" % e)
            err(rid, -32603, "internal error")


INSTRUCTIONS = ("Drives Android apps on this Mac. launch an APK, then tap/type/key/swipe: each action answers with the "
                "nodes that changed (+ added, - removed, ~ changed; e3 refs stay the same while a node is on screen), so "
                "call nodes or screenshot only when you need the whole screen. record save keeps a .apkrec for replay. "
                "Sign-ins are the owner's: never ask for or type passwords.")

if __name__ == "__main__":
    main()
