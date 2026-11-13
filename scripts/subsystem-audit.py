#!/usr/bin/env python3
"""Which device subsystems an APK's code refers to, against what apksim simulates.

    scripts/subsystem-audit.py [--json] [--all] APK...

Reads every classes*.dex (stored or deflated), the binary manifest and the lib/*/*.so imports, with the standard
library only. Detection uses names the platform fixes and R8 never renames: framework class and method names,
AIDL descriptors and intent actions in the string table, permission and feature names, and native symbol
imports. Library classes (androidx, Play Services) are renamed by R8, so they are found by those strings too.
A reference is not a call: library code that is never reached (compat shims) still counts, so read the table as
"what the APK could ask for". A "site" is one distinct DEX type, method, field or string entry that matches (not a call count: a call site
count would need the instructions decoded). Exit 0 unless an APK cannot be read.
"""
import json, re, struct, sys, zipfile

# ---------------------------------------------------------------------------------------------------------------
# apksim's status per subsystem, in one place. Take them from AGENTS.md and the README's Limitations; update an
# entry when a stand-in lands. status: simulated | partial | in progress | missing.
STATUS = {
    'sensors': ('in progress', 'none yet', 'motion/body sensors: a stand-in is being built; UPDATE THIS ENTRY WHEN IT LANDS'),
    'health-connect': ('in progress', 'none yet', 'no Health Connect service; a stand-in is being built; UPDATE THIS ENTRY WHEN IT LANDS'),
    'play-location': ('missing', '', 'FusedLocationProviderClient needs Play Services; the plain LocationManager is simulated'),
    'play-services': ('partial', 'push', 'no Play Services; bind to a missing service returns false; FCM pushes via `push`'),
    'firebase': ('partial', 'push', 'FCM messages are delivered by `push`; other Firebase services reach the network only'),
    'ble': ('simulated', '--ble FILE|PRESET, ble', 'scripted peripherals through the framework scanner and GATT'),
    'bluetooth-classic': ('missing', '', 'adapter state only (`bluetooth on|off`); no sockets, A2DP or headset profiles'),
    'nfc': ('missing', '', 'no NFC adapter'),
    'telephony-sms': ('missing', '', 'Robolectric stubs only; telephony features are not declared'),
    'usb': ('missing', '', 'no USB host or accessory'),
    'camera': ('partial', '--camera fake, camera', 'Camera2 and CameraX see a test pattern or image, not a webcam; no encoder surfaces'),
    'microphone': ('missing', '', 'AudioRecord/MediaRecorder have no input device (audio output plays)'),
    'location-manager': ('simulated', 'location, --permissions', 'LocationManager fed by `location LAT LNG|run|bike|drive|gpx`'),
    'biometrics': ('simulated', 'biometrics, --biometrics', 'BiometricPrompt and FingerprintManager answered by `biometrics`'),
    'keystore': ('partial', '', 'test keystore: keys persist in the clear, no use restrictions enforced'),
    'mediacodec': ('partial', '--video, --audio', 'H.264, HEVC, AAC, MP3 decoders; no VP9/AV1/Opus/Vorbis; encoders get nothing'),
    'webview': ('partial', '', 'Robolectric WebView stand-in; pages are not rendered'),
    'background-work': ('simulated', 'jobs, services, alarms', 'WorkManager, JobScheduler, AlarmManager and services run on the simulated clock'),
    'native-gles': ('partial', '--gles on|off', 'offscreen GLES on ANGLE in test runs; GLES in native libraries is a stub'),
    'native-audio': ('missing', '', 'OpenSL ES / AAudio libraries are stubs (Java AudioTrack plays)'),
}

