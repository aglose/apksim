# Code coverage for device tests

`--coverage DIR` on an `--instrument` run counts how often each method and each source line of the app (and its
modules) ran, with no rebuild of the app and no JaCoCo agent. `scripts/coverage.py` merges the files the test processes
write and exports JaCoCo XML (Codecov, Sonar, Jenkins), LCOV and one HTML page.

```bash
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --coverage cov            # any other options as usual
scripts/instrument.py ... --shards 3 --coverage cov --mapping mapping.txt --source-prefix app/src/main/java/
./apksim --coverage cov --instrument test.apk app.apk                                   # without instrument.py
scripts/coverage.py cov --mapping mapping.txt --out report                              # merge again, other options
```

`cov/` then holds `jacoco.xml`, `lcov.info`, `index.html` (totals, packages, classes, in light and dark) and
`coverage.json` (the merged numbers), beside the per-process files.

## How it works

- Off, nothing changes: the translator's output is byte-identical (`scripts/translate-all.java` writes the same hashes
  for all 26,398 classes of the test app as origin/main's jar). On, the translator gives every class of the probed
  packages (the same choice as `-Dapksim.deps`: `-Dapksim.deps.packages`, else the app package's first two parts, which
  takes in its modules and its test APK) an `int[]` of counters: a slot per method, incremented at its first
  instruction, and a slot per source line the DEX's `debug_info_item` names (`MethodTranslator.readDebugInfo`, which
  coverage turns on without the locals a debugger wants), incremented at the line's first instruction in each basic block.
  A class that is entered for the first time makes its array (`dev.apksim.dex.Coverage`).
- What the slots are travels in the class: static `String` constants `$apksimCovMap0..n` (slot, method name and
  descriptor, line). The class cache therefore holds everything a report needs. Coverage classes have a cache of their
  own (`-cov<hash>`, like `-debug`) and are never used for an ordinary run, or the reverse. A coverage class also has
  a `LineNumberTable` (stack traces gain line numbers) and the Kotlin SMAP.
- When the run ends (`Instrument`, after the last listener) `CoverageDump` writes `cov-<pid>-<n>.cov` (counters above
  zero only) and, if no process has yet, `universe-<key>.cov`: every probed method and line of the app and the tests
  with a zero count, read from the classes' bytes (the cache's, or translated; nothing is loaded or initialized). That
  file is what lets a method no test reached read 0. Its key is the DEX files' checksums, the probed packages and the
  runtime, so the thousands of processes of an `--isolate` run write it once.
- `coverage.py` adds the files up. Shards, isolated processes and reruns need no other bookkeeping: a rerun of a
  crashed process's tests writes files of its own. Counts are what the processes did (an isolated run counts startup
  code once per test), so compare *covered* methods and lines across ways of running a suite, not counts.
  `instrument.py` removes the earlier run's `cov-*.cov` first and merges at the end.
- Counters are plain `int` increments shared by all threads: two threads hitting the same line at once can lose a
  count, so counts of busy lines are approximate; a covered method or line never reads 0, which is what the reports
  and the merge check rely on. Overflow at 2^31 hits per process is not handled.
- A process that dies mid-run (a native crash, the Timeout watchdog) writes nothing: what it covered is lost, and its
  unfinished tests run again in a new process. Coverage is written for the whole run, not per test.

## Reports

