#!/usr/bin/env python3
"""Checks scripts/subsystem-audit.py: the test app's APK (what its own classes and its libraries are known to use,
what neither touches), the status table's structure, the ELF import path, and the manifest parser on a committed
binary manifest (testapks/subsystem-audit-fixture), also with its attribute names blanked.

    scripts/subsystem-audit-check.py [APK]

It asserts nothing about a subsystem's apksim status beyond the table being well formed, so it passes before and
after a stand-in lands and its entry is updated.
"""
import importlib.util, json, os, subprocess, sys, zipfile

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.join(here, '..')
apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, 'testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk')
script = os.path.join(here, 'subsystem-audit.py')
spec = importlib.util.spec_from_file_location('sa', script)
sa = importlib.util.module_from_spec(spec); spec.loader.exec_module(sa)
fails = []


def want(cond, msg):
    if not cond:
        fails.append(msg)


# The status table is well formed: same keys as the rules, valid statuses, a driver for everything simulated.
want(set(sa.STATUS) == set(sa.RULES), 'STATUS and RULES keys differ: %s' % sorted(set(sa.STATUS) ^ set(sa.RULES)))
for k, (st, drv, note) in sa.STATUS.items():
    want(st in ('simulated', 'partial', 'in progress', 'missing'), '%s: status %r' % (k, st))
    want(st != 'simulated' or drv, '%s: simulated without a driver' % k)
    want(note, '%s: no note' % k)

r = subprocess.run([sys.executable, script, '--json', apk], capture_output=True, text=True)
assert r.returncode == 0, r.stderr
(a,) = json.loads(r.stdout)
subs = a['subsystems']
code = lambda k: subs[k]['app'] + subs[k]['lib']
# The test app's own classes (BleActivity, CameraActivity, the Keystore button, the Bind button, BiometricPrompt).
for k in ('ble', 'camera', 'keystore', 'biometrics'):
    want(subs[k]['app'] > 0, '%s: expected its own classes to refer to it' % k)
# Its libraries (AppCompat's TwilightManager, CameraX, WorkManager) refer to these through code, not just the manifest.
for k in ('location-manager', 'background-work', 'camera', 'mediacodec'):
    want(code(k) > 0, '%s: expected code references (the manifest alone does not count)' % k)
# Not used by the app or its libraries: a hit would send the owner after the wrong stand-in.
for k in ('nfc', 'usb', 'firebase', 'play-location', 'play-services', 'native-audio'):
    want(code(k) + subs[k]['manifest'] == 0, '%s: expected unused, found %s (%s)' % (k, subs[k], subs[k]['example']))
for k in ('telephony-sms', 'microphone', 'sensors', 'webview'):   # library compat code only
    want(subs[k]['app'] == 0, '%s: the app\'s own classes do not use it, found %d' % (k, subs[k]['app']))
want(a['package'] == 'dev.apksim.composeapp' and a['appPrefix'] == 'Ldev/apksim/composeapp/', 'package/prefix %r %r' % (a['package'], a['appPrefix']))
want(str(a['minSdk']).isdigit() and str(a['targetSdk']).isdigit(), 'sdk levels %r %r' % (a['minSdk'], a['targetSdk']))
for p in ('android.permission.CAMERA', 'android.permission.ACCESS_FINE_LOCATION', 'android.permission.INTERNET'):
    want(p in a['permissions'], 'permission %s missing' % p)
want(a['dex'] >= 1, 'no dex read')
t = subprocess.run([sys.executable, script, apk], capture_output=True, text=True)
want(t.returncode == 0 and 'subsystem' in t.stdout and 'ble' in t.stdout, 'table output')
bad = subprocess.run([sys.executable, script, __file__], capture_output=True, text=True)
want(bad.returncode == 1 and 'error' in bad.stderr, 'an unreadable APK should exit 1')
want(subprocess.run([sys.executable, script, '--jsno', apk], capture_output=True).returncode == 2, 'unknown flag should exit 2')

# Counts are of distinct names, whatever number of dex files refer to them.
z = zipfile.ZipFile(apk)
seen = {}
def rep(cls, kind, text):
    seen.setdefault((kind, text), 0)
for n in z.namelist():
    if n.endswith('.dex'):
        sa.read_dex(z.read(n), rep)
want(subs['location-manager']['lib'] <= len([1 for (k, t) in seen if k == 't' and t.startswith('Landroid/location/')]), 'location count exceeds distinct types')

# ELF imports (the test app's bitmap fixture library calls libjnigraphics).
so = [n for n in z.namelist() if n.endswith('libbitmapfixture.so') and '/arm64-v8a/' in n]
want(so and 'AndroidBitmap_getInfo' in sa.elf_undefined(z.read(so[0])), 'ELF imports of libbitmapfixture.so')

# The manifest parser, on a committed binary manifest.
fx = open(os.path.join(root, 'testapks/subsystem-audit-fixture/AndroidManifest.xml'), 'rb').read()
def blank(d):
    # Same-length garbage over the attribute names in the UTF-16 string pool, so only resource ids identify them.
    for nm in ('name', 'minSdkVersion', 'targetSdkVersion', 'required', 'glEsVersion'):
        d = d.replace(nm.encode('utf-16le'), 'x'.encode('utf-16le') * len(nm))
    return d
for label, data in (('plain', fx), ('names blanked', blank(fx))):
    m = sa.manifest_info(data)
    want(m['package'] == 'fixture.audit', '%s: package %r' % (label, m['package']))
    want((m['minSdk'], m['targetSdk']) == (26, 28), '%s: sdk %r %r' % (label, m['minSdk'], m['targetSdk']))
    want(m['permissions'] == ['android.permission.NFC'], '%s: permissions %r' % (label, m['permissions']))
    want(m['features'] == [('android.hardware.bluetooth_le', False), ('android.hardware.camera', True), ('glEsVersion=0x30001', True)],
         '%s: features %r' % (label, m['features']))
    want(m['actions'] == ['android.hardware.usb.action.USB_DEVICE_ATTACHED'], '%s: actions %r' % (label, m['actions']))
want(blank(fx) != fx and 'minSdkVersion'.encode('utf-16le') not in blank(fx), 'the blanking helper changed nothing')
if fails:
    print('FAIL:\n  ' + '\n  '.join(fails)); sys.exit(1)
print('subsystem-audit-check: ok (%d subsystems, %d referenced)' % (len(subs), sum(1 for v in subs.values() if v['app'] + v['lib'] + v['manifest'])))
