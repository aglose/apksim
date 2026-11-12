# Scenes: several linked instances in one script

Some journeys need more than one device: two users of one backend (a chat, a shared list, a marketplace's buyer and
seller), a phone and its watch. `scripts/fleet.py` runs N copies of one script; a **scene** runs different roles, each
its own instance with its own APK, device and options, through one ordered script whose steps wait on one another's
state, against one shared server.

```bash
python3 scripts/scene.py testapks/composeapp/scenes/board.json
python3 scripts/scene.py my-scene.json --out work/scenes/run1 --apk app.apk -- --anim-scale 0
```

## The scene file

```json
{
  "name": "board",
  "fixture": {"command": ["python3", "{scripts}/network-fixture.py", "{out}/fixture"], "ready": "ready (\\d+)"},
  "options": ["--network-host", "api.apksim.test=127.0.0.1:{fixture}", "--network-trust", "{out}/fixture/cert.pem",
              "--network", "record", "{out}/{role}.har"],
  "roles": {
    "alice": {"apk": "../app/build/outputs/apk/debug/app-debug.apk", "device": "phone"},
    "bob": {"apk": "../app/build/outputs/apk/debug/app-debug.apk", "device": "phone", "options": ["--appearance", "dark"]}
  },
  "steps": [
    {"name": "both up", "all": "until 60000 FAB clicks"},
    {"alice": "open apksim://board?user=alice", "bob": "open apksim://board?user=bob"},
    {"alice": ["open apksim://board?user=alice&post=hello%20from%20alice", "until 10000 posted: hello from alice"]},
    {"bob": "until 10000 board: alice: hello from alice"},
    {"bob": ["tap-text Post ping", "capture count posted: ping 1 \\((\\d+) on the board\\)",
             "to alice open apksim://board?user=alice&post=bob%20counted%20{count}"]},
    {"all": "shot board-{role}.png"}
  ]
}
```

- **Roles** start together, headless, each `apksim --headless --control auto --data-slots scene --fresh --device D`
  plus the scene's `options`, the role's `options` and anything after `--` on the command line. `--data-slots scene`
  keeps them off an ordinary launch's data (`scene-0`, `scene-1`, ...: two roles of one APK get one slot each) and
  `--fresh` starts each from nothing. Launches are cold (`APKSIM_STANDBY=0`) unless `--warm`. APK paths are relative to
  the scene file; `--apk APK` (every role) or `--apk ROLE=APK` replaces them, as CI does with the APK it built.
