#!/usr/bin/env python3
"""Scripted BLE peripherals as an app finds and talks to them (docs/ble.md), on the test app's apksim://ble screen.

Usage: ble-check.py PORT
Drives a running instance of the test app (822x1782 phone) over its --control port. It adds the heart-rate preset
(`ble add heart-rate`), grants the screen's permissions, opens apksim://ble and expects the screen to have scanned,
connected, read the body sensor location ("sensor: chest"), set a 247-byte MTU and shown heart-rate notifications from
the preset's timeline (one a second). Then: `ble rssi` and the RSSI button, the control-point button (`ble` must show
the write), `ble notify` (200 bpm), and `ble disconnect` ("status 19"). It does it again with the callbacks deprecated
in API 33 (apksim://ble?legacy=1), the peripheral going out of range this time ("status 8"), and removes it.
Exit 0 when it passes, 1 otherwise. Without apksim's BLE peripherals the screen never finds one.
"""
import re
import socket
import sys
import time

ADDR = "C0:FF:EE:00:18:0D"


def send(sock, cmd, timeout=15.0):
    """One control command and its reply (the bytes up to the newline that ends every reply)."""
    sock.sendall((cmd + "\n").encode())
    sock.settimeout(timeout)
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = sock.recv(65536)
        if not chunk:
            raise RuntimeError(f"connection closed before the reply to {cmd!r}")
        buf += chunk
    return buf.decode(errors="replace").rstrip("\n")


def lines(sock):
    """The screen's 'key: value' lines."""
    reply = send(sock, "nodes all")
    return dict(re.findall(r'text="(ble|found|state|sensor|mtu|heart rate|rssi|write): ([^"]*)"', reply))


def wait_for(sock, what, test, timeout=15.0):
    deadline = time.time() + timeout
    seen = {}
    while time.time() < deadline:
        seen = lines(sock)
        if test(seen):
            return seen
        time.sleep(0.3)
    raise AssertionError(f"timed out waiting for {what}; the screen shows {seen}")


def notifications(seen):
    m = re.match(r"(\d+) bpm \((\d+) notifications\)", seen.get("heart rate", ""))
    return (int(m.group(1)), int(m.group(2))) if m else (None, 0)


def check(sock, legacy):
    name = "deprecated callbacks" if legacy else "API 33 callbacks"
    print(f"-- {name}")
    print("ble add:", send(sock, "ble add heart-rate"))
    send(sock, "open apksim://ble" + ("?legacy=1" if legacy else ""))
    seen = wait_for(sock, "two notifications", lambda s: notifications(s)[1] >= 2)
    print("screen:", seen)
    assert seen.get("found", "").startswith(f"apksim HR {ADDR} -58"), seen
    assert seen.get("sensor") == "chest", seen
    assert seen.get("state") == "connected, 2 services", seen
    assert seen.get("mtu") == "247", seen
    bpm, n = notifications(seen)
    assert bpm in (72, 74, 76, 78, 80), f"a value from the preset's timeline, not {bpm}"
    t0 = time.time()
    later = wait_for(sock, "two more notifications", lambda s: notifications(s)[1] >= n + 2)
    rate = (notifications(later)[1] - n) / (time.time() - t0)
    print(f"notifications: {n} -> {notifications(later)[1]}, about {rate:.1f} a second")
    assert 0.4 < rate < 2.5, f"the timeline notifies once a second, not {rate:.1f}"
    print("rssi:", send(sock, f"ble rssi {ADDR} -71"))
    send(sock, "tap-text READ RSSI")
    wait_for(sock, "the new RSSI", lambda s: s.get("rssi") == "-71 dBm")
    send(sock, "tap-text WRITE CONTROL POINT")
    wait_for(sock, "the write", lambda s: s.get("write") == "status 0")
    status = send(sock, "ble")
    print("ble:", status)
    assert f"{ADDR} \"apksim HR\" rssi -71, 1 connection (mtu 247), notifying 2A37, written 2A39=01" in status, status
    send(sock, f"ble notify {ADDR} 2A37 00C8")
    wait_for(sock, "200 bpm", lambda s: notifications(s)[0] == 200)
    if legacy:
        print("remove:", send(sock, f"ble remove {ADDR}"))
        seen = wait_for(sock, "the link timing out", lambda s: s.get("state", "").startswith("disconnected"))
        assert seen["state"] == "disconnected (status 8)", seen
    else:
        print("disconnect:", send(sock, f"ble disconnect {ADDR}"))
        seen = wait_for(sock, "the peer's disconnect", lambda s: s.get("state", "").startswith("disconnected"))
        assert seen["state"] == "disconnected (status 19)", seen
    print("state:", seen["state"])
    send(sock, "back")
    time.sleep(1.0)
    send(sock, "ble remove all")


def main():
    port = int(sys.argv[1])
    sock = socket.create_connection(("127.0.0.1", port), timeout=10)
    for p in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION"):
        send(sock, f"permission grant {p}")
    try:
        check(sock, legacy=False)
        check(sock, legacy=True)
    except AssertionError as e:
        print("FAIL:", e)
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
