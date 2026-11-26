# System design

[![apksim system design: ways in, the launch, what runs inside the one process, what comes out, and the hosts it runs on](architecture.svg)](architecture.svg)

The diagram is a vector image: click it to open it at full size. Colours say whose code each part is: the app's own,
Android's own (AOSP), apksim's, or the host's.

Reading it from the top:

1. **Ways in.** The Mac window, the control channel, the adb device, a browser tab, the MCP server and test runs are
    each a launch option or a subcommand. In a running instance they all end as the same commands on the Android main
    thread, so a tap from the mouse, from `adb shell input tap`, from a browser tab or from an agent takes one path.
    Test runs are the exception: they have no window and no frame loop, and run each test the way Robolectric does.
2. **Launch.** The launcher hands the APK to a standby JVM that has already booted the framework, as Android's zygote
    has, or starts a JVM from an AOT cache of the framework when no standby is ready.
3. **The process.** Three kinds of code share one address space. The app's DEX code is translated to JVM bytecode,
    class by class, and JIT-compiled by HotSpot like any Java program. The Android framework is AOSP's own, with
    stand-ins where a device would have a system server and other apps. The APK's native libraries are mapped by an
    ELF loader and run on the CPU as they are. `Host` is the Android main thread: it keeps the activity stack,
    dispatches input and draws a frame per display vsync through Android's own HWUI and Skia.
4. **What comes out.** Besides pixels: test reports and failure bundles, coverage, screenshots, flight recordings,
    network recordings and screen maps, most of which another apksim command takes as input.
5. **Runs on.** A Mac with Apple Silicon, with a window and the GPU, or a Linux container, headless, for CI.

## How it works

- **Framework:** real AOSP Android 16 code (API 36) from Robolectric's `android-all`, with its native Skia, HWUI and SQLite for macOS. Robolectric's shadows stand in for system services.
- **App code:** a from-scratch DEX-to-JVM bytecode translator translates classes lazily, as they're first loaded: about 5,400 classes in about 0.6 s. Methods over the JVM's 64 KB limit are split. Translated classes are cached per DEX file, so a rebuilt APK retranslates only the DEX files that changed.
- **Native code:** Android arm64 ELF libraries load directly; it's the same CPU, so nothing is emulated. A bionic-to-macOS shim covers structs, constants, errno and pthreads, and a bridge handles the varargs ABI difference.
- **Pixels:** Android's own render pipeline draws into a buffer that goes straight to an IOSurface on a native Cocoa window, which takes about 0.5 ms a frame to present. AGSL shaders and layer effects are translated to Metal, and H.264, HEVC and VP9 video decode on VideoToolbox.
- **Startup:** the JVM's AOT cache covers the framework for any APK. After each launch, a standby JVM boots the framework in the background, as Android's zygote does, and waits. The next launch hands it the APK and the terminal, and it becomes that instance in ~10 ms.
