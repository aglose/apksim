# App corpus (offline half)

Of the first 171 issues, 77 were found by a real app and 2 by the project's own tests. The translator crashed three
times on one shape (a super call to a default method: issues 275, 299, 522), each time found by an app at run time as an
`IncompatibleClassChangeError` or `NoSuchMethodError`; missing native symbols were found the same way. The corpus finds
such things without running anything: ten open-source APKs (`scripts/corpus.json`: name, URL, SHA-256, no APK is
committed) are fetched, translated and checked statically. A run-time half (launch each and drive it) is not built.

```
scripts/corpus.py [--list FILE] [--cache DIR] [--only NAME[,NAME]] [--json OUT] [--fail-on-new BASELINE.json] [--selftest]
```

Needs `scripts/build.sh` (work/apksim.jar, work/lib) first. Downloads (curl, a generic user agent) go to
`work/corpus/NAME/SHA256.apk` (https only, 300 MB at most per file; each app's `urls` are tried in order, F-Droid's `/repo/`
then `/archive/`, where a superseded build moves), are checked against the list and are only ever read as ZIP and DEX files, never
installed or launched. A full run takes ~1 minute after the downloads (~420 MB).

## What it checks

1. **Translate failures**: `scripts/link-check.java` translates every class with the same `ClassTranslator` calls as
   `translate-all.java` (app-wide hierarchy across the DEX files, first file winning, and the framework's and JDK's
   interfaces as the live translator knows them); a class it throws on is listed.
2. **Link errors**: every invoke instruction of the translated output is resolved as HotSpot resolves it when it first
   runs (JVMS 5.4.3.3 and 5.4.3.4, and invokespecial's rules: a direct superinterface only, an `ACC_SUPER` selection
   from the direct superclass up), against the app's own classes, then apksim.jar (what translated code calls into),
   the android-all jar and the JDK. Kinds: `icce-owner-kind` (Methodref naming an interface or the reverse),
   `icce-indirect-super` (invokespecial of an interface that is not a direct superinterface: issues 275, 299, 522),
   `icce-static`, `no-such-method`, `abstract-special`, `special-not-super`, `illegal-access` (private, package,
   protected). A class or hierarchy the platform lacks is counted as unresolved, never an error (Play services and the
   like). An APK class that the platform has too (D8 and R8 put stubs of newer framework classes in an app) is skipped:
   the platform's loader answers first. Not checked: fields, class accessibility, invokedynamic, signature-polymorphic
   calls.
