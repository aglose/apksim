---
title: MCP Server
---

<div class="page-hero" markdown>

# apksim mcp: drive Android apps from AI agents

<p class="sim-lead">Expose a Model Context Protocol (MCP) server over stdio so AI coding agents can launch APKs, interact with UI elements by ref or text, inspect compact screen diffs, take screenshots, and save flight recordings.</p>

<div class="sim-pills">
<span class="sim-pill">~18 tokens per action</span>
<span class="sim-pill">Ref-based UI diffs</span>
<span class="sim-pill">Sandboxed &amp; allow-listed</span>
<span class="sim-pill">Zero extra dependencies</span>
</div>

</div>

## Setup

Add `apksim mcp` to your agent's MCP configuration (e.g. Claude Code `.mcp.json` or Claude Desktop):

```json
{
  "mcpServers": {
    "apksim": {
      "command": "apksim",
      "args": ["mcp", "--apps", "/path/to/apks", "--out", "/path/to/out"]
    }
  }
}
```

### CLI flags

```bash
apksim mcp --apps DIR [--apps DIR]... [--out DIR] [--max 2] [--window]
```

| Flag | Default | What it does |
|---|---|---|
| `--apps DIR` | *(required)* | Folder(s) the agent is allowed to launch APKs from (repeatable) |
| `--out DIR` | `<state>/mcp-out` | Folder where saved screenshots and `.apkrec` recordings are written |
| `--max N` | `2` | Maximum concurrent running instances |
| `--window` | off (headless) | Allow the agent to open a visible Mac simulator window when requested |

## Tools

| Tool | What it does |
|---|---|
| `launch` | Launch an APK from `--apps` (`apk`, `options`, `window`, `fresh`, `record`) and return the first screen |
| `instances`, `quit` | List running instances or stop one (`instance` is optional when only one runs) |
| `tap` | Tap by element `ref` (`e3`), by `text` (exact or substring), or by `x, y` coordinates |
| `type`, `key`, `swipe`, `back`, `open_url` | Enter text (optionally tapping a target first), send keys, scroll by direction or coordinates, navigate back, or open deep links |
| `device` | Switch `appearance`, `locale`, `fontscale`, `rotate`, `network`, `permission`, `timezone`, `animscale`, `bluetooth`, `device`, `lock`/`unlock`, `home`/`resume`, or `recreate` |
| `nodes` | Read the current accessibility tree, optionally filtered by `text` or with `bounds` |
| `screenshot` | Capture a PNG (default 640 px long side; `0` for full resolution) on demand |
| `logcat`, `activity` | Read recent app logs (with optional `filter`/`lines`) or the foreground activity |
| `record` | Control the [flight recorder](flight-recorder.md) (`start`, `stop`, `status`, `save` to `--out`) |

### Compact diff responses

After every action, `apksim mcp` waits for `settle`, compares the accessibility tree, and returns **only what changed** (`+` added, `-` removed, `~` changed) rather than dumping the entire hierarchy:

```text
tap 734,1486 ok | MainActivity
~ e1 "FAB clicks: 0" -> "FAB clicks: 1"
```

Element refs (`e1`, `e2`, ...) stay stable while an element remains on screen, and clickable containers holding a single label are collapsed into one concise line — cutting token usage by 80%+ compared to full UI dumps.

## Safety &amp; sandboxing

<div class="beam-security" markdown>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Strict isolation</div>

- **Allow-listed options only:** `--no-sandbox`, `--jvm`, `--adb`, `--control-bind`, and arbitrary host file options are refused.
- **Scoped paths:** APKs load only from `--apps`, and saved artifacts write only into `--out` with sanitized filenames.
- **Isolated data slots:** instances run with `--data-slots mcp --fresh` so your local `default` app data is never modified.
</div>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Credential protection</div>

- **No autofill or passwords:** launches force `--no-autofill` and offer no password-retrieval tools.
- **Private typing:** `type` never echoes typed arguments into server logs and replies only with a character count.
- **Bounded calls:** tool calls enforce `--call-timeout` (60 s) and `--launch-timeout` (150 s) so hung apps never stall an agent.
</div>
</div>
