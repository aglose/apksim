#!/usr/bin/env python3
"""Which device subsystems an APK's code refers to, against what apksim simulates.

    scripts/subsystem-audit.py [--json] [--all] APK...

Reads every classes*.dex (stored or deflated), the binary manifest and the lib/*/*.so imports, with the standard
library only. Detection uses names the platform fixes and R8 never renames: framework class and method names,
AIDL descriptors and intent actions in the strings code loads, permission and feature names, and native symbol
imports. Library classes (androidx, Play Services) are renamed by R8, so they are found by those strings too.

References are attributed to the class that makes them: the instructions of every method are decoded for their
type, method, field and string operands, and a class's superclass and interfaces count. A class is the APP's when
its descriptor is under the manifest's package (the last segment dropped when no class is under the full name, as
for a `.debug` applicationId suffix); every other class is a LIBRARY (androidx, kotlin, Play Services and anything
R8 moved to the root). The table has one count for each, and one for the manifest (permissions, features, actions).
A count is the number of distinct matching names, not of call sites, and a library's code that is never reached
still counts: "library" means a dependency could ask for it, "app" that the app's own classes do. Exit 0 unless an
APK cannot be read (2 for a bad option).
"""
import json, re, struct, sys, zipfile

# ---------------------------------------------------------------------------------------------------------------
# apksim's status per subsystem, in one place. Take them from AGENTS.md and the README's Limitations; update an
# entry when a stand-in lands. status: simulated | partial | in progress | missing.
STATUS = {
    'sensors': ('simulated', 'sensor, --sensor', 'an emulator\'s sensor list, events at the requested rate (AGENTS.md "sensor")'),
    'health-connect': ('simulated', '--health, health', 'scripted records answer HealthConnectClient (docs/health-connect.md)'),
    'play-location': ('missing', '', 'FusedLocationProviderClient needs Play Services; the plain LocationManager is simulated'),
    'play-services': ('missing', '', 'no Play Services: a bind to a missing service returns false (FCM pushes alone work, see firebase)'),
    'firebase': ('partial', 'push', 'FCM messages are delivered by `push`; other Firebase services reach the network only'),
    'ble': ('simulated', '--ble FILE|PRESET, ble', 'scripted peripherals through the framework scanner and GATT'),
    'bluetooth-classic': ('missing', '', 'adapter state only (`bluetooth on|off`); no sockets, A2DP or headset profiles'),
    'nfc': ('missing', '', 'no NFC adapter'),
    'telephony-sms': ('missing', '', 'Robolectric TelephonyManager/SmsManager stubs: no radio, SMS or calls'),
    'usb': ('missing', '', 'no USB host or accessory'),
    'camera': ('partial', '--camera fake, camera', 'Camera2 and CameraX see a test pattern or image, not a webcam; no encoder surfaces'),
    'camera-legacy': ('missing', '', 'android.hardware.Camera (the deprecated API) is not simulated'),
    'microphone': ('missing', '', 'AudioRecord/MediaRecorder/SpeechRecognizer have no input device (audio output plays)'),
    'location-manager': ('simulated', 'location, --permissions', 'LocationManager fed by `location LAT LNG|run|bike|drive|gpx`'),
    'biometrics': ('simulated', 'biometrics, --biometrics', 'BiometricPrompt and FingerprintManager answered by `biometrics`'),
    'keystore': ('partial', '', 'test keystore: keys persist in the clear, no use restrictions enforced'),
    'mediacodec': ('partial', '--video, --audio', 'MediaCodec: H.264, HEVC, AAC, MP3 decoders; no VP9/AV1/Opus/Vorbis; encoders get nothing'),
    'media-other': ('missing', '', 'MediaPlayer, MediaExtractor, MediaMuxer, MediaDrm, MediaCrypto: Robolectric stand-ins at best (ExoPlayer is the path that is checked)'),
    'webview': ('missing', '', 'Robolectric WebView stand-in: a WebView screen renders nothing'),
    'background-work': ('simulated', 'jobs, services, alarms', 'WorkManager, JobScheduler, AlarmManager and services run on the simulated clock'),
    'native-gles': ('missing', '', 'GLES called from native libraries is a stub (`--gles` covers the Java GLES20/30 API only)'),
    'native-audio': ('missing', '', 'OpenSL ES / AAudio libraries are stubs (Java AudioTrack plays)'),
}

