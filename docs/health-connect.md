# Health Connect

Apps that read health data (steps, heart rate, sleep, workouts) through Health Connect found it missing here and hid
what they would have shown. apksim now runs a stand-in for the platform's Health Connect service: the framework's own
`HealthConnectManager` talks to it, AndroidX's `HealthConnectClient` finds Health Connect available, and the records
it reads come from the launch (`--health FILE|PRESET`), the `health` command, or what the app itself wrote.

```bash
./apksim --health week app.apk                        # a plausible week of steps, heart rate, sleep and runs
./apksim --health records.json --permissions grant app.apk
python3 scripts/drive.py 7400 'health' 'health add {"type":"weight","time":"-1h","kg":70.1}' 'health clear weight'
```

## How apps reach Health Connect (traced)

Traced with `scripts/jdi/Trace.java` on a large R8 app that shows a fitness card and a heart-rate chip from Health
Connect, and read from AndroidX's `connect-client` 1.1.0 and the framework jar (Android 16, the android-all jar
Robolectric runs):

- On API 34 and later Health Connect is part of the platform, not a provider app. `HealthConnectClient.getSdkStatus`
  on API 34+ (`HealthConnectClient$Api34Impl`) answers SDK_AVAILABLE (3) when the user is not a profile and
  `context.getSystemService("healthconnect")` is not null, else SDK_UNAVAILABLE (1). It never looks for the
  `com.google.android.apps.healthdata` package there; that is the path for API 28-33. `getOrCreate` then builds
  `HealthConnectClientUpsideDownImpl`, which holds the platform's `HealthConnectManager` and turns every call into one
  of the manager's.
- The framework registers that service in `SystemServiceRegistry` through `HealthServicesInitializer`: name
  `healthconnect`, a `HealthConnectManager` around `IHealthConnectService.Stub.asInterface(binder)`, where the binder
  is `ServiceManager.getServiceOrThrow("healthconnect")`. It answers null on embedded, leanback (TV) and automotive
  devices.
- In apksim before this, Robolectric's ServiceManager had no `healthconnect` service: the trace shows
  `getServiceOrThrow("healthconnect")` from `SystemServiceRegistry$149.createService`, which threw
  `ServiceNotFoundException: No service published for: healthconnect`; `getSystemService` logged it and returned
  null, `getSdkStatus` said unavailable, and the app hid its card. That exception was the only one in the app's log.
- Permissions: Health Connect's permissions (`android.permission.health.READ_STEPS`...) are runtime permissions on API
  34+. AndroidX's request contract (`PermissionController.createRequestPermissionResultContract()`) is then
  `RequestMultiplePermissions`, an ordinary runtime permission request, and `getGrantedPermissions()` reads the app's
  own `PackageInfo` (`requestedPermissionsFlags & REQUESTED_PERMISSION_GRANTED` for each `android.permission.health.`
  permission), not the service. `revokeAllPermissions()` is `revokeSelfPermissionsOnKill`.
- Reads, writes, aggregates and changes go through the manager to `IHealthConnectService`: `insertRecords`,
  `readRecords`, `updateRecords`, `deleteUsingFilters`, `aggregateRecords`, `getChangeLogToken`, `getChangeLogs`, each
  answering through a callback binder (`onResult`/`onError(HealthConnectExceptionParcel)`). The manager hands results
  to the app's executor; AndroidX turns `HealthConnectException` codes into Kotlin exceptions (ERROR_SECURITY becomes
  `SecurityException`, ERROR_INVALID_ARGUMENT `IllegalArgumentException`...).

So the place to stand in is the manager's binder, as `dev.apksim.ble` answers the Bluetooth binders: everything the
framework and AndroidX do on the app's side runs as it is.

## Records

A file holds one record, a list, or `{"origin": "com.example.watch", "records": [...]}`. Each record has a `type` and
the fields of its type; `origin` (the data origin's package, default `dev.apksim.health`) and `clientId` (the client
record id: the same id replaces the record) are optional on any record.

