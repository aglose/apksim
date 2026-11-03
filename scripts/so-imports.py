#!/usr/bin/env python3
"""Lists what an APK's arm64 .so files import that they do not provide themselves."""
import struct, sys, zipfile, collections
def parse(d):
    phoff = struct.unpack_from('<Q', d, 32)[0]; phes, phn = struct.unpack_from('<HH', d, 54)
    segs = []; dyn = None
    for i in range(phn):
        t, fl, off, va, pa, fs, ms, al = struct.unpack_from('<IIQQQQQQ', d, phoff + i * phes)
        if t == 1: segs.append((va, off, fs))
        if t == 2: dyn = (off, fs)
    v2o = lambda v: next((v - va + o for va, o, f in segs if va <= v < va + f), None)
    tags = {}; needed = []
    for i in range(dyn[1] // 16):
        tag, val = struct.unpack_from('<qQ', d, dyn[0] + i * 16)
        if tag == 1: needed.append(val)
        else: tags[tag] = val
    strtab = v2o(tags[5]); symtab = v2o(tags[6])
    name = lambda o: d[strtab + o: d.index(b'\0', strtab + o)].decode('latin1')
    # Symbol count from the relocations' max symbol index plus the hash table.
    n = 0
    if 4 in tags: n = struct.unpack_from('<I', d, v2o(tags[4]) + 4)[0]
    else:
        if 0x6ffffef5 in tags:
            h = v2o(tags[0x6ffffef5]); nb, so, bs, _ = struct.unpack_from('<IIII', d, h)
            buckets = h + 16 + bs * 8; last = max(struct.unpack_from('<%dI' % nb, d, buckets))
            if last:
                chain = buckets + nb * 4
                while not struct.unpack_from('<I', d, chain + (last - so) * 4)[0] & 1: last += 1
                n = last + 1
            else: n = so
    und = []; defd = []
    for i in range(1, n):
        nm, info, oth, shndx, val, sz = struct.unpack_from('<IBBHQQ', d, symtab + i * 24)
        (und if shndx == 0 else defd).append(name(nm))
    return [name(x) for x in needed], und, defd
for apk in sys.argv[1:]:
    print('==', apk.split('/')[-1])
    z = zipfile.ZipFile(apk)
    libs = {}
    for e in z.namelist():
        if e.startswith('lib/arm64-v8a/') and e.endswith('.so'):
            libs[e.split('/')[-1]] = parse(z.read(e))
    provided = set(s for v in libs.values() for s in v[2])
    allimp = collections.Counter()
    for k, (need, und, defd) in sorted(libs.items()):
        ext = [u for u in und if u not in provided]
        print(f'  {k}: needs {[x for x in need if x not in libs]} imports {len(und)}, unresolved in APK {len(ext)}')
        allimp.update(ext)
    print('  total distinct external symbols:', len(allimp))
    print('  ', ' '.join(sorted(allimp)))
