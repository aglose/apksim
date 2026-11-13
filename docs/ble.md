# Bluetooth LE peripherals

An emulator has no Bluetooth LE: an app that scans finds nothing, so its pairing, sync and live-data screens cannot be
run or tested there. apksim plays the device's Bluetooth stack against **scripted peripherals**: the app scans, finds
them, connects, discovers their GATT services, reads, writes and subscribes to notifications through the ordinary
`BluetoothLeScanner` and `BluetoothGatt` APIs, in a running instance and in a test run.

```bash
./apksim --ble heart-rate app.apk                 # a preset
./apksim --ble peripherals.json --ble battery app.apk   # a file and a preset (--ble is repeatable)
python3 scripts/drive.py 7400 'ble' 'ble notify C0:FF:EE:00:18:0D 2A37 00C8' 'ble disconnect C0:FF:EE:00:18:0D'
```

## Peripherals

A JSON file holds one peripheral, a list, or `{"peripherals": [...]}`:

```json
{
  "address": "C0:FF:EE:00:00:01",
  "name": "Trail sensor",
  "rssi": -62,
  "intervalMs": 500,
  "txPower": -4,
  "mtu": 247,
  "latencyMs": 0,
  "bondable": true,
  "advertisement": {
    "connectable": true,
    "includeName": true,
    "serviceUuids": ["180D", "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"],
    "manufacturerData": {"0x0059": "0102"},
    "serviceData": {"180D": "00"}
  },
  "services": [
    {"uuid": "180D", "characteristics": [
      {"uuid": "2A37", "properties": ["notify"], "value": "0048",
       "timeline": {"everyMs": 1000, "values": ["0048", "004A", "004C"], "loop": true}},
      {"uuid": "2A38", "properties": ["read"], "value": "01"},
      {"uuid": "2A39", "properties": ["write", "write-no-response"]}
    ]},
    {"uuid": "6E400001-B5A3-F393-E0A9-E50E24DCCA9E", "characteristics": [
      {"uuid": "6E400003-B5A3-F393-E0A9-E50E24DCCA9E", "properties": ["indicate"],
       "descriptors": [{"uuid": "2901", "value": "5458"}]}
    ]}
  ]
}
```

- UUIDs are 16- or 32-bit short forms on the Bluetooth base UUID, or full 128-bit ones. Values are hex.
- `properties`: `read`, `write`, `write-no-response`, `notify`, `indicate`, `broadcast`, `signed-write` (default
  `read`). A notifying or indicating characteristic gets its Client Characteristic Configuration descriptor (0x2902)
  when the file does not list one, as the specification requires.
- `intervalMs` (default 1000) is the advertising interval: how often a scan reports the peripheral. `latencyMs`
  (default 0) delays every GATT answer by that much uptime. `mtu` (default 517) is the largest ATT MTU it accepts.
- A `timeline` sets the characteristic's value to the next of `values` every `everyMs` of uptime (looping unless
  `"loop": false`) and notifies it to every subscribed client. It runs from the moment the peripheral exists.
- The advertisement is built as the bytes that go over the air (flags, complete local name, 16- and 128-bit service
  UUIDs, TX power, service data, manufacturer data) and parsed by `ScanRecord.parseFromBytes`, so `getBytes()` and
  every getter of the app's `ScanRecord` agree. It is not held to 31 bytes.

Presets: `heart-rate` (`C0:FF:EE:00:18:0D`, "apksim HR": Heart Rate 0x180D with a measurement 0x2A37 notified once a
second from 72 to 80 bpm and back, body sensor location 0x2A38 = chest, control point 0x2A39; Battery 0x180F with
level 0x2A19 = 100) and `battery` (`C0:FF:EE:00:18:0F`, "apksim Battery": level 0x2A19 at 100, falling a percent a
minute to 96). `PRESET:ADDRESS` gives a preset another address (`battery:C0:FF:EE:00:00:42`).

## The `ble` command

Over `--control`, the Mac menu's control socket, `adb shell apksim ble ...`, and in a test run
`executeShellCommand("apksim ble ...")`:

