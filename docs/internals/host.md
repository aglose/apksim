# Host: the main loop, activity stack, windows, crashes and exits

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Code notes
- `Host`: the Android main thread. Activity stack, input dispatch, the frame loop (on the display's vsync, else a 120 Hz grid), and the `--control`
  commands (`runCommand`). A frame is drawn when a shown window is dirty or the shown windows changed (`needsFrame`);
  hidden windows (an activity below the top one) never count, as nothing draws them clean: one left dirty made every
  loop turn draw, 150-400 frames a second on a video screen. It is drawn only on a turn that ran a vsync, or when
  none is pending (a TextureView's new picture): input dispatched at a turn's start dirtied the window, which was
  drawn then and again at the vsync (`frames` counts the frames `held for their vsync`). A vsync asked for since the
  last frame waits for the display's next one, and while a timed drag (`swipe ... MS`) runs the loop turns at each
  vsync and sends the move there, as a device consumes batched touch input in the vsync's frame; other input goes
  out 7 ms apart, and scripted input that queued up meanwhile is batched as a device's input dispatcher does
  (`mergeQueued`, issue 450): consecutive queued `touch move`s go out as the newest, consecutive `wheel`s at one point
  as their sum, nothing else is merged or reordered (`stats` inputMerged counts them; `scripts/input-merge-check.py`).
  A burst sent with no time between its moves is therefore a down, one move and an up: pace moves for a real drag. When nothing is wanted (no frame, no input for 100 ms, no command or `settle`), it sleeps in
  Robolectric's `ShadowPausedLooper.poll` until the next main-queue message is due, at most 40 ms: a post to the main
  queue ends it, as do `Control.submit` and window input (`apksim-input-wait` thread); `-Dapksim.idleSleep=false`
  keeps the old 2 ms poll. `Host.Memory`: once the screen has been still for 2 s and the footprint has grown by
  `-Dapksim.idleGcMB` (256) since the last check, a full collection runs then (HotSpot does not count native memory);
  under the Mac's memory pressure GpuBitmaps' caches go and a collection runs at once, and a standby quits
  (`Footprint.pressure`). Once the screen has been still for 3 s (`-Dapksim.idleTrimMs`, 0 for never) the heap's
  unused pages go back to the system (`HeapTrim`, below), again whenever the footprint has grown 16 MB past what
  the last trim left, at most every 10 s; the next frame or input ends it. Before (2026-11-07), slow drags drew ~170 frames a second at 120 Hz, a third never shown
  (`-Dapksim.offVsyncFrames=true` restores it). Without a window, `vsync PERIOD_US SHIFT_US` (sent by serve's page
  twice a second) makes a viewer's display the vsync to follow (`SimWindow.remoteVsync`) until 1.5 s pass without
  one. The first frame waits for the bottom window that is
  shown and sized to be laid out (`laidOut`), not for the bottom one: an app's overlay added before its activity and
  never sized (0x0) kept the screen black for good (issue 83). A touch goes to the top window it lands in, or on past one that is not touch-modal, as
  InputDispatcher decides: a window is touch-modal only when it is focusable and lacks FLAG_NOT_TOUCH_MODAL, so a
  non-focusable popup (a slider's value tooltip) takes only touches inside it (`isTouchThrough`). On a DOWN, each visible, touchable window above the target that has FLAG_WATCH_OUTSIDE_TOUCH and does not contain the point gets an ACTION_OUTSIDE in its own coordinates (`sendOutsideTouches`, as InputDispatcher does), so Compose popups dismiss on an outside tap. A touch event's
  raw coordinates are the screen's and x/y the window's, as a device delivers them (Compose reads the raw ones).
  Host places windows itself (`position`, as AOSP's `WindowLayout.computeFrames`: gravity, offsets and margins with
  `Gravity.apply` in the parent frame, then `Gravity.applyDisplay` on the display frame; the display frame is the
  display less the insets of the bars the window fits and that show (`getFitInsetsTypes`/`Sides`, bar sizes from
  Robolectric's `SystemUi` through `SystemBars.fitInsets`: a dialog stays off the bars, an edge-to-edge activity
  window does not), the parent frame that, or for a sub-window (a PopupWindow) without FLAG_LAYOUT_IN_SCREEN the
  window it is attached to; FLAG_LAYOUT_NO_LIMITS (a popup with clipping off) is not kept on the display; the
  attributes are ViewRootImpl's `mWindowAttributes`, adjusted for compatibility (FLAG_LAYOUT_IN_SCREEN and the
  translucent and legacy layout flags drop bars from the fitted types), as Robolectric sizes the frame from them;
  issue 506: a BOTTOM-gravity dialog sat over the navigation bar; not done: the cutout's safe area, an IME-inset
  parent, multi-window. Test app: `apksim://floating?popup=1|offscreen` (a PopupWindow on a window at 91,733:
  139,833, or -9,833 with clipping off) and `apksim://dialogs?open=inscreen` (0,0 822x1734). An axis with no gravity (NO_GRAVITY, a window's default) is centred; Robolectric's window session
  gives a window that is not full-screen its measured size placed inside the bars' insets, not where the window
  manager would; before issue 506 the display's size at lp.x/lp.y), and tells their views: `ShadowSimViewRootImpl`
  moves the frame a relayout gives to that place, and `syncWindowPositions` each loop turn, so AttachInfo's window
  position (getLocationOnScreen, boundsInScreen, Compose's window position) is where the window is drawn. Before, a
  centred dialog's views thought they were at the top left, and `nodes`, `tap-text` and UiAutomator missed them.
  A start that a running activity takes itself is delivered to its onNewIntent, not stacked (`Host.deliverToRunning`,
  issue 336: singleTask/singleInstance, singleTop on top, CLEAR_TOP with SINGLE_TOP or a non-standard mode; those
  above it finish; not NEW_TASK | CLEAR_TASK, CLEAR_TOP alone on a standard activity or a start for a result, which
  stack as before; `ci-smoke.sh` checks it on `apksim://signin`; an activity that finishes in onCreate, a redirect trampoline, is not arranged in `Host.start`, so the one below stays paused until the pending start's onNewIntent, issue 435: `apksim://signin?strict=1` cancels on a resume with no callback).
  The stack is kept as a device's activity manager keeps it (`Host.arrange`, where every
  path that changes it ends: start, finish (of the top or of one below it), `home`/`resume`, configuration changes,
  `recreate`, `process-death`, test runs' launches): the top resumed; below it, while everything above is translucent or floating (a
  dialog-themed activity: the test app's `FloatingActivity`, `apksim://floating`), paused and visible; the rest
  stopped with their windows hidden, so only what a device shows draws and takes touches. A visible activity with
  no window (recreated or rebuilt while stopped: Robolectric's recreate leaves it detached, and resumed whatever it
  was) gets one, windows are put in the stack's order, and in test runs the top has the focus. An activity's dialogs
  and popups hide with it (a destroyed activity's leaked ones are removed: `Host.destroyActivity`, as
  `WindowManagerGlobal.closeAll`, issue 614; not for configuration-change recreates), and a hidden window is
  FLAG_NOT_TOUCHABLE meanwhile (Robolectric's UiAutomation injection reads only that, and ranks dialogs above every
  activity window). Before, a rotation left the whole stack resumed,
  and finishing the top of a recreated stack left nothing on screen. In test runs a finish only brings activities
  up (a test may have launched several side by side), and UiDevice rotation restores each activity's own state
  (Robolectric's instrumentation recreates ActivityScenario's activities itself on `setQualifiers`).
- `TestActivities.ShadowSimActivity.recreate` (running instances only; test runs keep Robolectric's):
  `Activity.recreate()` relaunches the activity as the `recreate` command does, through a configuration change on
  that one activity (`Host.recreate`, `DeviceControls.recreate`), then `Host.arrange`. Robolectric's own
  `ActivityController.recreate()` leaves the new window detached and then waits for its focus, so even one call
  threw a NullPointerException in `windowFocusChanged` and ended the app (issue 410: an in-app theme switch).
  Calls while one is pending for that activity are one relaunch, as on a device, and relaunches run one at a time
  (`runRecreate`: one asked for while another runs, the new activity's own from onCreate included, waits in a queue
  and is posted once that one is done; an activity destroyed since it asked is skipped). The test app's
  `apksim://recreate` (`RecreateActivity`: "Recreate twice", "Recreate from onCreate", "recreate: instances N, live
  1") is checked in `ci-smoke.sh`.
- `TimeTick`: ACTION_TIME_TICK (registered receivers only) from Host's loop at each minute of `SimClock`'s clock; not under `--snapshot` (issue 109).
- `Crashes`: uncaught exceptions in running instances, as RuntimeInit's KillApplicationHandler (issue 104). The JVM
  had no default handler, so ThreadGroup printed "Exception in thread" and the app ran on: kotlinx.coroutines hands a
  coroutine's exception to the thread's handler itself (traced: `propagateExceptionFinalResort`, ThreadGroup, no
  default; AndroidExceptionPreHandler does nothing past API 27), and a crash reporter passing it on to the handler it
  found (none) left nothing at all. Main sets `KillApplication` as the default before any app code: `E
  AndroidRuntime: FATAL EXCEPTION: <thread>` (the main looper's is `main`), `Process: PKG, PID: N` and the stack through
  android.util.Log (`adb logcat`; stderr too when logcat is off), then exit 10 (RuntimeInit's System.exit(10); warm,
  the client's). An app's own default handler replaces it, as on a device; one that passes the exception on reaches
  it. An exception leaving the main looper (Host's loop, the first activity's start, the Application's construction,
  attachBaseContext and onCreate (SimRunner, TestActivities), and app code a command's lifecycle transition runs:
  `Crashes.app` around restart, recreate, process-death, configuration changes, home/resume, memory, `Host.arrange`;
  the command, menu item or adb shell call is answered `error: app crashed: ...` first; a command's bad arguments stay
  `error:`/usage answers) is logged
  first, as the device's pre-handler does, then goes to the thread's handler, and the process ends even if that
  returns (the main thread is dead on a device). Not a device's: an app thread whose exception reaches the app's own
  non-passing handler is not logged (no pre-handler for JVM-dispatched exceptions). apksim's own threads (every thread
  apksim starts is named `apksim-...`: keep it so for new ones) are printed and end alone, as before; every other
  thread is the app's (not judged by the exception's stack, which may be a JDK one or have none). The adb log's tee is
  put back in front of ShadowLog.stream before logging, and stderr gets the crash whenever Log.e failed. `--jvm -Dapksim.crashes=log` logs and runs on (the
  main loop too), to look at a broken screen. Logging and each cleanup step are best-effort (an OutOfMemoryError may
  be the crash) and `Standby.haltNow` always halts with 10, the client told first; `-Dapksim.crashTest.failCleanup=true`
  makes the cache save throw, to check it. Test runs install nothing: AndroidJUnitRunner's handler
  (MonitoringInstrumentation's, set in its onCreate) logs and the test goes on (`UncaughtExceptionTest`).
- Fatal signals (a JVM crash in native code, issue 523): the launcher gives every launch that is not serve's or beam's
  `-XX:ErrorFile=STATE/tmp/PID/hs_err_pid%p.log` (its scratch: writable under the sandbox, kept until the next launch's
  sweep, which also covers a warm launch's standby, whose PID is the instance's) and `-XX:OnError` running `apksim
  --crash-summary FILE 134` (awk only, inside the JVM's crash handler, after the report is written): stderr gets
  `[apksim] exit 134: native crash: SIGSEGV in LIBRARY (FUNCTION)`, the first 15 lines of the report's `Native frames:`
  and `Java frames:`, and the report's path. 134 is the abort() the JVM ends with (a launch with core dumps disabled
  would end 1; serve's, which sets `APKSIM_NO_CORE`, reads the report itself in `Session.explainExit`, as beam does
  from its scratch, and words its end message and log the same way: `Crash.hsErrReason`). A warm launch's client
  exits 134 as well. A signal HotSpot does not handle (SIGKILL) leaves no report, and a native abort
  has `bionic.c`'s line instead (which stays silent after a summary: the summary leaves `crash-reported` in the scratch
  folder, `APKSIM_SCRATCH`, and the abort handler looks for it, since HotSpot ends a fatal error with abort()). The hook
  is set only when ROOT and STATE are made of `A-Za-z0-9_./+@:,=-` (OnError goes through `sh -c`; HotSpot expands `%`).
  An address in a library the ELF loader mapped is anonymous memory to the JVM (a bare `C  0x0000007000004608`): the
  loader appends `BASE SIZE lib/ABI/NAME.so` to `APKSIM_SCRATCH/elf-modules` as each library loads, and the summary
  names the address `lib/arm64-v8a/NAME.so+0xOFFSET` (checked by a library whose JNI function writes through NULL).
  Serve's and beam's reports are read by Java without that list: an app library's frame there is still an address. No flight-recorder bundle is written for a JVM crash (nothing is left to write
  it: only Java crashes, `Crashes`, save one). Checked by `kill -SEGV` of an instance (cold, warm, serve).
- `Exits`: why a running instance ends, one `[apksim] exit N: REASON` line on stderr for every way (issue 116: a
  process ended 0 with nothing in the log). The last activity finishing names it and the call, with the stack of
  the code that made it (`finish`, `finishAffinity`, `finishAndRemoveTask` shadows in `TestActivities.ShadowSimActivity`,
  back included); the window closing, `quit`, `--exit-after-first-frame`, the launcher's client going away (130) and
  crashes (10) say so. Host's loop ends only once no activity has been left for `Exits.LINGER_MS` (1 s) of real
  time, the app running meanwhile: a device's process lingers, and a hand-off that finished first and started the
  next activity from a posted message or coroutine exited 0 before it landed. The app's own `System.exit`,
  `Runtime.exit` and `Runtime.halt` go here through the translator: logged with the code and caller, and that code
  reaches a warm launch's client (the standby's shutdown hook sent 0 for every exit, and a halt sent nothing: 134).
  `Process.killProcess(myPid())` and `sendSignal(myPid, SIGNAL_KILL)` end a running instance with 137, as a device
  kills the process (Robolectric only recorded the pid and the app ran on; test runs keep that). The test app's
  `apksim://exit?kind=finish|affinity|handoff|exit|halt|kill|restart|restart-kill` does each (`ci-smoke.sh`). An exit or kill within 1 s of a start of one of the app's activities that has not landed yet (no resume since) is the app restarting its process (`Restarts`, issue 658: ProcessPhoenix and the like): `[apksim] restart: the app started .Act (NEW_TASK|CLEAR_TASK) N ms before CALL` comes before the exit line. Not relaunched yet; running instances only. `-Dapksim.traceExit=true`
  still prints every thread's stack at a JVM shutdown.
- Screenshot tests (docs/snapshots.md): `Snapshot` (`--snapshot`: CPU-only drawing, carets drawn and not
  blinking, the top window focused), `SimClock` (`clock`, `--clock`), `ImageDiff` (`diff`), and in `Host` the
  `settle` idle signal and `restart`.
- `ShadowFastRipple`: forces the SOLID ripple with software animation. The patterned ripple's per-pixel shader
  cost ~15 ms a frame, and its render-thread animation never finished, because there is no native looper.
- `ShadowUiThreadVectorAnimations` (running instances only): an AnimatedVectorDrawable (a framework indeterminate
  ProgressBar's spinner, checkbox and switch transitions) animates on AOSP's UI-thread animator, as the framework
  does for a software canvas. Its render-thread animator advanced only in frames the main thread drew anyway (HWUI
  has no render-thread vsync here), so a spinner turned for ~250 ms after its screen opened and then stood still,
  and `settle` answered "settled" (found by `idle-zoo-check.py`'s `progress`). The shadow is on
  `VectorDrawableAnimatorRT`'s start/reverse, which switch the drawable over (`forceAnimationOnUI`) before it has
  started: a shadow on AnimatedVectorDrawable itself made its natives, bound straight to libandroid_runtime, answer
  0. A ProgressBar stops its animation once not visible, so a stopped activity's spinner draws nothing. `settle`
  names what wants a frame when it can (`Host.frameWantedBy`: "a spinning ProgressBar #id at X,Y", else the
  running animators). Test runs keep Robolectric's behaviour; `-Dapksim.uiVectorAnimations=false` turns it off (launches cold).
  `ci-smoke.sh` checks the spinner keeps `settle` from answering.
- `ShadowRepeatingAnimator`: a ValueAnimator's `INFINITE` repeat count is kept in running instances and with
  `--realtime-clock`, where frames come at vsyncs. Robolectric's shadow makes it 1, so an endless View animation
  stopped after two cycles. Default test runs keep that cap: their unpaused Choreographer would never let the looper
  go idle. `-Dapksim.repeatForever=false` keeps the cap everywhere (docs/conformance.md, cause 4).

## Rules
- Robolectric's looper clock has **1 ms resolution and drops fractions**. Always advance by whole ms (see
  `Host.run`).
- **Nothing but Host's loop may move the clock in a running instance.** Robolectric's SystemClock.sleep moves it by
  the time asked and returns at once, from any thread; Host never gives that time back, so app timers fire early by
  as much, and a worker polling with SystemClock.sleep raced the clock minutes ahead (an app's 10-minute inactivity
  CountDownTimer fired after ~30 s, most likely this). `ShadowRealSleep` makes it a real sleep outside test runs; on the main thread it also moves the clock by the time
  slept (Host's loop cannot meanwhile, and a deadline loop there would never end), which Host then does not add again. `stats` clockAheadMs
  shows drift; `scripts/timer-check.py` checks it.
- Vsync uses `ShadowChoreographer.setPaused(true)` + frame delay 1 ms. The loop only advances the clock to 120 Hz
  grid lines while a frame is wanted (`lineAtOrBefore`). Unpaused Robolectric vsync spins forever on infinite
  animations. Line k is at k * 1000 / hz ms rounded down, and `ms * hz / 1000` rounded down is not its inverse (8 ms
  gives line 0): until 2026-11-05 the loop took the line it stood on for the one before, waited for a line already
  passed, and spun the main thread for the rest of every frame (a core, headless, whenever anything animated).
  `lineAtOrBefore(ms, hz)`/`lineAfter` are exact for every rate (checked for 1-120 Hz).
- Phone, foldable and tablet report a 24 dp (48 px) status bar and gesture-nav inset (Robolectric's `SystemUi`, set
  in `SystemBars.apply`; desktop and TV have none; `--status-bar`/`--nav-bar` and `bars` override it per bar, and a bar
  the app hides reports 0), and `useDeviceGestureThresholds` replaces Robolectric's 16 dp touch slop with the framework's 8 dp.
  Without them layouts sat 26 px higher and every drag scrolled 16 px short of the emulator.
