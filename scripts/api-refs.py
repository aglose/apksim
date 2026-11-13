#!/usr/bin/env python3
"""Which framework classes, methods and fields does apksim.jar use that another API level's framework lacks?

    scripts/api-refs.py FRAMEWORK.jar [APKSIM.jar] [--json FILE] [--all]

FRAMEWORK.jar is a Robolectric android-all(-instrumented) jar of the level asked about; APKSIM.jar defaults to
work/apksim.jar (built against the level apksim ships). Reads class files only: constant pools for what each apksim
class links against, member tables for what the framework has (inherited members followed through the jar).

Reported per apksim source file, most references first:
  class    a framework class that is not there
  method   a method that is not there under that name and descriptor (the class is)
  field    likewise a field
  sig      a shadow (@Implements) whose own method or field signatures name a missing class: Robolectric reads a
           shadow's methods with getDeclaredMethods, which then throws for the whole shadow
  shadows  an @Implements(value = X.class) whose X is missing: the shadow cannot even be registered
  unbound  a shadow's @Implementation that no method of the shadowed class matches at that level (by name and
           parameter types): Robolectric never calls it, silently
  gated    an @Implementation whose minSdk/maxSdk leaves that level out: what it adds is not there
  string   a string constant that names a missing framework class (reflection, @Implements(className = ...))

Not seen: members reached by reflection through names built at run time, behaviour differences behind a signature
that exists at both levels, and what Robolectric's own shadows do at that level. docs/api-levels.md has a run's result.
"""
import collections
import json
import re
import struct
import sys
import zipfile

FRAMEWORK = re.compile(r"^(android|com/android|libcore|dalvik|com/google/android|org/apache/harmony|org/ccil|"
                       r"com/ibm/icu/|android/icu)(/|$)")


JDK_INTERFACES = {
    "java/lang/Runnable": {"run"}, "java/lang/Comparable": {"compareTo"}, "java/lang/AutoCloseable": {"close"},
    "java/io/Closeable": {"close"}, "java/lang/Iterable": {"iterator", "forEach", "spliterator"},
    "java/io/Serializable": set(), "java/lang/Cloneable": set(), "java/lang/CharSequence": {
        "length", "charAt", "subSequence", "chars", "codePoints", "isEmpty"},
}


class Cls:
    __slots__ = ("name", "super", "ifaces", "fields", "methods", "classes", "mrefs", "frefs", "strings", "implements",
                 "source", "sigs", "impls")