| Command | |
|---|---|
| `ble` | each peripheral: address, name, RSSI, connections and MTU, bonded, characteristics with notifications on (the CCC written), the last value written to each; then the number of scans |
| `ble add FILE\|PRESET[:ADDRESS]` | starts advertising (a running scan reports it; an autoConnect connection waiting for it connects). Same address: the old one goes first |
| `ble remove ADDRESS\|all` | out of range: connections drop with status 8 (GATT_CONN_TIMEOUT), FIRST_MATCH/MATCH_LOST scans hear it lost |
| `ble rssi ADDRESS DBM` | the RSSI scans and `readRemoteRssi` report from now on |
| `ble notify ADDRESS CHAR HEX` | sets the value and notifies it (to clients that asked, see below) |
| `ble set ADDRESS CHAR HEX` | sets the value only: the next read returns it |
| `ble disconnect ADDRESS` | the peripheral hangs up: status 19 (GATT_CONN_TERMINATE_PEER_USER); an autoConnect client reconnects one advertising interval later |

In a test run every test starts with the launch's `--ble` peripherals only, nothing connected or scanning, like the
adapter itself (`Bluetooth.beforeApplicationOnCreate`).

## How it is built

The framework's own client code runs. Robolectric shadows `BluetoothLeScanner` and `BluetoothGatt` with stand-ins of
its own (callbacks run at once on the calling thread, `addScanResult` for tests); apksim replaces those shadows with
empty ones and answers the two binders the real classes talk to, as the Bluetooth app's `ScanController` and
`GattService` do on a device:

- `BluetoothAdapter.getBluetoothScan()` and `getBluetoothGatt()` (`ShadowSimBluetoothAdapter`) return
  `Ble.ScanService` (`IBluetoothScan`) and `Ble.GattService` (`IBluetoothGatt`), or null while the adapter is off.
- `BluetoothDevice.connectGatt` (all overloads, `ShadowSimBluetoothDevice`) goes to the framework's 7-argument one,
  which builds a real `BluetoothGatt`. `createBond` and `removeBond` go to `Ble`.
