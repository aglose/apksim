#!/usr/bin/env python3
"""Typing reaches the focused field under an empty overlay window added after the activity (issue 123).

Usage: overlay-input-check.py PORT [APK-NAME]
Drives a running instance of the test app over its --control port: opens Trips with the overlay
(apksim://composeapp/trips?overlay=1), taps the Trip note field, types 'abc' and presses DEL. The field must read 'ab'.
Start the instance with --soft-keyboard off or on; the field is at the device coordinates of a 822x1782 phone.
Exit 0 when it passes, 1 otherwise.
"""
import re
import socket
import sys
import time


def send(sock, cmd, timeout=10.0):
    """Sends one control command and returns its reply: the bytes up to the terminating newline.

    The control protocol ends each reply with a newline. A timeout or a closed connection is an error, not the end of
    a reply, so a slow command cannot leave its reply to be read as the next command's.
    """
    sock.sendall((cmd + "\n").encode())
    sock.settimeout(timeout)
    buf = b""
    while not buf.endswith(b"\n"):
        try:
            chunk = sock.recv(65536)
        except OSError as e:
            raise RuntimeError(f"no reply to {cmd!r} within {timeout} s: {e}")
        if not chunk:
            raise RuntimeError(f"connection closed before the reply to {cmd!r}")
        buf += chunk
    return buf.decode(errors="replace").rstrip("\n")


def field_text(reply):
    m = re.search(r'field clickable text="(.*?)" ; \[56,\d+,\d+,\d+\] text="Trip note"', reply, re.S)
    return m.group(1) if m else None


def main():
    port = int(sys.argv[1])
    sock = socket.create_connection(("127.0.0.1", port), timeout=10)
    send(sock, "open apksim://composeapp/trips?overlay=1")
    time.sleep(1.2)
    send(sock, "tap 408 1486")
    time.sleep(0.8)
    send(sock, "type abc")
    send(sock, "key DEL")
    time.sleep(0.5)
    text = field_text(send(sock, "nodes"))
    print("field text:", repr(text))
    ok = text == "ab"
    print("PASS" if ok else "FAIL: typed text did not reach the focused field under the overlay")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