def parse(data, members=True, refs=False):
    """One class file: its name, supers, declared members, and (refs) what its constant pool links against."""
    magic, _, _, n = struct.unpack_from(">IHHH", data, 0)
    if magic != 0xCAFEBABE:
        return None
    pool = [None] * n
    off = 10
    i = 1
    while i < n:
        tag = data[off]
        if tag == 1:
            ln = struct.unpack_from(">H", data, off + 1)[0]
            pool[i] = (1, data[off + 3:off + 3 + ln])
            off += 3 + ln
        elif tag in (3, 4):
            pool[i] = (tag, struct.unpack_from(">i", data, off + 1)[0])
            off += 5
        elif tag in (5, 6):
            off += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack_from(">H", data, off + 1)[0])
            off += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag,) + struct.unpack_from(">HH", data, off + 1)
            off += 5
        elif tag == 15:
            off += 4
        else:
            raise ValueError("constant pool tag %d" % tag)
        i += 1

    def utf(ix):
        return pool[ix][1].decode("utf-8", "replace")

    def cname(ix):
        return utf(pool[ix][1])

    c = Cls()
    _, this, sup, ni = struct.unpack_from(">HHHH", data, off)
    off += 8
    c.name = cname(this)
    c.super = cname(sup) if sup else None
    c.ifaces = [cname(x) for x in struct.unpack_from(">%dH" % ni, data, off)]
    off += 2 * ni
    c.fields, c.methods, c.sigs, c.impls = set(), set(), [], []
    c.implements, c.source = None, None

    def attributes(off, want=None):
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(count):
            name, ln = struct.unpack_from(">HI", data, off)
            if want is not None:
                want(utf(name), off + 6, ln)
            off += 6 + ln
        return off

    def class_attr(name, at, ln):
        if name == "SourceFile":
            c.source = utf(struct.unpack_from(">H", data, at)[0])
        elif name == "RuntimeVisibleAnnotations":
            count = struct.unpack_from(">H", data, at)[0]
            p = at + 2
            for _ in range(count):
                p, typ, values = annotation(p)
                if typ == "Lorg/robolectric/annotation/Implements;":
                    c.implements = values.get("value") or values.get("className") or ""

    def annotation(p):
        typ, pairs = struct.unpack_from(">HH", data, p)
        p += 4
        values = {}
        for _ in range(pairs):
            key = utf(struct.unpack_from(">H", data, p)[0])
            p, value = element(p + 2)
            if value is not None:
                values[key] = value
        return p, utf(typ), values

    def element(p):
        tag = chr(data[p])
        p += 1
        if tag in "BCDFIJSZs":
            ix = struct.unpack_from(">H", data, p)[0]
            if tag == "s":
                return p + 2, utf(ix).replace(".", "/")
            return p + 2, (pool[ix][1] if tag == "I" else None)
        if tag == "c":
            d = utf(struct.unpack_from(">H", data, p)[0])
            return p + 2, (d[1:-1] if d.startswith("L") else None)
        if tag == "e":
            return p + 4, None
        if tag == "@":
            return annotation(p)[0], None
        if tag == "[":
            count = struct.unpack_from(">H", data, p)[0]
            p += 2
            for _ in range(count):
                p, _v = element(p)
            return p, None
        raise ValueError("annotation tag " + tag)

    for table in (c.fields, c.methods):
        count = struct.unpack_from(">H", data, off)[0]
        off += 2
        for _ in range(count):
            _, name, desc = struct.unpack_from(">HHH", data, off)
            if members:
                table.add((utf(name), utf(desc)))
                c.sigs.append((utf(name), utf(desc)))
            if refs and table is c.methods:
                found = []

                def method_attr(aname, at, ln, found=found):
                    if aname == "RuntimeVisibleAnnotations":
                        p = at + 2
                        for _ in range(struct.unpack_from(">H", data, at)[0]):
                            p, typ, values = annotation(p)
                            if typ == "Lorg/robolectric/annotation/Implementation;":
                                found.append(values)

                off = attributes(off + 6, method_attr)
                if found:
                    c.impls.append((utf(name), utf(desc), found[0]))
            else:
                off = attributes(off + 6)

    attributes(off, class_attr)
    if refs:
        c.classes, c.mrefs, c.frefs, c.strings = set(), set(), set(), set()
        for e in pool:
            if e is None:
                continue
            if e[0] == 7:
                c.classes.add(utf(e[1]))
            elif e[0] == 8:
                c.strings.add(utf(e[1]))
            elif e[0] in (9, 10, 11):
                owner = cname(e[1])
                nt = pool[e[2]]
                (c.frefs if e[0] == 9 else c.mrefs).add((owner, utf(nt[1]), utf(nt[2])))
    return c


def element_class(name):
    """[[Landroid/x/Y; -> android/x/Y; a primitive array -> None."""
    if name.startswith("["):
        name = name.lstrip("[")
        return name[1:-1] if name.startswith("L") else None
    return name


