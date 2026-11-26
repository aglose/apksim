# Agents, Bluetooth LE and scenes

```bash
apksim mcp --apps builds/                               # an MCP server over stdio; agents may launch APKs from builds/
apksim --ble heart-rate app.apk                         # a scripted peripheral the app can scan for and connect to
apksim --health week app.apk                            # Health Connect with a week of steps, heart rate and sleep
scripts/scene.py testapks/composeapp/scenes/board.json  # several instances, one script, one shared backend
```

- **MCP server:** `apksim mcp` lets an agent launch an APK, tap, type and swipe, and read the screen as a short list
    of nodes; every action is settled and answered with what changed since the last answer, not the whole tree. See
    [docs/mcp.md](mcp.md).
- **Bluetooth LE peripherals:** `--ble FILE|PRESET` (repeatable) and the `ble` command play the device's Bluetooth
    stack against scripted peripherals: advertisements the app's scanner finds, GATT services it discovers, reads,
    writes, notifications, bonding, MTU and PHY, in a running instance and in a test run. An emulator has no Bluetooth
    LE at all. See [docs/ble.md](ble.md).
- **Health Connect:** `--health FILE|PRESET` (repeatable) and the `health` command give the platform's Health Connect
    service records to answer with: AndroidX's `HealthConnectClient` finds it available, asks for its permissions as
    runtime permissions, and reads, writes, aggregates and follows changes of every record type, in a running instance
    (kept with the data slot) and in a test run. See [docs/health-connect.md](health-connect.md).
- **Scenes:** `scripts/scene.py` runs different roles, each its own instance with its own APK, device and options,
    through one ordered script whose steps wait on one another's state, against one shared server. See
    [docs/scenes.md](scenes.md).