# Detection rules. Each rule: kind 't' (a type a class refers to: new-instance, const-class, check-cast, the owner
# of a called method or field, superclass, interfaces; regex on 'Lpkg/Name;'), 'm' (method called, regex on
# 'Lpkg/Name;->method'), 's' (a string the code loads, regex), 'p'/'u'/'a' (manifest permission / uses-feature /
# intent-filter action regex), 'n' (native import symbol regex).
RULES = {
    'sensors': [('t', r'^Landroid/hardware/(Sensor\w*|TriggerEvent\w*)$'), ('u', r'^android\.hardware\.sensor\.'),
                ('p', r'^android\.permission\.(BODY_SENSORS|ACTIVITY_RECOGNITION|HIGH_SAMPLING_RATE_SENSORS)'),
                ('t', r'^Lcom/google/android/gms/location/ActivityRecognition'), ('n', r'^ASensor\w*$')],
    'health-connect': [('t', r'^Landroid/health/connect/'), ('t', r'^Landroidx/health/connect/'),
                       ('s', r'^(androidx\.health\.|android\.health\.connect|com\.google\.android\.apps\.healthdata)'),
                       ('p', r'^android\.permission\.health\.|^androidx\.health\.'), ('a', r'^androidx\.health\.|^android\.health\.')],
    'play-location': [('t', r'^Lcom/google/android/gms/location/(FusedLocationProviderClient|LocationServices|SettingsClient|LocationCallback|LocationRequest)$'),
                      ('s', r'^com\.google\.android\.gms\.location\.internal\.|IGoogleLocationManagerService')],
    'play-services': [('t', r'^Lcom/google/android/gms/'), ('s', r'^com\.google\.android\.gms\.common\.(GoogleApiAvailability|internal\.IGmsServiceBroker)$|IGmsServiceBroker')],
    'firebase': [('t', r'^Lcom/google/firebase/'), ('s', r'^com\.google\.firebase\.(MESSAGING_EVENT|iid|messaging)'), ('a', r'^com\.google\.firebase\.')],
    'ble': [('t', r'^Landroid/bluetooth/le/'), ('t', r'^Landroid/bluetooth/(BluetoothGatt\w*)$'),
            ('m', r'^Landroid/bluetooth/BluetoothDevice;->connectGatt'), ('u', r'^android\.hardware\.bluetooth_le$')],
    'bluetooth-classic': [('t', r'^Landroid/bluetooth/(BluetoothSocket|BluetoothServerSocket|BluetoothA2dp|BluetoothHeadset|BluetoothHidDevice|BluetoothHealth|BluetoothSap|BluetoothPbap)$'),
                          ('m', r'^Landroid/bluetooth/BluetoothDevice;->(createRfcommSocket|createInsecureRfcommSocket|createL2capChannel)'),
                          ('m', r'^Landroid/bluetooth/BluetoothAdapter;->(listenUsingRfcomm|listenUsingInsecureRfcomm|startDiscovery|getProfileProxy|getBondedDevices)'),
                          ('u', r'^android\.hardware\.bluetooth$')],
    'nfc': [('t', r'^Landroid/nfc/'), ('p', r'^android\.permission\.NFC'), ('u', r'^android\.hardware\.nfc'), ('a', r'^android\.nfc\.action\.')],
    'telephony-sms': [('t', r'^Landroid/telephony/(SmsManager|SmsMessage|PhoneStateListener|TelephonyCallback)'),
                      ('m', r'^Landroid/telephony/TelephonyManager;->(getLine1Number|getDeviceId|getImei|getMeid|getSimState|getSimOperator\w*|getNetworkOperator\w*|listen|getCallState|getDataState|registerTelephonyCallback|getSubscriberId|getPhoneType|getNetworkType|getDataNetworkType|getSimSerialNumber)$'),
                      ('p', r'^android\.permission\.(SEND_SMS|READ_SMS|RECEIVE_SMS|READ_PHONE_STATE|READ_PHONE_NUMBERS|CALL_PHONE|READ_CALL_LOG|ANSWER_PHONE_CALLS)$'),
                      ('u', r'^android\.hardware\.telephony'), ('a', r'^android\.provider\.Telephony\.|^android\.intent\.action\.(NEW_OUTGOING_CALL|PHONE_STATE)$')],
    'usb': [('t', r'^Landroid/hardware/usb/'), ('u', r'^android\.hardware\.usb\.'), ('a', r'^android\.hardware\.usb\.action\.')],
    'camera': [('t', r'^Landroid/hardware/camera2/'), ('p', r'^android\.permission\.CAMERA$'), ('u', r'^android\.hardware\.camera'),
               ('t', r'^Landroidx/camera/(lifecycle/ProcessCameraProvider|core/(CameraSelector|ImageAnalysis|ImageCapture|Preview|Camera|CameraControl)|video/\w+|view/PreviewView)(\$\w+)*$'),
               ('n', r'^ACamera\w*$')],
    'camera-legacy': [('t', r'^Landroid/hardware/Camera$')],
    'microphone': [('t', r'^Landroid/media/(AudioRecord|MediaRecorder)$'), ('t', r'^Landroid/speech/(SpeechRecognizer|RecognizerIntent|Recognition\w*)$'),
                   ('p', r'^android\.permission\.RECORD_AUDIO$'), ('u', r'^android\.hardware\.microphone')],
    'location-manager': [('t', r'^Landroid/location/(LocationManager|LocationListener|Location|GnssStatus|OnNmeaMessageListener)$'),
                         ('p', r'^android\.permission\.ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION$'), ('u', r'^android\.hardware\.location')],
    'biometrics': [('t', r'^Landroid/hardware/biometrics/'), ('t', r'^Landroidx/biometric/'), ('t', r'^Landroid/hardware/fingerprint/'),
                   ('t', r'^Landroidx/core/hardware/fingerprint/'), ('p', r'^android\.permission\.(USE_BIOMETRIC|USE_FINGERPRINT)$'),
                   ('u', r'^android\.hardware\.fingerprint$')],
    'keystore': [('t', r'^Landroid/security/keystore/'), ('s', r'^AndroidKeyStore$'), ('t', r'^Landroid/security/KeyChain')],
    'mediacodec': [('t', r'^Landroid/media/(MediaCodec|MediaCodecList|MediaCodecInfo|MediaFormat)$'), ('n', r'^AMediaCodec\w*$')],
    'media-other': [('t', r'^Landroid/media/(MediaPlayer|MediaExtractor|MediaMuxer|MediaDrm|MediaCrypto)$')],
    'webview': [('t', r'^Landroid/webkit/(WebView|WebViewClient|WebChromeClient|CookieManager|WebSettings)$'), ('t', r'^Landroidx/webkit/')],
    'background-work': [('t', r'^Landroidx/work/(WorkManager|Worker|CoroutineWorker|ListenableWorker|OneTimeWorkRequest|PeriodicWorkRequest|WorkRequest|Configuration)$'),
                        ('t', r'^Landroid/app/job/'), ('t', r'^Landroid/app/AlarmManager$'),
                        ('p', r'^android\.permission\.(SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|RECEIVE_BOOT_COMPLETED|FOREGROUND_SERVICE)')],
    'native-gles': [('n', r'^(gl[A-Z]\w+|egl[A-Z]\w+)$')],
    'native-audio': [('n', r'^(slCreateEngine|SL_IID_\w+|AAudio\w+)$')],
}
RULES_C = {k: [(kind, re.compile(rx)) for kind, rx in v] for k, v in RULES.items()}
CODE_KINDS = 'tmsn'   # what code, as opposed to the manifest, can refer to


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


