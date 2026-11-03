@AGENTS.md

## Claude Code notes
- Use `mcp__terminal__read_terminal` (tab 0) to see an apksim instance the owner launched in their terminal: its
  logs show login failures and crashes.
- Prefer driving instances with `scripts/drive.py` over screenshots of the owner's screen (no screen-recording
  permission). `wshot` captures the real window from inside the process.
- Long runs (training, benches) can take 1-3 minutes, so give Bash calls a long timeout.
