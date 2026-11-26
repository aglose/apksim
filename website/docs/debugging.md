# Debugging

- **Breakpoints in app code.** Launch with `--debug 5005`, then attach Android Studio or IntelliJ ("Remote JVM Debug", localhost:5005). Breakpoints in the app's Kotlin and Java sources stop, step and show locals. R8-minified apps have no locals, and their line numbers need retracing with the mapping file.
- **Logs.** Send `logcat on`, or set `APKSIM_LOGCAT=1` at launch, to copy `android.util.Log` output to stderr. With `--adb`, `adb logcat` reads it with filters.
- **Crashes.** An uncaught exception ends the app as on a device: `FATAL EXCEPTION` with the stack goes to the log (`adb logcat`, and stderr), and the exit status is 10. An app's own default exception handler replaces apksim's. `--jvm -Dapksim.crashes=log` logs it and keeps running.
- **Startup timing.** `--jvm -Dapksim.timing=verbose` prints each startup stage's time.
- **Frames.** `frames`, `fps` and `shaders` show where frame time goes, and which shaders run on the GPU.
- **Layout and accessibility.** `debug layout on` shows layout bounds for Views and Compose. `a11y show` marks unlabeled controls and touch targets under 48 dp.
