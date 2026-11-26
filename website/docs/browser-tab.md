# In a browser tab

```bash
apksim serve --apps builds/            # http://localhost:8080/: pick an APK (or upload one) and use it in the tab
apksim serve --bind 0.0.0.0 --token token.txt --apps builds/   # for others: they open /?token=... once
```

This is for people without Android tools. Each tab runs the APK in its own instance on the server and streams its
screen; clicks, drags, the wheel and the keyboard go back as touches and keys. [docs/serve.md](serve.md) is the
full guide.

- **The stream:** JPEG frames, or H.264 where the browser decodes it, sent only when the screen changes. A tab that
    decodes slower than the app draws gets fewer frames, not freezes.
- **Controls:** Back, Home, rotation, dark mode, device type, text size, locale and deep links are buttons. "Copy
    link" gives a URL that opens the same app in the same settings.
- **Speed:** on a Mac a session's first frame comes in 0.2-0.4 s, a tap shows in ~30 ms on the same machine, and
    animations stream at up to 120 fps.
- **Access:** loopback only, unless a token is set.
- **Sessions:** a session ends when its tab closes or after 10 minutes idle, and its app data is erased. If the
    server keeps sessions (`--keep-max N`) and the session named a key (`keep=KEY`), the next session with that key
    starts signed in where the last one left off.

## For a page that drives the stream itself

- Mute audio, blank video and change animation speed in a running session (`audio`, `video`, `animscale`;
    docs/serve.md).
- Push a file into the app's storage (`POST /api/sessions/ID/files?path=`).
- Save a flight recording and download it, when the server runs with `--allow-rec`.

## Many people watching one session

With `--allow-share`, or after `POST /api/share?allow=on` on a running server, a session's tab has "Copy view-only
link". Whoever opens it sees that screen live, without the server's token, and can press nothing. The link dies with
the session, or when sharing is turned off.

The server sends the same encoded frames to every viewer, so it does not encode again per viewer:

- 1,000 viewers on the same Mac got 60-120 fps;
- 1,500 on another machine got every frame of a 9 fps stream;
- at 2,000 the server's 1 Gbit/s link was nearly full and they fell behind.

See [docs/serve.md](serve.md) "Shared sessions".

## Two builds side by side, and pull request previews

`/?compare=BASE_ID,NEW_ID` starts a session for each build, sends every tap, drag, key and device control to both,
and shows a third screen marking the pixels that differ.

`scripts/pr-preview.py` and the `.github/actions/preview` Action put a pull request's build on a server and keep one
comment on the pull request with its preview and compare links. Added apps can expire (`ttl`). See
[docs/pr-preview.md](pr-preview.md).

## Reports, the network inspector and forwarding

The report button and the inspector are off by default, and forwarding reaches only targets that were allowed.

- **Report button (`--allow-rec`):** packs a recording, the device's settings and a note into one zip. With
    `--issue-url` it opens an issue in the reviewer's own browser.
- **Network inspector (`--inspect-network`):** a panel lists the app's requests as they happen, and can rewrite a
    response or merge a JSON patch into a real one. `--inspect-network optional` (and
    `apksim beam config inspect-network optional`) gives it only to a session that asks.
- **Forwarding:** a page can forward chosen requests to another server. On `apksim beam` the Mac's owner lists the
    allowed targets with `apksim beam config forward-allow URL_PREFIX` and the agent sends them, so a backend on that
    Mac's loopback works (docs/beam.md "Forward rules").