# Detection rules. Each rule: kind 't' (DEX type descriptor, regex on the 'Lpkg/Name;' form), 'm' (method ref,
# regex on 'Lpkg/Name;->method'), 'f' (field ref, same shape), 's' (string, regex), 'p'/'u' (manifest permission /
# uses-feature name regex), 'n' (native import symbol regex). Classes are matched on framework names only.
RULES = {
    'sensors': [('t', r'^Landroid/hardware/(SensorManager|SensorEventListener|SensorEvent|TriggerEvent)\b'),
                ('t', r'^Landroid/hardware/Sensor;'), ('u', r'^android\.hardware\.sensor\.'),
                ('p', r'^android\.permission\.(BODY_SENSORS|ACTIVITY_RECOGNITION|HIGH_SAMPLING_RATE_SENSORS)'),
                ('t', r'^Lcom/google/android/gms/location/ActivityRecognition')],
    'health-connect': [('t', r'^Landroid/health/connect/'), ('t', r'^Landroidx/health/connect/'),
                       ('s', r'androidx\.health\.ACTION_|android\.health\.connect|com\.google\.android\.apps\.healthdata|androidx\.health\.connect'),
                       ('p', r'^android\.permission\.health\.|^androidx\.health\.')],
    'play-location': [('t', r'^Lcom/google/android/gms/location/(FusedLocationProviderClient|LocationServices|SettingsClient|LocationCallback|LocationRequest)\b'),
                      ('s', r'com\.google\.android\.gms\.location\.internal\.|com\.google\.android\.gms\.location\.places\.|IGoogleLocationManagerService|com\.google\.android\.location\.internal')],
    'play-services': [('t', r'^Lcom/google/android/gms/'), ('s', r'^com\.google\.android\.gms(\.|$)|GoogleApiAvailability|com\.google\.android\.gsf'),
                      ('s', r'^com\.google\.android\.c2dm\.')],
    'firebase': [('t', r'^Lcom/google/firebase/'), ('s', r'^com\.google\.firebase\.|firebase-?(messaging|analytics|crashlytics)')],
    'ble': [('t', r'^Landroid/bluetooth/le/'), ('t', r'^Landroid/bluetooth/(BluetoothGatt|BluetoothGattCallback|BluetoothGattServer|BluetoothGattCharacteristic|BluetoothGattService|BluetoothGattDescriptor)'),
            ('m', r'^Landroid/bluetooth/BluetoothDevice;->connectGatt'), ('u', r'^android\.hardware\.bluetooth_le$')],
    'bluetooth-classic': [('t', r'^Landroid/bluetooth/(BluetoothSocket|BluetoothServerSocket|BluetoothA2dp|BluetoothHeadset|BluetoothHidDevice|BluetoothHealth|BluetoothSap|BluetoothPbap)'),
                          ('m', r'^Landroid/bluetooth/BluetoothDevice;->(createRfcommSocket|createInsecureRfcommSocket|createL2capChannel)'),
                          ('m', r'^Landroid/bluetooth/BluetoothAdapter;->(listenUsingRfcomm|listenUsingInsecureRfcomm|startDiscovery|getProfileProxy|getBondedDevices)'),
                          ('u', r'^android\.hardware\.bluetooth$')],
    'nfc': [('t', r'^Landroid/nfc/'), ('p', r'^android\.permission\.NFC'), ('u', r'^android\.hardware\.nfc'),
            ('s', r'^android\.nfc\.action\.')],
    'telephony-sms': [('t', r'^Landroid/telephony/'), ('t', r'^Lcom/android/internal/telephony/'), ('p', r'^android\.permission\.(SEND_SMS|READ_SMS|RECEIVE_SMS|READ_PHONE_STATE|READ_PHONE_NUMBERS|CALL_PHONE|READ_CALL_LOG|ANSWER_PHONE_CALLS)$'),
                      ('u', r'^android\.hardware\.telephony'), ('s', r'^android\.provider\.Telephony\.|^android\.intent\.action\.(NEW_OUTGOING_CALL|PHONE_STATE)$')],
    'usb': [('t', r'^Landroid/hardware/usb/'), ('u', r'^android\.hardware\.usb\.'), ('s', r'^android\.hardware\.usb\.action\.')],
    'camera': [('t', r'^Landroid/hardware/camera2/'), ('t', r'^Landroid/hardware/Camera;'), ('t', r'^Landroidx/camera/(lifecycle/ProcessCameraProvider|core/(CameraSelector|ImageAnalysis|ImageCapture|Preview|Camera|CameraControl)|video/\w+|view/PreviewView)\b'),
               ('p', r'^android\.permission\.CAMERA$'), ('u', r'^android\.hardware\.camera'),
               ('s', r'androidx\.camera\.|CameraX')],
    'microphone': [('t', r'^Landroid/media/(AudioRecord|MediaRecorder|SpeechRecognizer)\b'), ('p', r'^android\.permission\.RECORD_AUDIO$'),
                   ('u', r'^android\.hardware\.microphone'), ('t', r'^Landroid/speech/')],
    'location-manager': [('t', r'^Landroid/location/(LocationManager|LocationListener|Location|GnssStatus|OnNmeaMessageListener)\b'),
                         ('p', r'^android\.permission\.ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION$'), ('u', r'^android\.hardware\.location')],
    'biometrics': [('t', r'^Landroid/hardware/biometrics/'), ('t', r'^Landroidx/biometric/'), ('t', r'^Landroid/hardware/fingerprint/'),
                   ('t', r'^Landroidx/core/hardware/fingerprint/'), ('p', r'^android\.permission\.(USE_BIOMETRIC|USE_FINGERPRINT)$'),
                   ('u', r'^android\.hardware\.fingerprint$'), ('s', r'androidx\.biometric\.')],
    'keystore': [('t', r'^Landroid/security/keystore/'), ('s', r'^AndroidKeyStore$'), ('t', r'^Landroid/security/KeyChain')],
    'mediacodec': [('t', r'^Landroid/media/(MediaCodec|MediaExtractor|MediaMuxer|MediaCodecList|MediaPlayer|MediaDrm|MediaCrypto)\b'),
                   ('s', r'^video/(avc|hevc|x-vnd\.on2\.vp[89]|av01)$|^audio/(mp4a-latm|opus|vorbis|mpeg)$')],
    'webview': [('t', r'^Landroid/webkit/(WebView|WebViewClient|WebChromeClient|CookieManager|WebSettings)\b'), ('t', r'^Landroidx/webkit/')],
    'background-work': [('t', r'^Landroidx/work/(WorkManager|Worker|CoroutineWorker|ListenableWorker|OneTimeWorkRequest|PeriodicWorkRequest|WorkRequest|Configuration)\b'), ('t', r'^Landroid/app/job/'), ('t', r'^Landroid/app/AlarmManager;'),
                        ('p', r'^android\.permission\.(SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|RECEIVE_BOOT_COMPLETED|FOREGROUND_SERVICE)'),
                        ('s', r'^androidx\.work\.impl\.background')],
    'native-gles': [('n', r'^(gl[A-Z]\w+|egl[A-Z]\w+)$')],
    'native-audio': [('n', r'^(slCreateEngine|SL_IID_\w+|AAudio\w+)$')],
}
RULES_C = {k: [(kind, re.compile(rx)) for kind, rx in v] for k, v in RULES.items()}


