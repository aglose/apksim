#!/usr/bin/env python3
"""Drives a running instance over its --control port. Usage: drive.py PORT 'cmd' ['cmd' ...] ; 'wait MS' sleeps.
APKSIM_CONTROL_TOKEN_FILE names the token file of an instance started with --control-token-file."""
import os, socket, sys, time
port = int(sys.argv[1])
for _ in range(400):
    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=1); break
    except OSError:
        time.sleep(0.05)
else:
    sys.exit(f"drive.py: nothing is listening on 127.0.0.1:{port} (is the instance running?)")
f = s.makefile("rw")
s.settimeout(30)
token_file = os.environ.get("APKSIM_CONTROL_TOKEN_FILE")  # an instance started with --control-token-file
if token_file:
    f.write("auth " + open(token_file).read().strip() + "\n"); f.flush()
    if f.readline().strip() != "ok":
        sys.exit("drive.py: the instance refused the token in " + token_file)
for cmd in sys.argv[2:]:
    if cmd.startswith("wait "):
        time.sleep(int(cmd.split()[1]) / 1000); continue
    f.write(cmd + "\n"); f.flush()
    print(cmd, "->", f.readline().strip())
