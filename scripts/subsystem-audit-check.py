#!/usr/bin/env python3
"""Checks scripts/subsystem-audit.py on the test app's APK: what the app is known to use is found, what it never
touches is not, the manifest is read, and --json parses.

    scripts/subsystem-audit-check.py [APK]
"""
import json, os, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    here, '..', 'testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk')
r = subprocess.run([sys.executable, os.path.join(here, 'subsystem-audit.py'), '--json', apk],
                   capture_output=True, text=True)
assert r.returncode == 0, r.stderr
(a,) = json.loads(r.stdout)
subs = a['subsystems']
fails = []


def want(cond, msg):
    if not cond:
        fails.append(msg)


# Known uses (the test app's BleActivity, CameraActivity, Settings tab, Keystore button, ExoPlayer clip, ...).
for k in ('ble', 'camera', 'location-manager', 'keystore', 'mediacodec', 'background-work', 'webview',
          'biometrics', 'bluetooth-classic'):
    want(subs[k]['sites'] > 0, '%s: expected referenced, found none' % k)
# Never used: these must not match (a false positive would send the owner after the wrong stand-in).
for k in ('health-connect', 'play-location', 'nfc', 'usb', 'native-audio', 'firebase'):
    want(subs[k]['sites'] == 0, '%s: expected unused, found %s (%s)' % (k, subs[k]['sites'], subs[k]['example']))
want(subs['ble']['status'] == 'simulated' and '--ble' in subs['ble']['driver'], 'ble status/driver')
want(subs['sensors']['status'] == 'in progress', 'sensors status')
want(a['package'] == 'dev.apksim.composeapp', 'package %r' % a['package'])
want(str(a['minSdk']).isdigit() and str(a['targetSdk']).isdigit(), 'sdk levels %r %r' % (a['minSdk'], a['targetSdk']))
for p in ('android.permission.CAMERA', 'android.permission.ACCESS_FINE_LOCATION', 'android.permission.INTERNET'):
    want(p in a['permissions'], 'permission %s missing' % p)
want(a['dex'] >= 1, 'no dex read')
t = subprocess.run([sys.executable, os.path.join(here, 'subsystem-audit.py'), apk], capture_output=True, text=True)
want(t.returncode == 0 and 'subsystem' in t.stdout and 'ble' in t.stdout, 'table output')
bad = subprocess.run([sys.executable, os.path.join(here, 'subsystem-audit.py'), __file__], capture_output=True, text=True)
want(bad.returncode == 1 and 'error' in bad.stderr, 'an unreadable APK should exit 1')
# The ELF and manifest paths, which the test app's subsystems do not exercise.
import importlib.util, zipfile
spec = importlib.util.spec_from_file_location('sa', os.path.join(here, 'subsystem-audit.py'))
sa = importlib.util.module_from_spec(spec); spec.loader.exec_module(sa)
z = zipfile.ZipFile(apk)
so = [n for n in z.namelist() if n.endswith('libbitmapfixture.so') and '/arm64-v8a/' in n]
want(so and 'AndroidBitmap_getInfo' in sa.elf_undefined(z.read(so[0])), 'ELF imports of libbitmapfixture.so')
want(any(n == 'android.permission.CAMERA' for n in sa.manifest_info(z.read('AndroidManifest.xml'))['permissions']), 'manifest_info')
if fails:
    print('FAIL:\n  ' + '\n  '.join(fails)); sys.exit(1)
print('subsystem-audit-check: ok (%d subsystems, %d referenced)' % (len(subs), sum(1 for v in subs.values() if v['sites'])))