def desc_classes(desc):
    return re.findall(r"L([^;]+);", desc)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__)
    out_json = sys.argv[sys.argv.index("--json") + 1] if "--json" in sys.argv else None
    if out_json in args:
        args.remove(out_json)
    apksim = args[1] if len(args) > 1 else "work/apksim.jar"
    fw = {}
    level = 10000
    with zipfile.ZipFile(args[0]) as z:
        if "build.prop" in z.namelist():
            m = re.search(r"ro\.build\.version\.sdk=(\d+)", z.read("build.prop").decode("utf-8", "replace"))
            level = int(m.group(1)) if m else level
        for n in z.namelist():
            if n.endswith(".class"):
                c = parse(z.read(n))
                if c:
                    fw[c.name] = c

    def has(owner, member, kind):
        """True/False, or None when the hierarchy leaves the jar (a JDK class: not judged)."""
        seen, todo, left = set(), [owner], False
        while todo:
            o = todo.pop()
            if o in seen:
                continue
            seen.add(o)
            c = fw.get(o)
            if c is None:
                left = True
                continue
            if member in (c.fields if kind == "field" else c.methods):
                return True
            if c.super:
                todo.append(c.super)
            todo.extend(c.ifaces)
        if kind == "method" and member[0] == "<init>":
            return False
        # Ancestors outside the jar: Robolectric's marker interfaces declare nothing the framework calls; a JDK
        # interface is judged by its few method names; any other JDK ancestor (a collection, a stream) is not judged.
        for o in seen - set(fw):
            if o == "java/lang/Object" or not o.startswith(("java/", "javax/", "kotlin/")):
                continue
            if o not in JDK_INTERFACES:
                return None
            if kind == "method" and member[0] in JDK_INTERFACES[o]:
                return True
        return False

    def params(desc):
        return re.findall(r"\[*(?:L[^;]+;|[BCDFIJSZ])", desc[1:desc.index(")")])

    def binds(target, name, desc):
        """Robolectric binds an @Implementation by name and parameter types; an Object parameter stands for any."""
        want = params(desc)
        seen, todo = set(), [target.name]
        while todo:
            o = todo.pop()
            c = fw.get(o)
            if c is None or o in seen:
                continue
            seen.add(o)
            for mname, mdesc in c.methods:
                have = params(mdesc)
                if mname == name and len(have) == len(want) and all(
                        w == h or w == "Ljava/lang/Object;" for w, h in zip(want, have)):
                    return True
            if name == "<init>":
                return False
            todo.append(c.super)
            todo.extend(c.ifaces)
        return False

    object_methods = {"getClass", "hashCode", "equals", "toString", "clone", "notify", "notifyAll", "wait", "finalize"}
    by_file = collections.defaultdict(lambda: collections.defaultdict(set))
    with zipfile.ZipFile(apksim) as z:
        own = {n[:-6] for n in z.namelist() if n.endswith(".class")}
        for n in z.namelist():
            if not n.endswith(".class"):
                continue
            c = parse(z.read(n), refs=True)
            if c is None:
                continue
            where = n.rsplit("/", 1)[0] + "/" + (c.source or n.rsplit("/", 1)[1])
            found = by_file[where]

            def missing(name):
                name = element_class(name) if name else None
                return bool(name) and FRAMEWORK.match(name) and name not in fw and name not in own

            for k in c.classes:
                if missing(k):
                    found["class"].add(element_class(k))
            for owner, name, desc in c.mrefs:
                owner = element_class(owner)
                if owner is None or not FRAMEWORK.match(owner) or owner not in fw:
                    continue  # a missing owner is already under "class"
                if name in object_methods:
                    continue
                if has(owner, (name, desc), "method") is False:
                    found["method"].add("%s.%s%s" % (owner, name, desc))
            for owner, name, desc in c.frefs:
                if FRAMEWORK.match(owner) and owner in fw and has(owner, (name, desc), "field") is False:
                    found["field"].add("%s.%s:%s" % (owner, name, desc))
            for s in c.strings:
                t = s.replace(".", "/")
                if (re.fullmatch(r"[a-z][\w/$]*/[A-Z][\w$]*", t) and FRAMEWORK.match(t) and t not in fw
                        and t not in own and not re.search(r"/[A-Z0-9_]+$", t)
                        and not re.search(r"/(action|category|extra|intent|permission)/", t)):
                    found["string"].add(t)
            if c.implements is not None:
                if c.implements and missing(c.implements):
                    found["shadows"].add("%s -> %s" % (c.name.rsplit("/", 1)[1], c.implements))
                for name, desc in c.sigs:
                    for k in desc_classes(desc):
                        if missing(k):
                            found["sig"].add("%s.%s: %s" % (c.name.rsplit("/", 1)[1], name, k))
                target = fw.get(c.implements)
                for name, desc, values in c.impls if target else ():
                    if name == "__staticInitializer__":
                        continue
                    if values.get("minSdk", 0) > level or -1 < values.get("maxSdk", -1) < level:
                        found["gated"].add("%s.%s (minSdk %s)" % (
                            c.name.rsplit("/", 1)[1], name, values.get("minSdk", "-")))
                        continue
                    if not binds(target, "<init>" if name == "__constructor__" else name, desc):
                        found["unbound"].add("%s.%s%s on %s" % (
                            c.name.rsplit("/", 1)[1], name, desc.rsplit(")", 1)[0] + ")", c.implements))

    kinds = ["shadows", "sig", "unbound", "gated", "class", "method", "field", "string"]
    rows = [(sum(len(v[k]) for k in kinds), f, v) for f, v in by_file.items() if any(v[k] for k in kinds)]
    rows.sort(key=lambda r: (-r[0], r[1]))
    total = collections.Counter()
    for count, f, v in rows:
        print("%s (%d)" % (f, count))
        for k in kinds:
            total[k] += len(v[k])
            for item in sorted(v[k]):
                print("  %-7s %s" % (k, item))
    print("\n%d of %d apksim source files reference something %s lacks: %s" % (
        len(rows), len(by_file), args[0].rsplit("/", 1)[-1], ", ".join("%d %s" % (total[k], k) for k in kinds)))
    if out_json:
        with open(out_json, "w") as f:
            json.dump({name: {k: sorted(v[k]) for k in kinds if v[k]} for _, name, v in rows}, f, indent=1)


if __name__ == "__main__":
    main()
