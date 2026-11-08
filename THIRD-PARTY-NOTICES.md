# Third-party notices

apksim itself is Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). It builds on, downloads, or (in
release tarballs and the Linux image) redistributes the components below, each under its own license. Versions are
those `scripts/setup.sh` fetches at the time of writing; the license texts ship inside each component's own jar or
folder. This list is a good-faith summary, not legal advice.

| Component | Used for | License |
|---|---|---|
| [Robolectric](https://github.com/robolectric/robolectric) (`robolectric`, `shadows-framework`, `sandbox`, `nativeruntime`, ...) | Android framework runtime on the JVM | MIT |
| Android framework (`android-all-instrumented`, Robolectric's build of AOSP) and `libandroid_runtime`, fonts, ICU data in `work/runtime` | The framework apps run on | Apache 2.0 (fonts: see the font files' own licenses, SIL OFL / Apache 2.0) |
| [ASM](https://asm.ow2.io/) | Class inspection and rewriting | BSD 3-Clause |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov`) | `BKS` keystore and crypto providers | MIT (Bouncy Castle License) |
| [Conscrypt](https://github.com/google/conscrypt) | TLS provider apps expect | Apache 2.0 |
| [ICU4J](https://github.com/unicode-org/icu) | `android.icu` | Unicode License |
| [Guava](https://github.com/google/guava), `failureaccess`, `listenablefuture`, `j2objc-annotations`, `error_prone_annotations` | Robolectric dependencies | Apache 2.0 |
| [JUnit 4](https://junit.org/junit4/), [Hamcrest 1.3](https://github.com/hamcrest/JavaHamcrest) | Test runner | EPL 1.0 / BSD 3-Clause |
| [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test) (`monitor`, `espresso-idling-resource`) | Instrumentation tests | Apache 2.0 |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml), `sqlite4java`, `javax.inject`, `javax.annotation-api`, `jspecify`, `auto-value-annotations`, `annotations`, `test-parameter-injector` | Robolectric dependencies | Apache 2.0 (`javax.annotation-api`: CDDL 1.1 + GPLv2 with Classpath Exception) |
| [Eclipse Temurin](https://adoptium.net/) JDK 24 (jlinked into release tarballs as `work/jre`) | Java runtime | GPLv2 with Classpath Exception |
| [ANGLE](https://chromium.googlesource.com/angle/angle) (from `work/runtime/gles` or the Android SDK emulator) | Offscreen GLES in test runs | BSD 3-Clause |
| [OpenH264](https://github.com/cisco/openh264) (Linux image, loaded at run time from the distribution's package) | H.264 encoding for `apksim serve` | BSD 2-Clause (Cisco's binary distribution has its own terms) |
| Mesa (Linux image) | Software GLES/EGL | MIT and others |

The Compose test app in `testapks/composeapp` depends on AndroidX, Compose, Kotlin, ExoPlayer/Media3, OkHttp and
other libraries (Apache 2.0) that Gradle resolves when it is built.

Running an APK does not change the APK's own license, and apksim's license does not apply to it.