def _op_units():
    """Instruction length in 16-bit units for each Dalvik opcode."""
    n = [1] * 256
    for op, u in ((0x02, 2), (0x03, 3), (0x05, 2), (0x06, 3), (0x08, 2), (0x09, 3), (0x13, 2), (0x14, 3), (0x15, 2),
                  (0x16, 2), (0x17, 3), (0x18, 5), (0x19, 2), (0x1a, 2), (0x1b, 3), (0x1c, 2), (0x1f, 2), (0x20, 2),
                  (0x22, 2), (0x23, 2), (0x24, 3), (0x25, 3), (0x26, 3), (0x29, 2), (0x2a, 3), (0x2b, 3), (0x2c, 3),
                  (0xfa, 4), (0xfb, 4), (0xfc, 3), (0xfd, 3), (0xfe, 2), (0xff, 2)):
        n[op] = u
    for lo, hi, u in ((0x2d, 0x31, 2), (0x32, 0x3d, 2), (0x44, 0x6d, 2), (0x6e, 0x72, 3), (0x74, 0x78, 3),
                      (0x90, 0xaf, 2), (0xd0, 0xe2, 2)):
        for op in range(lo, hi + 1):
            n[op] = u
    return n


OP_UNITS = _op_units()


def read_dex(d, report):
    """Calls report(class_descriptor, kind, text) for every reference one DEX file's classes make."""
    if d[:4] != b'dex\n':
        raise ValueError('not a DEX file')
    (ssz, soff, tsz, toff, psz, poff, fsz, foff, msz, moff, csz, coff) = struct.unpack_from('<12I', d, 0x38)
    strings = []
    for i in range(ssz):
        off = struct.unpack_from('<I', d, soff + i * 4)[0]
        _, p = uleb(d, off)
        strings.append(mutf8(d[p:d.index(b'\0', p)]))
    types = [strings[struct.unpack_from('<I', d, toff + i * 4)[0]] for i in range(tsz)]
    methods = []   # (owner type index, text)
    for i in range(msz):
        c, _, n = struct.unpack_from('<HHI', d, moff + i * 8)
        methods.append((c, types[c] + '->' + strings[n]))
    fields = []
    for i in range(fsz):
        c, _, n = struct.unpack_from('<HHI', d, foff + i * 8)
        fields.append(c)
    for ci in range(csz):
        cidx, _, sup, ifs, _, _, cdata, _ = struct.unpack_from('<8I', d, coff + ci * 32)
        cls = types[cidx]
        if sup != 0xffffffff:
            report(cls, 't', types[sup])
        if ifs:
            n = struct.unpack_from('<I', d, ifs)[0]
            for k in range(n):
                report(cls, 't', types[struct.unpack_from('<H', d, ifs + 4 + k * 2)[0]])
        if not cdata:
            continue
        p = cdata
        sf, p = uleb(d, p); inf, p = uleb(d, p); dm, p = uleb(d, p); vm, p = uleb(d, p)
        for _ in range(sf + inf):
            _, p = uleb(d, p); _, p = uleb(d, p)
        for _ in range(dm + vm):
            _, p = uleb(d, p); _, p = uleb(d, p); code, p = uleb(d, p)
            if not code:
                continue
            walk_code(d, code, cls, types, strings, methods, fields, report)


