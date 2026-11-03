#!/usr/bin/env python3
"""Drives a running instance over its --control port. Usage: drive.py PORT 'cmd' ['cmd' ...] ; 'wait MS' sleeps."""
import socket, sys, time
port = int(sys.argv[1])
for _ in range(400):
    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=1); break
    except OSError:
        time.sleep(0.05)
f = s.makefile("rw")
s.settimeout(30)
for cmd in sys.argv[2:]:
    if cmd.startswith("wait "):
        time.sleep(int(cmd.split()[1]) / 1000); continue
    f.write(cmd + "\n"); f.flush()
    print(cmd, "->", f.readline().strip())
