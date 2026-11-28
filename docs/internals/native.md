# Native code: the ELF loader, the bionic shim, the window

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Code notes
- `ElfLoader`, `Natives`: load the APK's arm64 `.so` files through `native/`.
- `simwindow.m`: Cocoa window and IOSurface present, input queue, Cmd+V paste, `wshot` self-capture, thread QoS.
- `elfloader.c`, `bionic.c`, `jnibridge.c`: the Android ELF loader, bionic→libSystem shim, and a JNI table wrapper
  for the AAPCS-vs-Apple varargs difference (chosen by caller address).
- `bionic.c` also has `timerfd_create`/`timerfd_settime` (Mac only, Linux has glibc's: a pipe fed by a thread per
  timer, freed when ELF code calls `close` on it (`net_close`; not `dup2` over it); expirations are added up and written as one 8-byte count while the pipe
  is empty, and `timerfd_settime` clears a count not yet read; no `timerfd_gettime`, no epoll) and a SIGABRT handler
  that logs `[apksim] exit 134: native abort: MESSAGE` (the message from `android_set_abort_message`, a C++ uncaught
  exception's text): before, a native abort ended the process with nothing after the library's own line (issue 477).
  Mac only (Linux has no such line yet). It replaces the JVM's own SIGABRT disposition, so a JVM-internal abort prints it too.
- `elfloader.c` logs, once per library as it loads, the imports nothing implements (neither another loaded library,
  `bionic_lookup` nor the host): `[apksim] lib/arm64-v8a/NAME.so: N imports have no implementation: a, b, c` (names
  counted once each, the list cut at ~400 characters with `(list cut)`, weak imports left out); each gets a stub that logs `native code called unsupported function:
  NAME` when called. Before (issue 523) the line named the symbol only, without its library, and every relocation of it.
  The test app's fixtures print none.
- `alooper.c` (Mac and Linux): the NDK's `ALooper_*` (prepare, forThread, acquire, release, addFd, removeFd,
  pollOnce, pollAll, wake), which native SDKs run their event loops on (issue 477: Mapbox's common library ended the
  app at `ALooper_prepare`). Watched fds are polled with poll(2), level-triggered as epoll is, with Android's
  `Looper::pollOnce` results: idents for fds without a callback (`ALOOPER_PREPARE_ALLOW_NON_CALLBACKS`), callbacks
  unregistered when they return 0, `ALOOPER_POLL_WAKE` for `ALooper_wake`, and an fd added from another thread
  watched at once. A thread with a Java Looper (the main thread, a HandlerThread) gets one backed by it, found by
  `forThread` without `prepare`, as on a device: an `apksim-alooper` thread polls its fds and posts an asynchronous
  message to that Looper (`NativeLoopers`) that runs the ready callbacks there, then waits for it before polling
  again (a message a test reset dropped is posted again after a second; a looper that quit stops its watcher). An fd
  closed without `removeFd` is forgotten, as epoll forgets it. `ALooper_pollOnce` on a Java-backed looper's own
  thread also works, beside the watcher (unusual on a device). Not done: `ALooper_addFd` of an epoll fd,
  `AChoreographer`, `AInputQueue`. `NativeLoaderTest`'s looper tests check a native thread (timerfd, idents, wake),
  the main thread and a HandlerThread.
- `jnigraphics.c` (Mac and Linux): libjnigraphics' `AndroidBitmap_*` for ELF code, through `NativeBitmaps` (see "Video").

## Rules
- macOS (16 KB pages) never makes a page writable and executable at once: `mprotect`/`mmap` refuse RWX, and MAP_JIT
  memory is writable or executable per thread, never both (checked: a thread executing a MAP_JIT page it may write
  faults). A 4 KB-aligned `.so` (an NDK before r27) has 16 KB pages holding the end of its code and what lld put
  after it, RELRO first: `elfloader.c` makes such pages read+execute once relocated, which a device's linker does to
  RELRO anyway. Writable `.data`/`.bss` on a page with code (`code_page_conflict`: a RELRO under ~12 KB, as in
  libevent's in a Flipper debug build, or `-z norelro`) is refused by default before anything is mapped: a catchable
  UnsatisfiedLinkError naming the APK entry, the rebuild (16 KB pages) and the switch. `--move-shared-data`
  (`-Dapksim.moveSharedData`, warm launches too; ElfLoader hands it to the native side before each load) moves it
  instead (`plan_data_move`): from its 4 KB page to the end of the image, the file's bytes are copied to pages after
  the image, a multiple of 16 KB on, so low 12 bits stay; the code's adrp (and adr/ldr-literal, if still within 1 MB)
  of moved pages is repointed (`patch_moved_refs`, executable sections only); relocations, symbol lookups and the
  dynamic section go through `at()`; dl_iterate_phdr's view follows the move (program headers, the dynamic section's
  address entries, moved symbols' st_value, then read as they are: `symbols_moved`); .eh_frame's PC-relative
  personality and LSDA pointers and each LSDA's type-table entries (typed catches) are repointed (`move_eh_frame`,
  `move_lsda`). What cannot be repointed (code after the moved data, an adr/ldr out of reach, an unwind pointer it
  cannot rewrite) refuses the library; a failed load gives its range back. A line on stderr names each moved library.
  Opt-in only because references with no relocation and no recognisable instruction cannot all be found: clang's
  relative lookup tables (offsets from a table in .rodata to globals), other link-time offsets in data, address
  differences folded into immediates, a one-past-the-end pointer at the moved boundary, data words in code that look
  like adrp. Each review of the move found another class. `--skip-library NAME` makes the app's
  `System.loadLibrary(NAME)` throw without trying (`ElfLoader.skipped`; not DT_NEEDED or dlopen). Every loader error
  names the library's APK entry (`lib/arm64-v8a/NAME.so`) (issue 137).
- A `.so`'s code pages are mapped from the extracted file (`elfloader.c` `load_segment`), not copied, so pages nothing
  patches stay clean (a large app's WebRTC library: 6.1 of 10.9 MB). macOS refuses `mmap(PROT_EXEC)` of a file but
  allows `mprotect` to read+execute of a private file mapping; if refused, the range is copied as before. Never rewrite
  an extracted `.so` in place (ElfLoader renames a new file over it).
- Nothing may change the process locale once the JVM runs threads: macOS's `setlocale` swaps the global locale under
  any thread in `vsnprintf` (a JIT compiler thread crashed in `localeconv_l` while a standby booted, under load). The
  launcher sets `LC_NUMERIC=en_US.UTF-8` on macOS, the value libandroid_runtime's `JNI_OnLoad` sets, so its
  `setlocale` changes nothing. Check new native libraries for `setlocale` imports (`nm -u`).