3. **Missing native imports**: for the arm64 libraries, symbols no library of the APK defines (`so-imports.py`'s reader;
   weak ones are left out, as the loader leaves them null), no table of the shim answers (`native/bionic.c`,
   `bionic_net.c`, `jnigraphics.c`, `alooper.c` on the Mac; `native/linux/bionic_linux.c` on Linux) and no host library
   has (dlsym through ctypes). The loader binds such an import to a stub that logs when it is called
   (`elfloader.c` `missing_stub`), so a symbol here is a crash or a silent no-op at run time only if the app reaches it.
   The host half is the Python process's view (`dlsym` through ctypes: libc, libm, libz, libc++ where there is one) plus
   the shim tables read as text, **not the JVM's**: inside the JVM `dlsym(RTLD_DEFAULT)` also sees libjvm, Conscrypt and
   apksim's own natives, so a symbol only they define shows as missing here, and the real loader may bind it. The
   set depends on the OS that runs the check (glibc has `lseek64`, macOS does not); the nightly runs on x86_64 Linux
   against the APKs' arm64 libraries, using Linux's shim table, so its numbers differ from a Mac's and compare within
   one OS only (`--fail-on-new` too). A library whose symbol count cannot be read (neither DT_HASH nor DT_GNU_HASH) or
   that does not parse is an `unreadable libs` finding (the table's last column), not a clean one.

Output: one table (APK, classes, translate failures, link errors, missing imports, unreadable libs) and the first findings; `--json` writes
everything (per app: counts, every finding with class, method and instruction, unresolved owners, per library missing
symbols; a finding list is capped at 200,000 per app and `linkErrorsTruncated` says when it was). Exit 0 whatever was found, 1 on a tool error (no build, a download or hash failure, the Java tool failing),
3 when `--fail-on-new BASELINE.json` finds something a previous `--json` file (same OS) lacks.

## Proof the link check catches the 522 shape

`corpus.py --selftest` (also run by ci.yml's `build` job, a few seconds, no download) runs it on
`testapks/translator-fixture` as built (0 link errors in `fixture.dex`, `fixture-d8-debug.dex`, `multidex`), then with
the translator's hierarchy knowledge taken away (`-Dlinkcheck.hierarchy=none`: no super-call retargeting, the
translator before #275 and #299; `single`: each class sees only its own DEX file, before #522), where it must report
at least one `icce-indirect-super` (any other error would not do): 2 in each of the first two
fixtures (`SuperCall$Impl`, `SuperCall2$Impl`) and 1 in `multidex` (`MultiImpl$Impl`, the 522 shape).

## Adding an app

Choose one with a stack the list lacks, a URL that stays valid for that exact build (F-Droid moves a superseded build from `/repo/` to `/archive/`: list both
in `urls`, `/repo/` first; one APK per ABI where the app splits, take the arm64 one) and a licence that allows reading it. Download it once, `shasum -a 256`, add the
entry. Nothing else; the cache is keyed by hash.

## Findings (apksim at the time of writing, macOS, 2026-11-18)

| APK | stack | classes | translate failures | link errors | missing imports |
|---|---|---|---|---|---|
| markor | plain Views, 2 DEX | 8,986 | 0 | 2 | no native libraries |
| wikipedia | Views + Kotlin, R8, 3 DEX | 13,092 | 0 | 0 | 52 in 3 libraries |
| homeassistant | Kotlin + Compose, R8, 9 DEX | 50,377 | 0 | 5 | 11 in 1 |
| florisboard | Compose, Rust library | 6,217 | 0 | 0 | 5 in 1 |
| localsend | Flutter | 3,022 | 0 | 0 | 54 in 2 |
| jitsi | React Native, WebRTC | 9,686 | 0 | 55 | 71 in 6 |
| gramophone | Media3 | 5,545 | 0 | 0 | 1 in 1 |
| wireguard | Go library | 2,099 | 0 | 0 | 3 in 2 |
| mpv | libmpv, FFmpeg | 1,293 | 0 | 0 | 74 in 4 |
| fossify-gallery | Views, image codecs | 7,237 | 0 | 0 | 13 in 2 |

Link errors:

- **jitsi, 55 `icce-indirect-super`**: the shape of issue 522 again, in a variant the translator leaves on purpose.
  `RNCWebViewManager.removeAllViews` (and 51 more React Native view managers: `com.facebook.react.*`,
  `com.horcrux.svg.*`, `com.swmansion.*`, `com.th3rdwave.*`) do an `invokespecial` on `com.facebook.react.uimanager.M`
  (R8's name for `IViewGroupManager`), which they reach only through the superclass `ViewGroupManager implements M`;
  `ViewGroupManager` itself declares `removeAllViews`. `MethodTranslator.superclassReaching` returns null when a class on
  the way declares the method ("would run its own override, not expressible"), so the call keeps its interface owner and
  HotSpot refuses it. As read from ART's invoke-super handling (not checked on a device), it selects from the direct superclass, which
  runs that override: an invokespecial of the direct superclass would do the same. Also `AwakeTimeSinceBootClock.now()` (`i2.b.now()` through
  its direct interface `i2.c`, which redeclares it) and `ReactInstanceManagerHolder$1.getModule` (`f3.D`, through
  `ReactPackageAdapter`). Whether each is reached at run time was not checked (it would need the app launched).
- **markor 2, homeassistant 4 `no-such-method`**: `androidx.window` calls `WindowExtensions.getActivityEmbeddingComponent`,
  `getWindowAreaComponent`, `WindowLayoutComponent.getSupportedWindowFeatures` and `getCurrentWindowLayoutInfo`. apksim.jar's
  stand-in for the device's window extensions is at vendor API level 2 (layout component only), and these methods belong to
  later levels; the library checks the level before calling (`Safe...ComponentProvider`, `isEmbeddingAvailable`), so this
  is a known gap rather than a crash. It does mean an app that gates on the level reads a lower one than a current phone.
- **homeassistant 1 `abstract-special`**: `CronetEngineBase.newBidirectionalStreamBuilder` calls the superclass's abstract
  method of the same signature (AbstractMethodError if reached; the bundled Cronet's own code, probably a path nothing takes).

Missing imports, by group (each library listed in the JSON):

- Bionic names with no macOS equivalent: `lseek64`, `ftruncate64`, `pread64`, `pwrite64`, `__assert2`,
  `__gnu_strerror_r`, `__write_chk`, `__fwrite_chk`, `__strncpy_chk2`, `__sched_cpucount`, `sched_getaffinity`,
  `sched_setaffinity`, `eventfd_read`, `eventfd_write`, `setresuid`, `setresgid`, `malloc_usable_size`, `memalign`,
  `android_dlopen_ext`, `__system_property_find`/`_read` (Mac shim only). These are plain shim gaps; most of them (`lseek64`
  in 9 libraries) are cheap to add to `native/bionic.c`.
- The NDK's platform libraries: `ANativeWindow_*`, `AAssetManager_*`/`AAsset_*`, `eglGetDisplay` and the rest of EGL,
  `AMediaCodec_*` (libavcodec's Android hardware path), OpenSL ES (`SL_IID_*`, libmpv), `ATrace_*`. Each is a feature
  (a window surface, the asset manager, native media), not a one-line shim.
- libjsctooling's `JS*` (55 JavaScriptCore symbols, jitsi's React Native engine option; Hermes is the default and needs
  only `lseek64`, `__write_chk`).

Run-time follow-ups the static check cannot do: whether any of the above is reached, and everything about an app's own
start-up (the Application, providers, resources).