- **The fixture** (optional) starts first; the port it prints (the `ready` pattern's first group) is `{fixture}`; it
  must print that within `timeout` seconds (default 30).
  Pointing a compiled-in backend address at it in every role (`--network-host`, docs/network.md) makes the instances
  two users of one server. `--network-trust` lets the app's TLS accept the fixture's throwaway certificate; apksim
  applies it on the recording path, so a scene records each role's traffic (`{out}/{role}.har`), which is also the
  first thing to read when a step fails.
- **Steps** run in order. A step maps roles to a command or a list; the roles in one step run at the same time and
  the next step starts when all are done. `all` sends to every role, `name` labels it.
- **Commands** are the control commands (`tap`, `tap-text`, `swipe`, `type`, `open URL`, `push k=v`, `settle`,
  `shot`, `ble ...`, `network ...`, ...; an answer that says it failed fails the step: `error ...`, `unknown command`,
  `usage:`, tap-text's `not found:`, open's `no activity ...`, key's `unknown key:`, push's `bad JSON:` and `the app
  has no FCM receiver`, and the answers of `permission`, `biometrics`, `notifications open`, `pick` and `jobs run`
  when there is no prompt, notification, picker or job to act on), plus:

| Command | |
|---|---|
| `wait MS` | sleep |
| `until MS TEXT` | repeat `nodes` until TEXT is on screen in an answer received within MS (`nodes`: the windows accessibility reports, so not text a dialog covers, as `tap-text` sees them; a node `nodes` marks `offscreen`, scrolled away, does not count; waiting for the role's connection or for an answer never runs past MS) |
| `gone MS TEXT` | until TEXT is not on screen |
| `expect TEXT` | the last answer contains TEXT |
| `capture NAME REGEX` | REGEX's first group, in the last answer or else in `nodes`, becomes `{NAME}`; a bad REGEX fails the step |
| `to ROLE CMD` | sends CMD to another role's instance from this step: `to watch open apksim://x?code={code}`, `to bob push otp={otp}`; `to bob shot FILE` is saved in the output folder like bob's own `shot` |
| `quit` | ends this role; its process must exit 0 |

- **Values**: `{NAME}` in any command or option: captured values, `--var NAME=VALUE`, `{fixture}`, `{out}` (the run's
  folder, every role's working directory, so `shot x.png` lands there), `{scripts}`, `{scene}`, `{role}`,
  `{port:ROLE}` (a role's control port, for a step that runs another tool against it).
- **Isolation**: every role runs with `--no-autofill`, so it never signs in with the owner's saved sign-in details,
  whatever its APK.
- **Captures**: `shot [--display] FILE`/`wshot FILE` (FILE may contain spaces) are written to a temporary folder outside
  `$HOME` under a space-free name first and moved into the output folder, so an `--out` the Mac sandbox would not let an instance write (anywhere under `$HOME` but its state folder)
  still gets them; a capture that fails fails its step, and a failure screenshot that cannot be saved is printed and
  listed in `result.json` (`capture_failures`).
- **Output**: a line per step with its time; `result.json` (roles, ready times, ports, steps, failure, peak memory);
  each role's log `ROLE.log`; on a failure every running role's screen as `failed-ROLE.png`. Exit 1 on a failure, 2
  for a bad scene file; a fixture or launcher that cannot be started is a failure like any other, with its `result.json`. Roles still running at the end are quit and must exit 0 (`--keep` leaves them and the fixture running, its PID
  printed). The fixture's output goes to `fixture.log`, watched for the ready line.

`scene.py` imports `fleet.py`'s instance handling (`Instance`: the port from the log, `ping`, `quit` and the exit
status), which is why `fleet.py` now runs `main()` only as a script.

## Scenes in the test app

`testapks/composeapp/scenes/`:

- `board.json`: alice and bob on `apksim://board` (`BoardActivity`: a message board on `https://api.apksim.test`,
  `scripts/network-fixture.py`'s new in-memory `/v1/board`, read every 500 ms). Alice posts, bob sees it; bob taps
  "Post ping", alice sees it and `settle`s; a count captured from bob's screen is sent to alice's instance with `to`,
  and both see the last word.
- `phone-watch.json`: a phone (`--ble heart-rate`, docs/ble.md) and a watch (`--device watch`): the phone reads the
  strap's heart rate on `apksim://ble`, captures it, posts it to the board and opens the board on the watch from its
  own step; the watch shows "phone: bpm 72" and answers; the phone sees the answer.

`scripts/scene-check.sh IMAGE APK OUT` (CI: `ci-smoke.sh`) runs both in the Linux image (`scene.py` inside the
container with the repo's scripts mounted: the fixture and every instance share its loopback; the certificate is
made on the host, as the image has no openssl), then a variant of the board scene whose fifth step waits for a post
nobody made: it must exit 1 at that step with `failed-bob.png` saved.

### Measured (2026-11-10, eight other sessions building and testing on the same Mac)

| | board | phone-watch |
|---|---|---|
| Mac, two cold launches at once, ready | 31-40 s | 12 s |
| Mac, steps after launch | 13 s (6 s of it the first HTTPS call through the recording engine) | 4.4 s |
| Mac, whole scene | 54 s | 17 s |
| Mac, peak memory of both instances | 570-620 MB | 750 MB |
| Linux image (amd64 under Rosetta), whole scene | 22-33 s | |
| Linux image, peak memory | 1.35 GB | |

A post on one instance shows on the other within one poll of the board (0.1-0.3 s for step 5 against a 500 ms poll).

## Phone and watch, and the Wear data layer

A phone and a watch already run as one scene: each is a role (`--device watch`: round, Wear OS's UI mode and
features), driven in order, sharing a backend, and a step on one can act on the other (`to watch open ...`,
`to watch push ...`). What does not exist is the **Wear OS data layer** between them: Google Play services' Wearable
API (`MessageClient.sendMessage`, `DataClient.putDataItem`, `CapabilityClient`, `WearableListenerService`). apksim has
no Play services, so those calls fail as on a device without them, and an app that pairs its watch half through them
sees no connected node.

Scoped, not built: the simplest useful path is messages only.

1. Node and capability discovery: each role of a scene is a node (`getConnectedNodes` lists the other roles; the
   capabilities from each APK's `wear.xml` `android_wear_capabilities`).
2. `MessageClient.sendMessage(node, path, bytes)`: a broker in `scene.py` (a loopback socket every instance is given
   with a launch option) relays it to the other instance, which delivers `onMessageReceived` to its
   `WearableListenerService` (the manifest's `com.google.android.gms.wearable.MESSAGE_RECEIVED` filters, with path
   matching) and to listeners added with `addListener`, and completes the sender's `Task` with a request id.
3. The hook: the Wearable API's entry points (`Wearable.getMessageClient` and friends) are classes in the APK,
   renamed by R8 in a release build, and talk to Play services through an obfuscated binder interface that changes
   between versions. The stable seam is the binder the app binds (`com.google.android.gms.wearable.BIND` in the
   `com.google.android.gms` package): a stand-in service there, as `BoundServices` binds the app's own, answering the
   few transactions messages need. That interface is not public, so each transaction code would have to be found in
   play-services-wearable's classes and kept working across its versions.

`DataClient` (synced data items with assets, URIs and change events in both directions) is several times that. With
no test app for Wear and the binder unpublished, this was judged not cheap and left out; a scene carries a value
between phone and watch with `capture` and `to ROLE open|push` meanwhile.

## Not done

- Roles run on this machine (or all in one container, as `scene-check.sh` does); not spread over several machines or
  containers with a network between them.
- Network conditions per role are the `network` command's (a step may send `network offline` to one role); the
  fixture is shared, not partitioned.
- Steps cannot branch or loop; a role cannot be restarted mid-scene (`restart` and `process-death` are commands and
  work).
- `serve`'s browser page shows one instance; a scene is not viewable as a group there.