| type | fields |
|---|---|
| `steps` | `start`, `end`, `count` |
| `distance` | `start`, `end`, `meters` |
| `active-calories-burned` (`active-calories`) | `start`, `end`, `kcal` |
| `total-calories-burned` (`total-calories`, `calories`) | `start`, `end`, `kcal` |
| `heart-rate` | `start`, `samples` (`[{"time": ..., "bpm": 72}]`) or `bpm`, optional `end` |
| `resting-heart-rate` | `time`, `bpm` |
| `weight` | `time`, `kg` |
| `height` | `time`, `meters` |
| `sleep-session` (`sleep`) | `start`, `end`, optional `title`, `stages` (`[{"start", "end", "stage": "deep"}]`: awake, sleeping, light, deep, rem, out-of-bed, in-bed) |
| `exercise-session` (`exercise`) | `start`, `end`, `exercise` (`running`, `walking`, `biking`, `strength-training`... or the number), optional `title` |
| `floors-climbed` | `start`, `end`, `floors` |
| `hydration` | `start`, `end`, `liters` |
| `oxygen-saturation` | `time`, `percent` |
| `blood-pressure` | `time`, `systolic`, `diastolic` (mmHg) |
| `body-fat` | `time`, `percent` |

Other record types have no seed format yet; apps still write, read, aggregate and delete them all (below). Times are
ISO instants (`2026-11-12T07:30:00Z`), local date-times (`2026-11-12T07:30`), or relative to the app's clock
(`SimClock`, so `--clock` moves them): `now`, `-2h`, `-1d-30m`, `today 07:30`, `yesterday 23:10`, `-3d 18:00`.

```json
{"origin": "com.example.watch", "records": [
  {"type": "steps", "start": "today 08:00", "end": "today 09:00", "count": 1420},
  {"type": "heart-rate", "start": "-10m", "samples": [{"time": "-10m", "bpm": 71}, {"time": "-5m", "bpm": 76}]},
  {"type": "sleep", "start": "yesterday 23:05", "end": "today 06:50",
   "stages": [{"start": "yesterday 23:05", "end": "today 01:00", "stage": "light"},
              {"start": "today 01:00", "end": "today 06:50", "stage": "deep"}]},
  {"type": "exercise", "start": "-2d 18:00", "end": "-2d 18:45", "exercise": "running", "title": "Evening run"}
]}
```

The preset `week` is seven days up to now in the app's time zone: steps, distance and active calories for each waking
hour, total calories for every hour, heart rate every five minutes (ten at night) up to now, a resting heart rate and
a weight each morning, each night's sleep with light, deep, REM and awake stages, and a 45-minute run at 18:00 every
third day by date. Every value of a day comes from its date alone (each part of the day draws from a random generator
seeded by the date), so a past day is the same at every launch and keeps its records' ids.

## Options and commands