- Names are the DEX's. With `--mapping mapping.txt` (R8/ProGuard) classes and methods get their original names, and
  lines go through the mapping where it has line ranges (`a:b:ret name(args):c:d -> obf`, linear when the ranges are
  as long as each other). Inline frames that share an obfuscated range resolve to the outermost method (the one that ran);
  overloads are told apart by parameter and return types; the mapping's `sourceFile` records name files R8 renamed.
  Not done: attributing inlined frames' lines to the inlined method's own file. Classes in the default package
  (R8's repackaging) are covered along with the probed packages; others R8 moved are named with `-Dapksim.deps.packages`.
- Kotlin inline functions: a line whose number the class's SMAP maps into another file counts for that file and line
  (`com/example/Util.kt`), as `affected.py` takes source files from the SMAP. Classes made by D8 (`D8$$SyntheticClass`) have
  no source file and appear in `jacoco.xml` only through their methods.
- `jacoco.xml` follows `report.dtd` (report, package, class, method, sourcefile, line, counter). Counters: METHOD,
  CLASS and LINE are what they say. **INSTRUCTION equals LINE**, and each `<line>` has `mi`/`ci` 1/0 and no branches:
  the translator has no per-instruction or per-branch counters, and tools such as Sonar and Codecov read the lines.
  Class counters count a class's own file's lines; package and report counters count source-file lines, so a line
  shared by classes counts once. Synthetic methods (lambdas, `access$`) are listed, as D8 made them.
- `lcov.info` has `SF`, `FN`/`FNDA`/`FNF`/`FNH`, `DA`, `LF`/`LH` per source file; `--source-prefix` goes before the
  paths (`app/src/main/java/` so that they match the repository). `--include`/`--exclude` take class globs
  (`com.example.*`), `--include-tests` adds the test APK's own classes (left out by default).

## Uploading from CI (the Linux image)

The image runs `instrument.py` as an entrypoint; give the coverage folder a path inside the mounted folder and upload
the files from the host:

```bash
docker run --rm -v "$PWD:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:VERSION \
  app-debug.apk app-debug-androidTest.apk --isolate -j 3 --shards 1 --coverage cov \
  --mapping mapping.txt --source-prefix app/src/main/java/ --xml results.xml
# Codecov (the uploader reads jacoco.xml or lcov.info) -- GitHub Actions:
#   - uses: codecov/codecov-action@v5
#     with: { files: cov/jacoco.xml, token: ${{ secrets.CODECOV_TOKEN }} }
# Sonar: sonar.coverage.jacoco.xmlReportPaths=cov/jacoco.xml
```

With a matrix of `--shard I` jobs, upload each job's `cov/` folder as an artifact and merge them in one job:
`scripts/coverage.py all-cov` after copying every job's `cov-*.cov` (and one `universe-*.cov`) into `all-cov/`; uploads
of several reports to Codecov also merge on its side, but then a line's covered state is per report, not summed. The
files are plain text (about 3 KB of a process's own plus the shared universe, 150 KB for the test app).

## Check and cost

`scripts/coverage-check.py APP TEST` (CI: `ci-smoke.sh`, in the image): `NullBindingTest` hits `NullBindingService.onBind`,
`onCreate` and line 20 and `BluetoothTest` reads 0 for them; methods nothing ran read 0 in `jacoco.xml` and `lcov.info`;
one process, two shards and an isolated run give the same covered methods and lines; `--mapping` renames classes,
methods and lines; `jacoco.xml` is validated against the DTD's structure (element order, attributes, counter types) and its
counters add up; the same tests pass with and without `--coverage`.

Cost on the test app (Apple Silicon, `scripts/instrument.py` on the whole androidTest suite, one process, warm class
caches, interleaved runs; the machine was shared with other work):

| run | plain | `--coverage` |
|---|---|---|
| 1 | 48.2 s | 42.2 s |
| 2 | 31.2 s | 55.8 s |
| 3 | 71.0 s | 67.7 s |
| median | 48.2 s | 55.8 s |

219 tests, the same 140 pass in every run (the 20 failures are the suite's opt-in tests, which need their `-e` flags).
The spread between runs (31-71 s) is larger than any difference: other sessions were using the Mac, and a second copy
of the timing script overlapped part of the run, so this measurement does not resolve the time cost (medians differ by
7 s, within the noise); repeat it on an idle machine before quoting a figure. Class sizes are exact
(`translate-all.java`, the test app's 338 classes of `dev.apksim.composeapp`): 781,481 bytes plain, 1,147,980 with
coverage (+47%: a counter increment is ~12 bytes per method and line, plus the `LineNumberTable` and slot table); the
APK's other 26,060 classes are unchanged. Translating the whole APK takes about the same (14.2 s and 16.8 s in single passes).