# ---------------------------------------------------------------------------------------------------------------
def mutf8(b):
    return b.decode('utf-8', 'replace')


def uleb(d, p):
    r = s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s; s += 7
        if not b & 0x80:
            return r, p


def read_dex(d):
    """Return (strings, types, methods, fields) of one DEX file: methods and fields as 'Lc;->name'."""
    if d[:4] != b'dex\n':
        raise ValueError('not a DEX file')
    (ssz, soff, tsz, toff, psz, poff, fsz, foff, msz, moff) = struct.unpack_from('<10I', d, 0x38)
    strings = []
    for i in range(ssz):
        off = struct.unpack_from('<I', d, soff + i * 4)[0]
        _, p = uleb(d, off)
        strings.append(mutf8(d[p:d.index(b'\0', p)]))
    types = [strings[struct.unpack_from('<I', d, toff + i * 4)[0]] for i in range(tsz)]
    methods = []
    for i in range(msz):
        c, _, n = struct.unpack_from('<HHI', d, moff + i * 8)
        methods.append(types[c] + '->' + strings[n])
    fields = []
    for i in range(fsz):
        c, _, n = struct.unpack_from('<HHI', d, foff + i * 8)
        fields.append(types[c] + '->' + strings[n])
    return strings, types, methods, fields


