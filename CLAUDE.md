@AGENTS.md

## Claude Code notes
- Use `mcp__terminal__read_terminal` (tab 0) to see an apksim instance the owner launched in their terminal: its
  logs show login failures and crashes.
- Prefer driving instances with `scripts/drive.py` over screenshots of the owner's screen (no screen-recording
  permission). `wshot` captures the real window from inside the process.
- Long runs (training, benches) can take 1-3 minutes, so give Bash calls a long timeout.
- Test runs (`--instrument`) can take minutes for big suites: run them in the background and wait for the
  notification. The owner's app's suites and their numbers are in docs/ci-testbed.md (names in the private notes).
- An emulator another session started may be running (`adb devices`): install only test packages there for a ground
  truth, uninstall them afterwards, and leave its apps alone.
- When a test passes on the emulator and fails here, find the mechanism with `scripts/jdi/Trace.java`/`Ret.java`
  before changing behaviour: each fix in docs/ci-testbed.md came from a trace, and two guesses (Robolectric's
  INSTRUMENTATION_TEST looper, a device fingerprint) broke hundreds of tests.
