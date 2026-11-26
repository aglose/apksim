# Replay, trace viewer and crawler

```bash
apksim replay --headless session.apkrec; echo $?          # 0 matched, 3 diverged, 10 crashed, 2 cannot replay
apksim replay --apk app-new.apk session.apkrec            # the same session on another build (same signer)
scripts/bisect-rec.py session.apkrec builds/              # the first build whose replay diverges, with an HTML report
apksim replay --journey --headless --out out session.apkrec && scripts/journey.py out/journey.json --xml results.xml
apksim rec view session.apkrec                            # the trace viewer, in the default browser
scripts/crawl.py app.apk --out crawl/ --base app-old.apk  # every screen it can reach, mapped, and what changed
```

- **Replay:** a flight recording (`--rec`, Save Replay) starts again from its checkpoint, its input is fed at the
    recorded offsets and its network answered from the recording, and the run says where the screen first differed
    from the recorded frames, or that it never did; with `--control` the app stays up afterwards for a person to carry
    on. See [docs/replay.md](replay.md).
- **Bisect:** `scripts/bisect-rec.py` replays one recording against builds in order and reports the first that
    diverges or crashes, skipping builds it cannot replay, with the frames on each side and their difference.
- **Recording to test:** `apksim replay --journey` also writes the recording as `journey.json`, steps that find their
    element by text, content description or resource id rather than coordinates, each with the texts it should bring
    on screen; `scripts/journey.py` runs it on any build or device and writes JUnit XML.
- **Trace viewer:** one static page that opens a `.apkrec` or a zipped failure bundle by file picker or drag and drop,
    read in the browser with nothing sent anywhere: frames, input, network, logcat and SQLite on one timeline. See
    [docs/trace-viewer.md](trace-viewer.md).
- **Crawler:** `scripts/crawl.py` walks an APK's screens through the accessibility tree within a budget, with no test
    code: a clickable map of screens and the actions between them, the shortest replayable path to each, each screen
    captured across a device, appearance, locale and font-scale matrix, and any crash with the path that caused it. With
    `--base` it walks a second build and says which screens are new, gone or changed. `--sweep all` then puts every
    screen through process death, recreation, rotation, offline, permission denial, large text, right-to-left and dark
    mode and reports what broke (lost state, crashes, endless spinners, cut-off text) as JUnit failures with the commands
    that reproduce each. For a map kept per commit, `--seed RUN --only IDS` walks again only the screens named and carries
    the rest from an earlier walk, and `--record-deps` records for each screen the app classes that ran, their source
    files and the resources it resolved (and the same for the app's start), so a change's files say which screens to walk
    again; `--events` prints a JSON line per screen as the walk goes. Every replay and capture starts from the data the
    app had at its launch (an instance whose app wrote a setting or a file is launched again first), so two walks of one
    build give the same map. A plain walk renders on the GPU at 30 fps with animations off, steals work per action across
    `-j` instances and reuses a screen in place when an action leaves the data alone: a ~169-screen app's walk with `-j 6` and light captures
    went from 8,762 s to 966 s. A screen whose path no longer replays exactly (live data) is still explored where the
    path leads now. See [docs/crawl.md](crawl.md).