- `--health FILE|PRESET` (repeatable): records to start with. A running instance keeps its records with its data slot
  (`<slot>.device/health.store`, beside /sdcard and the Settings values; `--fresh` and `ctl.py erase` clear it), as a
  device keeps Health Connect's data outside the app's, so it also travels with the slot wherever the slot goes (a
  flight recorder checkpoint, a beam or serve slot pack). The seeds of a launch replace the last launch's seeds, so a
  week preset follows the clock; a launch without `--health` keeps them as they were. A seed without a client id gets
  an id from its content, so seeding the same records again changes nothing (no change-log entries, the same ids);
  what the app or `health add` wrote stays. In a test run every test starts with the seeds
  alone (made once per spec and minute of the clock, then copied). Read again by a warm launch (a standby takes
  `-Dapksim.health.N`). A value that is neither a file, inline JSON nor a preset stops the launcher, and a file whose
  records do not load stops the launch, both with exit 2 (`[apksim] exit 2: bad --health`); in a test run every
  test's setup fails instead. The slot's file is written by a thread of its own shortly after each change (and at
  every way an instance ends), and read only when something first asks for Health Connect: an app that never does
  pays nothing for it at launch. It is kept only for the framework build that wrote it (records are stored as that
  build's parcels).
- `health`: records per type, the app's health permissions (granted and not), and the service calls made since the
  launch (a test's setup): `records: heart-rate 162, sleep-session 7, steps 107, ...; granted: READ_STEPS ...; not
  granted: READ_WEIGHT; calls: aggregateRecords 2, insertRecords 1, readRecords 4`.
- `health add FILE|PRESET|JSON`: adds records now (inline JSON as above).
- `health clear [TYPE]`: removes every record, or those of one type (as deletions, so change tokens see them).
- `health list TYPE`: each record of a type, by start time, with its id (`seed` for the launch's seeds). A seed's id
  comes from its content, so two lists agree exactly where the records are the same.
- `health types`: every record type's name.
- Over `--control`, `adb shell apksim health ...`, and in a test run `executeShellCommand("apksim health ...")` (until
  the next test's setup).
- Permissions are runtime permissions, as on API 34+: `--permissions grant` grants them all, a request (AndroidX's
  contract) shows the usual prompt or waits for `permission allow|deny`, `permission grant health.READ_STEPS` and `pm
  grant PKG android.permission.health.READ_STEPS` grant one. Like every runtime permission here, grants last for the
  launch.

## What the service does

- **Insert**: each record needs its type's write permission (else ERROR_SECURITY, a `SecurityException`); it is stored
  with the caller's package as its data origin, the app's label, the time, and a new id, or, with a client record id,
  an id derived from the package, type and client id, so inserting it again replaces it unless its version is older.
  An empty client record id counts as none (two such records are two, as on an emulator). **Update** needs the record
  to exist, be the caller's and be of the same type (else ERROR_INVALID_ARGUMENT, with the device's "No record found
  for the following input" message).
- **Read** by ids or by filter: the type's read permission reads every origin; with only the write permission the
  caller may read only by naming itself as the only data origin, else ERROR_SECURITY with the device's "... from other
  applications" message (an emulator refuses an unfiltered read too); neither: ERROR_SECURITY. A time filter takes a record whose time
  (an interval's start) is in `[start, end)`: an interval that began before the filter is not read even if it runs into
  it (checked on an API 36 emulator). Local time filters compare local times. Ordered by start time, ascending or
  descending; pages of `pageSize` (default 1000) with a page token that carries the order, as the platform's does.
- **Aggregate** (and grouped by duration or period) for every aggregation the framework defines, found by reflection
  over the record classes' `AggregationType` fields rather than listed: the value is the record's getter returning the
  aggregation's result class (by name where there are several, as NutritionRecord's), or its samples' (heart rate,
  speed, power, cadence, skin temperature). Sums over interval records count the part inside the window (1,000 steps
  over an hour, half of it in the window: 500); averages, minimums and maximums take instant records and samples in the
  window; session durations (sleep, exercise, mindfulness) are the sessions' time in it less the stages and segments
  the framework's `DURATION_EXCLUDE_TYPES` name (awake, out of bed, pauses, rest); a window with no data has no value.
  It needs the read permission. Without a getter to read (basal calories from a metabolic rate) and for activity
  intensity's totals there is no value, nor for a nutrient no record set. Records are converted once per request;
  more than 5000 groups answers ERROR_INVALID_ARGUMENT with the device's message ("Number of buckets must not exceed
  5000", "Number of groups ..."), as does a zero or negative duration or period.
- **Delete** by ids or by filter (types, time range): only the caller's own records, with the write permission.
- **Change logs**: a token remembers where the log stood, which types and which origins; `getChanges` returns each
  record changed since then once (its latest state: the record, or a deletion with its id and time), up to the page
  size (1000), then a token to go on from and `hasMore`. Needs the read permission of each type. The log keeps the last
  20,000 changes; an older token, or one the service did not make, answers ERROR_INVALID_ARGUMENT, which AndroidX
  reports as `changesTokenExpired` (an emulator does the same for a token it does not know).
- **Everything else** in `IHealthConnectService` that has a callback taking a HealthConnectExceptionParcel (medical
  records, priorities, backup and restore, export, data state) answers ERROR_UNSUPPORTED_OPERATION through it; the rest
  (migration and remote-data staging, whose callbacks take other exceptions, and methods that return their answer)
  throw UnsupportedOperationException at once. Either way an app never waits for an answer that does not come.
  Permission grant, revoke and listing through the service work.
- **Open-ended filters and --clock**: a time filter built without an end gets the JDK's `Instant.now()` plus a day,
  in framework code, which `--clock` and the `clock` command do not move; with the app's clock more than a day ahead,
  an open-ended read would miss what the app wrote "now". The service moves such an end (within ten minutes before
  real now plus a day, at ZoneOffset.MAX for a local filter) by the app's clock's offset.

Answers go out on one thread, `apksim-health-binder`, in the order they were decided; the app's executor then gets
them. (A device has a pool of binder threads: an app whose executor runs the answer in place and then blocks on another
Health Connect call there would wait for ever here.) A service method that fails answers ERROR_INTERNAL through its
callback, as the platform does. Code that runs for every app references only framework classes
(`android.health.connect`), never AndroidX's. The service's binder is published before Application.onCreate, but the
interface behind it (a Proxy of the 60-method IHealthConnectService, whose making loads every Health Connect parcel
class) is made at the app's first `getSystemService("healthconnect")`.

## Checks

- `HealthConnectTest` in the test app's suite (18 tests): availability, the granted permissions the client reads,
  insert and read back (order, pages both ways, by id), update, which intervals a time filter takes, upserts by client
  id and an empty client id, heart-rate and weight aggregates, hourly groups, groups by period and a local-time filter,
  the 5000-group cap, a change token seeing an insert and then a deletion, changes in pages of 1000 and a bad token read
  as expired, reads, writes, aggregates, change tokens and deletes without their permission, a write-only app's own
  reads; with `--health week -e healthSeed week` the seeded week as well (CI runs both). Every test but three passes on
  an API 36 emulator as it is (granting itself with `pm grant`); the three apksim-only ones are the seeded week, steps'
  totals and session durations (awake and pauses left out), since a device counts steps, sleep and exercise only from
  apps in Health Connect's priority list, which an app joins when the user grants it in Health Connect's own screens,
  not on `pm grant` (an emulator answers no value there).
- `apksim://health` (`HealthActivity`): availability, permissions (asked for with the client's contract), today's steps,
  the week's steps, the latest heart rate, last night's sleep, and an insert button. `scripts/ci-smoke.sh` adds a week
  with `health add week`, grants the permissions, reads the screen (the week's steps over 20,000) and checks the insert
  adds 100 to today's steps; then it seeds the week at two clocks a day apart and checks the days both cover have the
  same records (`health list steps`).
- With `--health week`, the large app from the trace above shows a card and its heart rate, which it hid before.

## Not done

- **Priorities**: a device counts steps, distance, calories and the other activity totals only from apps in each data
  category's priority list, in its order, without double counting overlapping records. Here every origin counts, as if
  every app had been granted in Health Connect's screens, and overlapping records from several origins are added up.
- The 30-day history limit (reads before the first grant need READ_HEALTH_DATA_HISTORY), background reads
  (READ_HEALTH_DATA_IN_BACKGROUND), rate limits, record validation beyond what the framework's own builders do, and
  change tokens expiring after 30 days (they expire here only once the log has moved past them).
- Health Connect's own screens (the permission rationale, `ACTION_MANAGE_HEALTH_DATA`), exercise routes' consent flow,
  medical records, and apps below API 34's path (a provider app; apksim is always API 36).
- `serve` and `beam` do not accept `--health` (it may name a file); the flight recorder records no health calls (the
  store travels in a checkpoint's slot, above).
- With `--clock` ahead of the real clock, a record the app builds "now" is refused by the framework's own builder,
  which checks for records in the future against the real clock (seeds are built without that check).
- Answers go out on one binder thread, not a pool: an app whose executor runs an answer in place and blocks there on
  another Health Connect call would wait for ever.
