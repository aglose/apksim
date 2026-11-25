#!/usr/bin/env python3
"""Checks crawl.py's data comparison without an app (docs/crawl.md): a SQLite database is its tables' content, not its
files (a changed -wal or a Room bookkeeping row is no change; a row in a user table is, and names the table), a file that
is not a database is None, and --data-ignore patterns match a file, a file#table or a file#key."""
import os, sqlite3, sys, tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crawl

d = tempfile.mkdtemp(prefix="crawl-data-check-")
p = os.path.join(d, "a.db")
c = sqlite3.connect(p)
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
assert crawl.BOOKKEEPING.search("databases/x.db-wal") and crawl.BOOKKEEPING.search("databases/x.db-journal")
open(os.path.join(d, "n"), "w").write("x")
assert crawl.sqlite_tables(os.path.join(d, "n")) is None
ig = ["databases/a.db#events", "shared_prefs/*.xml#last*", "databases/b*.db"]
assert crawl.data_ignored(ig, "databases/a.db", "events")
assert not crawl.data_ignored(ig, "databases/a.db", "t") and not crawl.data_ignored(ig, "databases/a.db")
assert crawl.data_ignored(ig, "shared_prefs/x.xml", "last_seen")
assert crawl.data_ignored(ig, "databases/b1.db") and crawl.data_ignored(ig, "databases/b1.db", "t")
print("crawl-data-check: all passed")
