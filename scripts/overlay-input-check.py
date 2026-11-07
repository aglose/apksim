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


def send(sock, cmd):
    sock.sendall((cmd + "\n").encode())
    time.sleep(0.4)
    sock.settimeout(0.5)
    buf = b""
    try:
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
    except OSError:
        pass
    return buf.decode(errors="replace")


def field_text(reply):
    m = re.search(r'field clickable text="(.*?)" ; \[56,\d+,\d+,\d+\] text="Trip note"', reply, re.S)
    return m.group(1) if m else None


def main():
    port = int(sys.argv[1])
    sock = socket.create_connection(("127.0.0.1", port), timeout=5)
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
