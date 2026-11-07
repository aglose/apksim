#!/usr/bin/env python3
"""Capture a connected device's identity and display as a profile for `apksim --device-profile FILE`.

  scripts/device-profile.py [-s SERIAL] [--setting global:KEY ...] > tablet.props

Reads `getprop`, `wm size`, `wm density` and `pm list features` over adb. Lines are KEY=VALUE:
display.size (px), display.density (dpi), build.FIELD, feature.NAME, setting.NS:KEY. The fingerprint is left out
(test runs keep Robolectric's). A feature the profile lacks is not removed from the simulated device.
"""
import argparse, re, subprocess, sys

PROPS = {
    "DEVICE": "ro.product.device", "MODEL": "ro.product.model", "PRODUCT": "ro.product.name",
    "BOARD": "ro.product.board", "HARDWARE": "ro.hardware", "MANUFACTURER": "ro.product.manufacturer",
    "BRAND": "ro.product.brand", "ID": "ro.build.id", "DISPLAY": "ro.build.display.id",
    "TAGS": "ro.build.tags", "TYPE": "ro.build.type", "VERSION.RELEASE": "ro.build.version.release",
    "VERSION.INCREMENTAL": "ro.build.version.incremental",
    "VERSION.SECURITY_PATCH": "ro.build.version.security_patch",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial")
    ap.add_argument("--setting", action="append", default=[], metavar="NS:KEY",
                    help="a Settings key to copy (global|secure|system:KEY), repeatable")
    a = ap.parse_args()
    adb = ["adb"] + (["-s", a.serial] if a.serial else [])

    def sh(*cmd):
        r = subprocess.run(adb + ["shell", *cmd], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"adb shell {' '.join(cmd)} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout.strip()

    out = []
    # `wm size` may list "Physical size" and an "Override size" (which wins); same for density.
    size = re.findall(r"(\d+)x(\d+)", sh("wm", "size").split("Override size:")[-1])
    dens = re.findall(r"(\d+)", sh("wm", "density").split("Override density:")[-1])
    if size and dens:
        out.append(f"display.size={size[-1][0]}x{size[-1][1]}")
        out.append(f"display.density={dens[-1]}")
    for field, prop in PROPS.items():
        v = sh("getprop", prop)
        if v:
            out.append(f"build.{field}={v}")
    for line in sh("pm", "list", "features").splitlines():
        m = re.match(r"feature:([\w.]+)", line.strip())
        if m:
            out.append(f"feature.{m.group(1)}")
    for s in a.setting:
        m = re.fullmatch(r"(global|secure|system):(\S+)", s)
        if not m:
            sys.exit(f"--setting takes global|secure|system:KEY, not {s}")
        v = sh("settings", "get", m.group(1), m.group(2))
        if v and v != "null":
            out.append(f"setting.{s}={v}")
    print("\n".join(out))


main()
