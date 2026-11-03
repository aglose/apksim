#!/usr/bin/env python3
"""Summarises a macOS `sample` file: for busy threads, top self-time symbols and top inclusive frames matching a filter."""
import collections, re, sys
txt = open(sys.argv[1]).read()
sec = txt.split('Call graph:')[1].split('Total number in stack')[0]
idle = re.compile(r'mach_msg|kevent|__psynch|__semwait|__ulock|semaphore_wait|__select|__gettimeofday|poll\b')
for t in re.split(r'\n(?=\s{4}\d+ Thread_)', sec):
    lines = t.split('\n')
    head = lines[0].strip()
    parsed = []
    for l in lines[1:]:
        m = re.match(r'^([\s+!:|]*)(\d+) (.*)$', l)
        if m: parsed.append((len(m.group(1)), int(m.group(2)), re.sub(r'\s+\(in .*', '', m.group(3))[:90]))
    self_ = collections.Counter(); incl = collections.Counter()
    for i, (d, n, s) in enumerate(parsed):
        child = 0
        for j in range(i + 1, len(parsed)):
            if parsed[j][0] <= d: break
            if parsed[j][0] == min(p[0] for p in parsed[i + 1:j + 1]): child += parsed[j][1]
        if n - child > 0: self_[s] += n - child
        incl[s] = max(incl[s], n)
    busy = sum(v for k, v in self_.items() if not idle.search(k))
    if busy < int(sys.argv[2]) if len(sys.argv) > 2 else busy < 100: continue
    print('==', head[:60], 'busy', busy)
    for k, v in [x for x in self_.most_common(40) if not idle.search(x[0])][:int(sys.argv[3]) if len(sys.argv) > 3 else 14]: print('  self %6d %s' % (v, k))
    pat = re.compile(sys.argv[4]) if len(sys.argv) > 4 else None
    if pat:
        for k, v in sorted(incl.items(), key=lambda x: -x[1]):
            if pat.search(k): print('  incl %6d %s' % (v, k))