def walk_code(d, code, cls, types, strings, methods, fields, report):
    size = struct.unpack_from('<I', d, code + 12)[0]
    p = code + 16
    end = p + size * 2
    while p < end:
        u = struct.unpack_from('<H', d, p)[0]
        op = u & 0xff
        if op == 0 and u != 0:   # payloads: pseudo-instructions
            if u == 0x0100:
                n = struct.unpack_from('<H', d, p + 2)[0]; p += (n * 2 + 4) * 2
            elif u == 0x0200:
                n = struct.unpack_from('<H', d, p + 2)[0]; p += (n * 4 + 2) * 2
            else:
                w, n = struct.unpack_from('<HI', d, p + 2); p += ((w * n + 1) // 2 + 4) * 2
            continue
        if op == 0x1a:
            report(cls, 's', strings[struct.unpack_from('<H', d, p + 2)[0]])
        elif op == 0x1b:
            report(cls, 's', strings[struct.unpack_from('<I', d, p + 2)[0]])
        elif op in (0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25):
            idx = struct.unpack_from('<H', d, p + 2)[0]
            if idx < len(types):
                report(cls, 't', types[idx].lstrip('['))
        elif 0x52 <= op <= 0x6d:
            report(cls, 't', types[fields[struct.unpack_from('<H', d, p + 2)[0]]].lstrip('['))
        elif 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78 or op in (0xfa, 0xfb):
            c, text = methods[struct.unpack_from('<H', d, p + 2)[0]]
            report(cls, 't', types[c].lstrip('['))
            report(cls, 'm', text)
        p += OP_UNITS[op] * 2


# Binary XML (AXML) -------------------------------------------------------------------------------------------
ANDROID_NS = 'http://schemas.android.com/apk/res/android'
# Attribute resource ids: the names in the string pool may be stripped or reused, the ids are what the platform reads.
RES_ATTRS = {0x01010003: 'name', 0x0101020c: 'minSdkVersion', 0x01010270: 'targetSdkVersion',
             0x0101028e: 'required', 0x01010281: 'glEsVersion'}


def parse_axml(d):
    """Yield (element name, {attribute name: value}) for each start tag of a binary manifest. Attributes are keyed by
    their resource id's name when they have one, else by their name when it is in the android namespace or none."""
    typ, hsz, size = struct.unpack_from('<HHI', d, 0)
    if typ != 0x0003:
        raise ValueError('not binary XML')
    pos, pool, resmap = hsz, [], []
    while pos < min(size, len(d)):
        t, h, sz = struct.unpack_from('<HHI', d, pos)
        if sz < 8:
            raise ValueError('bad XML chunk')
        if t == 0x0001:
            n, _, flags, sstart, _ = struct.unpack_from('<IIIII', d, pos + 8)
            utf8 = flags & 0x100
            offs = struct.unpack_from('<%dI' % n, d, pos + 28)
            base = pos + sstart
            for o in offs:
                p = base + o
                if utf8:
                    p += 2 if d[p] & 0x80 else 1      # the length in UTF-16 units, which we skip
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
            name = pool[struct.unpack_from('<i', d, pos + 20)[0]]
            astart, asz, cnt = struct.unpack_from('<HHH', d, pos + 24)
            attrs = {}
            for i in range(cnt):
                ap = pos + 16 + astart + i * asz
                ns, an, raw = struct.unpack_from('<iii', d, ap)
                vtype = d[ap + 15]
                vdata = struct.unpack_from('<I', d, ap + 16)[0]
                rid = resmap[an] if 0 <= an < len(resmap) else 0
                nsuri = pool[ns] if 0 <= ns < len(pool) else ''
                if rid in RES_ATTRS:
                    key = RES_ATTRS[rid]
                else:
                    key = pool[an] if 0 <= an < len(pool) else ''
                    if rid or (nsuri and nsuri != ANDROID_NS):
                        key = 'other:' + key      # another namespace's `name` is not android:name
                if vtype == 0x03:
                    val = pool[vdata]
                elif vtype == 0x12:
                    val = bool(vdata)
                elif vtype in (0x10, 0x11):
                    val = vdata
                elif vtype in (0x01, 0x02):
                    val = ('@ref', vdata)             # a resource: its value is not in the manifest
                elif raw >= 0:
                    val = pool[raw]
                else:
                    val = vdata
                attrs[key] = val
            yield name, attrs
        pos += sz


def manifest_info(d):
    info = {'package': None, 'minSdk': None, 'targetSdk': None, 'permissions': [], 'features': [], 'actions': []}
    for el, a in parse_axml(d):
        if el == 'manifest':
            info['package'] = a.get('package')
        elif el == 'uses-sdk':
            info['minSdk'] = a.get('minSdkVersion', info['minSdk'])
            info['targetSdk'] = a.get('targetSdkVersion', info['targetSdk'])
        elif el in ('uses-permission', 'uses-permission-sdk-23') and isinstance(a.get('name'), str):
            info['permissions'].append(a['name'])
        elif el == 'action' and isinstance(a.get('name'), str):
            info['actions'].append(a['name'])
        elif el == 'uses-feature':
            req = a.get('required', True)
            req = '?' if isinstance(req, tuple) else req is not False   # '?': set through a resource
            if isinstance(a.get('name'), str):
                info['features'].append((a['name'], req))
            elif 'glEsVersion' in a and isinstance(a['glEsVersion'], int):
                info['features'].append(('glEsVersion=0x%x' % a['glEsVersion'], req))
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
LIBRARY_HINT = 'library classes are every class outside the manifest package'


def app_prefix(package, classes):
    """The descriptor prefix of the app's own classes: the package, less its last segment when nothing is under the
    full name (applicationId suffixes such as `.debug`)."""
    segs = (package or '').split('.')
    for n in (len(segs), len(segs) - 1):
        if n < 1:
            break
        pre = 'L' + '/'.join(segs[:n]) + '/'
        if any(c.startswith(pre) for c in classes):
            return pre
    return None


def audit(apk):
    z = zipfile.ZipFile(apk)
    names = z.namelist()
    if 'AndroidManifest.xml' not in names:
        raise ValueError('no AndroidManifest.xml')
    man = manifest_info(z.read('AndroidManifest.xml'))
    warnings = []
    if man['minSdk'] is None and man['targetSdk'] is None:
        warnings.append('no uses-sdk found in the manifest: it may be unreadable')
    dexes = sorted(n for n in names if re.fullmatch(r'classes\d*\.dex', n))
    if not dexes:
        raise ValueError('no classes.dex')
    refs = {}      # (kind, text) -> set of referring classes
    classes = set()

    def report(cls, kind, text):
        classes.add(cls)
        refs.setdefault((kind, text), set()).add(cls)

    for dn in dexes:
        try:
            read_dex(z.read(dn), report)
        except Exception as e:
            raise ValueError('%s: %s' % (dn, e))
    pre = app_prefix(man['package'], classes)
    if pre is None:
        warnings.append('no class is under the manifest package %s: every reference counts as a library\'s' % man['package'])
    is_app = lambda c: pre is not None and c.startswith(pre)

    found = {k: {'app': {}, 'lib': {}, 'manifest': {}} for k in RULES}

    def add(sub, scope, text):
        found[sub][scope].setdefault(text, 1)

    for (kind, text), who in refs.items():
        mt = text[:-1] if kind == 't' and text.endswith(';') else text   # rules anchor types without the ';'
        for sub, rules in RULES_C.items():
            if any(k == kind and rx.search(mt) for k, rx in rules):
                if any(is_app(c) for c in who):
                    add(sub, 'app', text)
                if any(not is_app(c) for c in who):
                    add(sub, 'lib', text)
    mrefs = [('p', p) for p in set(man['permissions'])] + [('u', f) for f, _ in man['features']] + \
            [('a', x) for x in set(man['actions'])]
    for kind, text in mrefs:
        for sub, rules in RULES_C.items():
            if any(k == kind and rx.search(text) for k, rx in rules):
                add(sub, 'manifest', text)
    libs = {}
    for n in names:
        if re.fullmatch(r'lib/[^/]+/[^/]+\.so', n):
            if n.split('/')[1] not in ('arm64-v8a', 'x86_64'):
                continue
            try:
                syms = elf_undefined(z.read(n))
            except Exception as e:
                print('warning: %s: %s: %s' % (apk, n, e), file=sys.stderr)
                continue
            for s in syms:
                libs.setdefault(s, n.split('/')[-1])
    for s, lib in libs.items():
        for sub, rules in RULES_C.items():
            if any(k == 'n' and rx.search(s) for k, rx in rules):
                add(sub, 'lib', '%s (%s)' % (s, lib))
    subs = {}
    for k, f in found.items():
        ex = None
        for scope in ('app', 'lib', 'manifest'):
            if f[scope]:
                ex = sorted(f[scope], key=lambda t: (not t.startswith('L'), t))[0]
                break
        subs[k] = {'app': len(f['app']), 'lib': len(f['lib']), 'manifest': len(f['manifest']), 'example': ex,
                   'status': STATUS[k][0], 'driver': STATUS[k][1], 'note': STATUS[k][2]}
    return {'apk': apk, 'package': man['package'], 'minSdk': man['minSdk'], 'targetSdk': man['targetSdk'],
            'permissions': sorted(set(man['permissions'])), 'features': man['features'], 'dex': len(dexes),
            'appPrefix': pre, 'warnings': warnings, 'subsystems': subs}


ORDER = {'missing': 0, 'in progress': 1, 'partial': 2, 'simulated': 3}


def show(r, everything):
    print('== %s' % r['apk'].split('/')[-1])
    print('   package %s, minSdk %s, targetSdk %s, %d dex file(s), %d permissions; app classes: %s' %
          (r['package'], r['minSdk'], r['targetSdk'], r['dex'], len(r['permissions']), (r['appPrefix'] or 'none found') + '*'))
    for w in r['warnings']:
        print('   warning: ' + w)
    feats = ['%s%s' % (n, '' if req is True else ' (optional)' if req is False else ' (required: ?)') for n, req in r['features']]
    if feats:
        print('   uses-feature: ' + ', '.join(feats))
    rows = [(k, v) for k, v in r['subsystems'].items() if everything or v['app'] + v['lib'] + v['manifest']]
    rows.sort(key=lambda kv: (-(kv[1]['app'] + kv[1]['lib'] + kv[1]['manifest'] > 0), ORDER[kv[1]['status']],
                              -kv[1]['app'], -kv[1]['lib']))
    w = max([len(k) for k, _ in rows] + [9])
    print('   %-*s  %4s %4s %4s  %-11s  %-24s  %s' % (w, 'subsystem', 'app', 'lib', 'mani', 'apksim', 'driven by', 'example'))
    for k, v in rows:
        ex = v['example'] or '-'
        if len(ex) > 64:
            ex = ex[:61] + '...'
        print('   %-*s  %4d %4d %4d  %-11s  %-24s  %s' % (w, k, v['app'], v['lib'], v['manifest'], v['status'],
                                                         v['driver'] or '-', ex))


def main(argv):
    as_json = '--json' in argv
    everything = '--all' in argv
    unknown = [a for a in argv if a.startswith('-') and a not in ('--json', '--all')]
    if unknown:
        print('unknown option: ' + unknown[0], file=sys.stderr)
        return 2
    apks = [a for a in argv if not a.startswith('-')]
    if not apks:
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
    for r in out:
        for w in r['warnings']:
            print('warning: %s: %s' % (r['apk'], w), file=sys.stderr)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
