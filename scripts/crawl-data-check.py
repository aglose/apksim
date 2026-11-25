#!/usr/bin/env python3
"""Checks crawl.py's data comparison without an app (docs/crawl.md): a SQLite database is its tables' content, not its
files (a changed -wal or a Room bookkeeping row is no change; a row in a user table is, and names the table), a file that
is not a database is None, and --data-ignore patterns match a file, a file#table or a file#key."""
import os, shutil, sqlite3, sys, tempfile, types

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crawl

with tempfile.TemporaryDirectory(prefix="crawl-data-check-") as d:
    p = os.path.join(d, "a.db")
    c = sqlite3.connect(p)
    try:
        c.execute("pragma journal_mode=wal")
        for t in ("events", "t", "room_master_table", "room_table_modification_log"):
            c.execute(f"create table {t}(x)")
        c.commit()
        a = crawl.sqlite_tables(p)
        assert set(a) == {"events", "t"}, a
        c.execute("insert into room_master_table values(1)")
        c.execute("insert into room_table_modification_log values(1)")
        c.commit()
        assert crawl.sqlite_tables(p) == a, "framework bookkeeping counted as a change"
        c.execute("insert into events values(1)")
        c.commit()
        b = crawl.sqlite_tables(p)
        assert b["t"] == a["t"] and b["events"] != a["events"], b
        # Non-UTF-8 bytes in a TEXT column must not fail the database read.
        c.execute("insert into t values(cast(x'fffe' as text))")
        c.commit()
        b2 = crawl.sqlite_tables(p)
        assert b2 is not None and b2["t"] != b["t"] and b2["events"] == b["events"], b2
        open(os.path.join(d, "n"), "w").write("x")
        assert crawl.sqlite_tables(os.path.join(d, "n")) is None
        ig = ["databases/a.db#events", "shared_prefs/*.xml#last*", "databases/b*.db"]
        assert crawl.data_ignored(ig, "databases/a.db", "events")
        assert not crawl.data_ignored(ig, "databases/a.db", "t") and not crawl.data_ignored(ig, "databases/a.db")
        assert crawl.data_ignored(ig, "shared_prefs/x.xml", "last_seen")
        assert crawl.data_ignored(ig, "databases/b1.db") and crawl.data_ignored(ig, "databases/b1.db", "t")
        # Reordered rows are no change; an unreadable database keeps its -wal in the comparison.
        c.execute("vacuum")
        c2 = crawl.sqlite_tables(p)
        assert c2 == b2, (c2, b2)
    finally:
        c.close()
    enc = os.path.join(d, "databases")
    os.makedirs(enc)
    for n, body in (("e.db", b"x" * 100), ("e.db-wal", b"w"), ("e.db-journal", b"j"), ("ok.db-wal", b"w")):
        open(os.path.join(enc, n), "wb").write(body)
    shutil.copy(p, os.path.join(enc, "ok.db"))
    # A readable journal-mode=persist database has a zeroed -journal that stays omitted when the DB is read.
    jp = os.path.join(enc, "j.db")
    jc = sqlite3.connect(jp)
    try:
        jc.execute("pragma journal_mode=persist")
        jc.execute("create table t(x)")
        jc.execute("insert into t values(1)")
        jc.commit()
    finally:
        jc.close()
    assert os.path.exists(jp + "-journal")
    inst = types.SimpleNamespace(ctx={}, slot_dirs=lambda: [d])
    state = crawl.Instance.data_state.__get__(inst)
    inst.say = lambda *a: ""
    st = state()
    assert "databases/e.db-wal" in st and "databases/e.db-journal" in st, sorted(st)
    assert "databases/ok.db-wal" not in st and isinstance(st["databases/ok.db"], dict), sorted(st)
    assert "databases/j.db-journal" not in st and isinstance(st["databases/j.db"], dict), sorted(st)
    # Ignoring a database file as a whole ignores its -wal and -journal too; ignoring <permission> drops it.
    inst.ctx = {"data_ignore": ["databases/ok.db", "databases/e.db", "databases/j.db", "<permission>"]}
    st2 = state()
    assert not any(k.startswith(("databases/ok.db", "databases/e.db", "databases/j.db")) for k in st2), sorted(st2)
    assert "<permission>" not in st2, sorted(st2)
    # Ignoring a single table drops only that table and still omits the readable database's -wal.
    inst.ctx = {"data_ignore": ["databases/ok.db#events"]}
    st3 = state()
    assert set(st3["databases/ok.db"]) == {"t"} and "databases/ok.db-wal" not in st3, st3
print("crawl-data-check: all passed")