- `isOffloadedFilteringSupported` and `isOffloadedScanBatchingSupported` are true, as on a phone's controller, so every
  `ScanSettings` callback type and batching are allowed (Robolectric's adapter had no service to ask: false).

So everything the framework does on the app's side is AOSP's, unchanged (Android 16, the android-all jar
Robolectric runs): `BleScanCallbackWrapper` registering the scanner and waiting for the answer, posting results to the
main looper, `SCAN_FAILED_ALREADY_STARTED` for a callback scanning twice, the checks of settings against filters
(FIRST_MATCH/MATCH_LOST need a non-empty filter); `BluetoothGatt`'s busy flag (one outstanding request:
`ERROR_GATT_WRITE_REQUEST_BUSY`, `false` from a read), the property checks before a write
(`ERROR_GATT_WRITE_NOT_ALLOWED`), `runOrQueueCallback` (the app's `Handler` if it gave one, else the binder thread),
handle lookup, and the API 33 callbacks (`onCharacteristicChanged(gatt, c, value)`,
`onCharacteristicRead(gatt, c, value, status)`) whose defaults call the deprecated ones, so both kinds of app work.

### Where each behaviour comes from

The Android SDK's sources (`android-33`, `android-36`) hold the framework's side, which runs as it is. The system
side (`packages/modules/Bluetooth/android/app`: `GattService`, `ScanController`, `AppScanStats`, `Utils`) is not in
the SDK; what apksim does there follows that code as of Android 14-16, and the rest is apksim's choice:

| Behaviour | Source |
|---|---|
| Scan results on the main thread; GATT callbacks on the app's `Handler` or else a binder thread (here `apksim-ble-binder`, no Looper), in order per callback (oneway binder calls) | framework (runs as is) |
| `registerScanner` answered from another thread while the caller waits (up to 2 s): `apksim-ble-binder-2`, so a scan started from a Handler-less GATT callback (itself on `apksim-ble-binder`) registers, as a device's binder pool has more than one thread | framework's wrapper; the answer must come from another thread or the wait runs out |
| Scan permission: BLUETOOTH_SCAN from target 31 (else BLUETOOTH_ADMIN), a `SecurityException` naming it; connect: BLUETOOTH_CONNECT from 31 (else BLUETOOTH), on every GATT call | system side (`Utils.checkScanPermissionForDataDelivery`, `checkConnectPermissionForDataDelivery`) |
| Results only with ACCESS_FINE_LOCATION (target 29+; either location permission below 29), decided as the scan starts, and still held, with location on, at each delivery (apksim does not kill an app on `pm revoke` as a device does, so a revoked permission stops the results), unless BLUETOOTH_SCAN is marked `neverForLocation` (target 31+); without them the scan runs and reports nothing, no error (a permission granted later does not wake a running scan; revoking one kills the app on a device) | system side (`ScanController`, `hasScanResultPermission`, `blockedByLocationOff`) |
| A sixth scan start within 30 s: registration answers SCAN_FAILED_SCANNING_TOO_FREQUENTLY and the app hears nothing at all | system side (`AppScanStats`, 5 starts per 30 s), framework (`mScannerId = -2`, silent) |
| ALL_MATCHES: a result per advertising interval; FIRST_MATCH once, MATCH_LOST when it goes; `reportDelay`: batches every delay and on `flushPendingBatchResults`, the rest on stop; a `PendingIntent` scan gets `EXTRA_LIST_SCAN_RESULT` and `EXTRA_CALLBACK_TYPE`; an opportunistic scan reports only while another scan runs | system side |
| Notifications reach a client only once it called `setCharacteristicNotification` (the stack's registration) **and** the CCC descriptor was written with notify or indicate (the peripheral's side); the CCC is per link, kept for a bonded device | system side (`registerForNotification`) and the GATT specification |
| A GATT call without a connection: nothing (the stack logs it), `ERROR_DEVICE_NOT_CONNECTED` from a write | system side |
| A direct connection to nothing in range: status 133 (GATT_ERROR) after 30 s; autoConnect waits for as long as it takes | system side and what devices report |
| Peripheral out of range: status 8; peripheral hangs up: 19; `disconnect()`: 0; `close()` without `disconnect()`: no callback | Bluetooth core specification's reason codes, as Android passes them on |
| MTU: the smaller of the request (at most 517) and the peripheral's, one per link; `onConnectionUpdated` for `requestConnectionPriority` with AOSP's default intervals; PHY: `setPreferredPhy` gets the fastest PHY asked for, kept per link, which `readPhy` returns; a second client joining a link gets its MTU and PHY; each client's notification payload is its own copy | system side |
| `createBond`: ACTION_BOND_STATE_CHANGED BONDING then BONDED (or NONE for a peripheral that is not there or refuses) 100 ms later; `getBondedDevices` follows; the adapter going off ends an attempt under way with NONE at once | system side's broadcasts; the delay is apksim's |
| The BLE stack is up (or down) before STATE_ON (STATE_OFF) is broadcast | AdapterService's order |
| Adapter off: scans stop silently, connections end with status 22 (GATT_CONN_TERMINATE_LOCAL_HOST), `connectGatt` returns null (no GATT service); a scan started meanwhile fails with SCAN_FAILED_INTERNAL_ERROR (apksim keeps an LE scanner while off, `Bluetooth.java`) | apksim's choice for the status; the rest framework |
| Write without response longer than MTU - 3: status 13 (invalid attribute length) | apksim's choice (one packet, no long write without a response) |
| Write without response still gets `onCharacteristicWrite` | Android's flow control: the stack answers once the packet is sent |
| A value read, written or notified is a copy, and each discovery gets new service objects | binder parcels |

### Threads and clocks

Answers go out on one thread, `apksim-ble-binder` (registerScanner's on a second one, above), in the order they were
decided, as oneway calls to one binder
object arrive. It has no Looper, as a binder thread has none: an app creating a `Handler()` there fails as on a device.
Anything timed (advertising intervals, timelines, batch delays, `latencyMs`, the 30 s direct-connection timeout,
bonding) is posted to the main looper by uptime, so in a running instance it follows real time with the rest of the
app, and in a test run it moves only with the simulated clock: `SystemClock.sleep(1000)` and an idle main looper
bring the next heart-rate notification, deterministically (`BleTest` relies on it). Immediate answers do not wait for
the main thread, so a test may block on a latch for a connection. A reset bumps a generation: an answer queued for the
previous test is dropped; an answer delayed by `latencyMs` is dropped too when its link ends or its client
unregisters first, so a peripheral removed before its connection was answered never reports connected.

No thread is started and nothing is posted until the app scans or connects; without `--ble` an app pays nothing.

## Checks and measurements

- `BleTest` in the test app's suite (the first 17 tests took 9.4 s on the Mac and 8.0 s in the Linux image under Rosetta;
  the whole class, with its 30 s and 40 s waits, ~113 s under `--realtime-clock`, where the clock follows real time;
  `--instrument -e class dev.apksim.composeapp.BleTest`): the advertisement as `ScanRecord` reads it, results on the main thread, filters,
  a result per advertising interval on the simulated clock (3 to 5 in 3 s at 1 s), FIRST_MATCH and MATCH_LOST,
  batching, a `PendingIntent` scan, connect/discover/read/write on a binder thread, the write shown by `ble`,
  `ERROR_GATT_WRITE_NOT_ALLOWED`, MTU 247, RSSI before and after `ble rssi`, the PHY read back after an update, a scan started from a Handler-less
  GATT callback, an answer delayed by latency dropped when the peripheral goes first, a name with a line break kept
  to one reply line, no notification before the CCC write and
  then the timeline in order, `ble notify`, the deprecated callbacks with a `Handler` (every callback on its thread),
  status 19, 8, 133 after 30 s (not before 29 s), autoConnect waiting 40 s for `ble add`, bonding broadcasts
  (pairing ended by the peripheral going or the adapter switching off and on), a second client on one link (its MTU,
  PHY and its own notification payload),
  `SecurityException` for BLUETOOTH_SCAN and BLUETOOTH_CONNECT, no results without location, the sixth scan in 30 s
  hearing nothing, the adapter going off (status 22, a failed scan, `connectGatt` null), and the next test starting
  with no peripherals.
- `scripts/ble-check.py PORT` (CI: `ci-smoke.sh`, against the Linux image) drives `apksim://ble` (`BleActivity`, a
  heart-rate app in miniature: scan with a 0x180D filter, connect, read the sensor location, subscribe, MTU 247) over
  `--control`, with both callback styles (`?legacy=1`). On the Mac: found, connected and subscribed within the 2 s of
  the first wait, notifications at 1.0 and 0.9 a second against the preset's 1 a second, `ble rssi` then the RSSI
  button, the control-point write shown by `ble`, `ble notify` (200 bpm), status 19 and 8; it passes the same way in the
  Linux image (amd64, Rosetta). Run against the same APK on `main`'s build it fails: `unknown command: ble` and the screen stays at "scanning".

## Not done

- The app as a peripheral: `BluetoothGattServer` and advertising stay Robolectric's (`ShadowBluetoothGattServer`,
  `ShadowBluetoothLeAdvertiser`); nothing connects to them.