# Binary XML (AXML) -------------------------------------------------------------------------------------------
def parse_axml(d):
    """Yield (element name, {attribute name: value}) for each start tag of a binary manifest."""
    typ, hsz, size = struct.unpack_from('<HHI', d, 0)
    if typ != 0x0003:
        raise ValueError('not binary XML')
    pos, pool, resmap = hsz, [], []
    while pos < min(size, len(d)):
        t, h, sz = struct.unpack_from('<HHI', d, pos)
        if t == 0x0001:
            n, _, flags, sstart, _ = struct.unpack_from('<IIIII', d, pos + 8)
            utf8 = flags & 0x100
            offs = struct.unpack_from('<%dI' % n, d, pos + 28)
            base = pos + sstart
            for o in offs:
                p = base + o
                if utf8:
                    _, k = (d[p], 1) if not d[p] & 0x80 else (0, 2)
                    p += k
                    ln = d[p]; k = 1
                    if ln & 0x80:
                        ln = ((ln & 0x7f) << 8) | d[p + 1]; k = 2
                    pool.append(d[p + k:p + k + ln].decode('utf-8', 'replace'))
                else:
                    ln = struct.unpack_from('<H', d, p)[0]; k = 2
                    if ln & 0x8000:
                        ln = ((ln & 0x7fff) << 16) | struct.unpack_from('<H', d, p + 2)[0]; k = 4
                    pool.append(d[p + k:p + k + ln * 2].decode('utf-16le', 'replace'))
        elif t == 0x0180:
            resmap = struct.unpack_from('<%dI' % ((sz - h) // 4), d, pos + h)
        elif t == 0x0102:
            ns, nm = struct.unpack_from('<ii', d, pos + 16)[0:2]
            name = pool[struct.unpack_from('<i', d, pos + 20)[0]]
            astart, asz, cnt = struct.unpack_from('<HHH', d, pos + 24)
            attrs = {}
            for i in range(cnt):
                ap = pos + 16 + astart + i * asz
                _ns, an, raw = struct.unpack_from('<iii', d, ap)
                vtype = d[ap + 15]
                vdata = struct.unpack_from('<I', d, ap + 16)[0]
                aname = pool[an] if 0 <= an < len(pool) else ''
                if not aname and an >= 0 and an < len(resmap):
                    aname = hex(resmap[an])
                if vtype == 0x03:
                    val = pool[vdata]
                elif vtype == 0x12:
                    val = bool(vdata)
                elif vtype in (0x10, 0x11):
                    val = vdata
                elif raw >= 0:
                    val = pool[raw]
                else:
                    val = vdata
                attrs[aname] = val
            yield name, attrs
        pos += sz


def manifest_info(d):
    info = {'package': None, 'minSdk': None, 'targetSdk': None, 'permissions': [], 'features': []}
    for el, a in parse_axml(d):
        if el == 'manifest':
            info['package'] = a.get('package')
        elif el == 'uses-sdk':
            info['minSdk'] = a.get('minSdkVersion', info['minSdk'])
            info['targetSdk'] = a.get('targetSdkVersion', info['targetSdk'])
        elif el in ('uses-permission', 'uses-permission-sdk-23') and a.get('name'):
            info['permissions'].append(a['name'])
        elif el == 'uses-feature':
            if a.get('name'):
                info['features'].append((a['name'], a.get('required', True) is not False))
            elif 'glEsVersion' in a:
                info['features'].append(('glEsVersion=0x%x' % a['glEsVersion'], True))
    return info


# ELF imports (the approach of so-imports.py, but only the undefined symbols) -------------------------------------
def elf_undefined(d):
    phoff = struct.unpack_from('<Q', d, 32)[0]; phes, phn = struct.unpack_from('<HH', d, 54)
    segs, dyn = [], None
    for i in range(phn):
        t, fl, off, va, pa, fs, ms, al = struct.unpack_from('<IIQQQQQQ', d, phoff + i * phes)
        if t == 1: segs.append((va, off, fs))
        if t == 2: dyn = (off, fs)
    if dyn is None:
        return []
    v2o = lambda v: next((v - va + o for va, o, f in segs if va <= v < va + f), None)
    tags = {}
    for i in range(dyn[1] // 16):
        tag, val = struct.unpack_from('<qQ', d, dyn[0] + i * 16)
        tags.setdefault(tag, val)
    if 5 not in tags or 6 not in tags:
        return []
    strtab, symtab = v2o(tags[5]), v2o(tags[6])
    n = 0
    if 4 in tags:
        n = struct.unpack_from('<I', d, v2o(tags[4]) + 4)[0]
    elif 0x6ffffef5 in tags:
        h = v2o(tags[0x6ffffef5]); nb, so, bs, _ = struct.unpack_from('<IIII', d, h)
        buckets = h + 16 + bs * 8; last = max(struct.unpack_from('<%dI' % nb, d, buckets))
        if last:
            chain = buckets + nb * 4
            while not struct.unpack_from('<I', d, chain + (last - so) * 4)[0] & 1:
                last += 1
            n = last + 1
        else:
            n = so
    out = []
    for i in range(1, n):
        nm, info, oth, shndx, val, sz = struct.unpack_from('<IBBHQQ', d, symtab + i * 24)
        if shndx == 0:
            out.append(d[strtab + nm: d.index(b'\0', strtab + nm)].decode('latin1'))
    return out


# ---------------------------------------------------------------------------------------------------------------
def audit(apk):
    z = zipfile.ZipFile(apk)
    names = z.namelist()
    if 'AndroidManifest.xml' not in names:
        raise ValueError('no AndroidManifest.xml')
    man = manifest_info(z.read('AndroidManifest.xml'))
    dexes = sorted(n for n in names if re.fullmatch(r'classes\d*\.dex', n))
    if not dexes:
        raise ValueError('no classes.dex')
    hits = {k: {'n': 0, 'example': None, 'ex_kind': '', 'kinds': {}} for k in RULES}

    def hit(sub, kind, text):
        h = hits[sub]
        h['n'] += 1
        h['kinds'][kind] = h['kinds'].get(kind, 0) + 1
        if h['example'] is None or (h['ex_kind'] not in 'tm' and kind in 'tm'):
            h['example'] = text
            h['ex_kind'] = kind

    def scan(kind, items):
        for it in items:
            if kind == 't' and re.search(r'/R(\$|;)', it):
                continue  # resource ids, not code
            if kind == 't' and it.startswith('Landroidx/') and '$' in it:
                continue  # a library's inner and lambda classes are one API
            for sub, rules in RULES_C.items():
                for k, rx in rules:
                    if k == kind and rx.search(it):
                        hit(sub, kind, it)
                        break

    sensor_types = set()
    for dn in dexes:
        strings, types, methods, fields = read_dex(z.read(dn))
        # Only framework-looking names can match; skip the obvious app/library bulk quickly via the regexes.
        scan('t', types)
        scan('m', methods)
        scan('f', fields)
        scan('s', [s for s in strings if 6 < len(s) < 200])
        for f in fields:
            m = re.match(r'^Landroid/hardware/Sensor;->(TYPE_\w+)$', f)
            if m:
                sensor_types.add(m.group(1))
    for p in man['permissions']:
        scan('p', [p])
    for f, req in man['features']:
        scan('u', [f])
    libs = {}
    for n in names:
        if re.fullmatch(r'lib/[^/]+/[^/]+\.so', n):
            arch = n.split('/')[1]
            if arch not in ('arm64-v8a', 'x86_64'):
                continue
            try:
                syms = elf_undefined(z.read(n))
            except Exception:
                continue
            for s in syms:
                libs.setdefault(s, n.split('/')[-1])
    scan('n', list(libs))
    for k, h in hits.items():
        h.pop('ex_kind', None)
        if h['example'] and h['kinds'].get('n'):
            h['example'] += ' (' + libs.get(h['example'], '?') + ')'
    return {'apk': apk, 'package': man['package'], 'minSdk': man['minSdk'], 'targetSdk': man['targetSdk'],
            'permissions': sorted(set(man['permissions'])), 'features': man['features'],
            'sensorTypes': sorted(sensor_types), 'dex': len(dexes),
            'subsystems': {k: {'sites': h['n'], 'example': h['example'], 'by': h['kinds'],
                               'status': STATUS[k][0], 'driver': STATUS[k][1], 'note': STATUS[k][2]}
                           for k, h in hits.items()}}


def show(r, everything):
    print('== %s' % r['apk'].split('/')[-1])
    print('   package %s, minSdk %s, targetSdk %s, %d dex file(s), %d permissions' %
          (r['package'], r['minSdk'], r['targetSdk'], r['dex'], len(r['permissions'])))
    feats = ['%s%s' % (n, '' if req else ' (optional)') for n, req in r['features']]
    if feats:
        print('   uses-feature: ' + ', '.join(feats))
    if r['sensorTypes']:
        print('   Sensor.TYPE_* seen: ' + ', '.join(r['sensorTypes']))
    rows = [(k, v) for k, v in r['subsystems'].items() if everything or v['sites']]
    rows.sort(key=lambda kv: (-(kv[1]['sites'] > 0), {'missing': 0, 'in progress': 1, 'partial': 2, 'simulated': 3}[kv[1]['status']], -kv[1]['sites']))
    w = max([len(k) for k, _ in rows] + [9])
    print('   %-*s  %6s  %-11s  %-24s  %s' % (w, 'subsystem', 'sites', 'apksim', 'driven by', 'example'))
    for k, v in rows:
        ex = (v['example'] or '-')
        if len(ex) > 70:
            ex = ex[:67] + '...'
        print('   %-*s  %6d  %-11s  %-24s  %s' % (w, k, v['sites'], v['status'], v['driver'] or '-', ex))


def main(argv):
    as_json = '--json' in argv
    everything = '--all' in argv
    apks = [a for a in argv if not a.startswith('--')]
    if not apks:
        print(__doc__.strip().split('\n\n')[0], file=sys.stderr)
        print('usage: subsystem-audit.py [--json] [--all] APK...', file=sys.stderr)
        return 2
    out, bad = [], 0
    for a in apks:
        try:
            out.append(audit(a))
        except Exception as e:
            print('error: %s: %s' % (a, e), file=sys.stderr)
            bad += 1
    if as_json:
        print(json.dumps(out, indent=1))
    else:
        for r in out:
            show(r, everything)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
