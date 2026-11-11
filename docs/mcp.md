# apksim mcp: driving instances from an AI agent

`apksim mcp` is a [Model Context Protocol](https://modelcontextprotocol.io) server over stdio. An agent launches an
APK, taps, types and swipes, and reads the screen as a short list of nodes; every action answers with what changed
since the last answer, not the whole tree. It is `scripts/mcp.py` (Python standard library only, shipped with
releases), started by the launcher's `mcp` subcommand. It drives each instance through its `--control` channel, the
way `scripts/drive.py` does. A JVM implementation beside `serve/` would add nothing: the server speaks JSON over a pipe
and a local socket, needs no apksim classes, and a script ships and changes without a rebuild.

## Starting it

```
apksim mcp --apps DIR [--apps DIR]... [--out DIR] [--max 2] [--window]
```

- `--apps`: folders APKs may be launched from (required; `launch` refuses any other path).
- `--out`: the only folder the server writes for the agent (recordings, screenshots kept with `save`); default
  `<state>/mcp-out`. The app's sandbox may write only under the state folder, so for an `--out` elsewhere the server
  stages files in `<state>/mcp-tmp` and copies them over.
- `--max`: instances at once (2). `--window`: lets `launch` open a Mac window when asked (headless otherwise).

Client configuration (Claude Code `.mcp.json`; Claude Desktop and others take the same `command`/`args`):

```json
{"mcpServers": {"apksim": {"command": "apksim", "args": ["mcp", "--apps", "/path/to/apks", "--out", "/path/to/out"]}}}
```

## Protocol

Revision **2026-07-28** (read from the published specification): stateless, every request carries
`_meta."io.modelcontextprotocol/protocolVersion"`, `server/discover`, results carry `resultType`, `tools/list` carries
`ttlMs` and `cacheScope`; a request for another version gets `UnsupportedProtocolVersionError` (-32022) naming the
supported ones. The handshake revisions 2025-11-25 and 2025-06-18 work too (`initialize`, `ping`), told apart by the
first request. Only tools are offered.

## Tools

| Tool | What it does |
|---|---|
| `launch` | Start an APK (`apk`, `options`, `window`, `fresh` (default), `record`). Answers with the first screen. |
| `instances`, `quit` | List, stop. `instance` is optional while only one runs. |
| `tap` | By `ref` (`e3`), by `text` (exact, else substring; several matches answer with their refs), or `x`,`y`. |
| `type`, `key`, `swipe`, `back`, `open_url` | `type` may tap a `ref`/`tap_text` first. `swipe` by `direction` or coordinates. |
| `device` | `appearance`, `locale`, `fontscale`, `rotate`, `network`, `permission`, plus `timezone`, `animscale`, `bluetooth`, `device`, `lock`, `unlock`, `home`, `resume`, `recreate`. Values are matched against a pattern per setting. |
| `nodes` | The screen, filtered by `text`; `bounds` adds coordinates. |
| `screenshot` | An image, long side 640 px unless `max_size` says otherwise (0: full size). Only when called. |
| `logcat`, `activity` | Tail of the app's log (`filter`, `lines`), top activity. |
| `record` | `start`/`stop`/`status` of the flight recorder; `save` writes a `.apkrec` into `--out` (`docs/flight-recorder.md`; the replay lane plays it). |

### What an answer looks like

```
tap 734,1486 ok | MainActivity
~ e1 "FAB clicks: 0" -> "FAB clicks: 1"
```

After each action the server waits for `settle`, reads `nodes`, and lists `+` added, `-` removed and `~` changed
nodes against the last answer. A reference (`e1`) is never reused and stays with a node while it is on screen (the same
text, or the same place with other text). A text-less clickable box holding one text node is folded into it, so a
nav item or list row is one line; more than 30 changes end with a pointer to `nodes`. A node line is
`ref "label" click|field`.

## Safety

- The sandbox stays on: instances are ordinary launches. `launch` options come from an allow-list (the options of
  `serve`'s catalogue, `--appearance`, `--locale`, `--font-scale`, `--permissions`, `--soft-keyboard`); `--jvm`,
  `--adb`, `--control-bind`, file options and the rest are refused. No raw control commands pass through (`shot FILE`,
  `record`, `rec save FILE` write paths); every tool builds its own command from validated values.
- Files: the APK is read from `--apps`, the server writes under `--out` only, with names checked against
  `[A-Za-z0-9._-]`.
- No credentials: instances start with `--no-autofill`, so `Autofill` never runs or reads its file, and the server
  has no tool for passwords. Sign-ins are the owner's. The instructions sent to the agent say so.
- Typed text is not logged: the server prints no tool arguments, and `type` answers with a character count. (The app
  may show the text in a field, which the next answer then reports.)
- Instances use their own data slot (`--data-slots mcp`) and `--fresh`, never the owner's `default`.

## Check

`scripts/mcp-check.py` (Mac; `APKSIM_STANDBY=0` while others share the machine) speaks both protocol eras to the server
and runs the journey: tap the FAB and read the count from the change, rename a trip, dark appearance, screenshot, save a
recording and check it is a zip, plus the refusals. The server is not run in the Linux image from `ci-smoke.sh` (it
launches the Mac launcher; a Linux run would need the image to carry the script).

Measured on the test app (2026-11-10, Mac, headless):

| | bytes | ~tokens |
|---|---|---|
| `tools/list` (15 tools) | 6,048 | ~1,500 once per session |
| `launch` answer (Home) | 287 | ~70 |
| `tap` on the FAB | 70 | ~18 |
| rename a trip (tap a row) | 74 | ~19 |
| `nodes` of the Trips tab, with bounds | 395 | ~100 |
| `screenshot` at 640 px (JPEG via `sips`) | 23,196 base64 | an image |

A typical action answer is 18% of the Trips screen's full dump, and the gap grows with the screen: the dump scales with
what is on screen, the answer with what changed.