- Scripted reactions to writes (a write to a control point that notifies an answer): a test can do it with
  `ble notify` after the write shows in `ble`.
- Errors a real link brings: authentication and encryption (status 5/15 and BluetoothGatt's retry), lost packets,
  `onServiceChanged`, MTU exchange refused, slower connection intervals unless `latencyMs` asks for them.
- Reliable writes are applied at once; `executeReliableWrite` only answers `onExecuteWrite`.
- Location switched off mid-scan stops results (checked at each delivery) but is not covered by a test: a device test
  cannot switch location mode here.
- An app targeting below 31 is checked by hand with the target-28 build (CI does not build it) of the test app (`-Papksim.targetSdk=28`: `Target28Test`, and
  `BleTest`'s permission tests, which then revoke BLUETOOTH_ADMIN and BLUETOOTH; docs/conformance.md). Not covered by a
  check: `neverForLocation` (read from
  `PackageInfo.requestedPermissionsFlags`; the test app's BLUETOOTH_SCAN is not marked, to check the location rule).
  The stack's filtering of beacon results for `neverForLocation` scans is not done.
- Classic Bluetooth (RFCOMM sockets, A2DP), L2CAP channels, periodic advertising, LE Audio, the Mac's own Bluetooth.
- `serve` and `beam` do not accept `--ble` (it names a file); `ble add PRESET` from a tab is not allowed either.
- The flight recorder records no BLE traffic.
