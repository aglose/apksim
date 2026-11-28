# Network: the tap, the engine, record, replay and conditions

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## Network (record, replay, conditions)
docs/network.md is the spec. `--network record FILE` captures every HTTP(S) exchange into a HAR (bodies, headers,
timings, each host's certificate chain; Authorization, Proxy-Authorization, Cookie and Set-Cookie redacted, more with
`--network-redact`), `--network replay FILE` answers from it with nothing leaving the process (match on method, URL,
body hash, optionally headers; `--network-ignore-query/-header`; an unmatched request gets a 599 with the closest entry
in the log, and in a test run fails the test it happened in unless it ran `apksim network unmatched clear`),
`--network-profile` and the `network` command set conditions (offline, 3g, lossy, latency, bandwidth), and
`--network-host H=ADDR:PORT` points an address compiled into the app at a fixture server (`--network-trust CERT` for
its certificate). Any option turns it on; without one, the hooks cost one static read.
- **Observing and intervening are separate (redesigned 2026-11-15; docs/network.md "Two mechanisms").** Until then
  every capture (a recording, `--rec net`, the inspector) put all of the app's connections through `NetEngine`, a
  proxy that ended the app's TLS and spoke only HTTP/1.1: no ALPN, so gRPC never connected (issues 367, 387), a
  loopback socket the beam sandbox denied (317), HLS players stuttering, every connection closed when the channel
  came on. Now observing is the **tap** (`Tap`): the app's sockets are its own (direct connect, the TLS its factory
  makes, the server's ALPN choice, so HTTP/2 and gRPC), and only what passes through their streams is copied (one
  array copy and a queue node per read or write on the app's thread) for the `apksim-net-tap` thread to decode:
  `TapHttp1`, `TapHttp2` with `Hpack` (tables written out from the JDK's own implementation), `TapWebSocket`.
  A decoder that cannot read a connection, or a queue 48 MB behind, drops that connection's capture, never its
  bytes; nothing the tap does can fail or delay a request. Entries also record failures (`_apksimError`), streams
  still open at a save (`_apksimUnfinished`), trailers, WebSocket messages and every connection with its TLS and
  ALPN (`log._apksimConnections`). The engine is for what must stand in the way, per connection (`Net.route`):
  `MITM` (answered: replay; a host an inspector rule's pattern names, `Inspector.concerns`, every host when it names
  none; a mapped host while recording), `RELAY` (carried untouched and shaped: conditions, a mapped host; the app's
  TLS stays end to end and the tap reads it), else `DIRECT`. A rule added or removed closes the connections it
  changes the route of (`Net.rulesChanged`), as conditions do when they begin; `network reconnect` closes all.
  `rec on net` in a running app closes nothing: connections already open stay unread (HPACK needs a connection
  from its first block) and the answer says how many.
- Where it hooks (measured: the owner's R8 build has no `okhttp3/OkHttpClient$Builder`, R8 renamed OkHttp): the
  translator sends app calls to JDK APIs, which R8 never renames, to `dev.apksim.net.Net` (`MethodTranslator.NET_HOOKS`):
  `Socket.connect`, `Socket.getInputStream`/`getOutputStream` (the tap's seam: the stream is wrapped, never the
  socket, so Conscrypt's and gRPC's own calls on the socket's class are untouched),
  `SocketFactory`/`SSLSocketFactory.createSocket(host, port)`, `SSLSocketFactory.createSocket(socket,
  host, port, autoClose)`, `URL.openConnection`/`openStream` and `HttpsURLConnection.setSSLSocketFactory`. A connection
  routed to the engine goes to its loopback port instead (a first line says where it was going and `answer|relay`;
  the engine answers with the real connect's result). Answered, TLS on such a socket is a `FakeTlsSocket`: its
  handshake gets the server's certificates from the engine (the real chain, so hostname checks and pins pass) and the
  bytes stay plain text, so HTTP/1.1 is readable (a client offering h2 without http/1.1, as gRPC does, is carried as
  a tunnel instead: hello field `tunnel`, reply `TUNNEL`, real TLS over the relayed bytes, `Inspector.tunnel`; no
  rule, mock or `--network-trust` applies inside it, and the tap reads it; `scripts/check-h2-tunnel.sh`). It logs `TLS handshake offers ALPN
  none|h2|...` with the TLS setters the app called, once per host, port, offer and set of setter calls (issue 395); it also takes Conscrypt's
  `setApplicationProtocols(String[])`, which OkHttp's Android adapters find by reflection. The JDK's
  HttpURLConnection is not app code: its socket factory is wrapped (`Net.TrackingFactory`) and hands the JDK a
  `TapSslSocket` (the real socket, every call passed on, streams copied); plain http, and anything the engine
  answers, gets the engine as an HTTP proxy.
  `NetResolver` (META-INF/services) answers every JVM lookup: offline fails it, replaying returns a 198.18/15
  placeholder. Natives: offline only (`bionic_net.c`, Mac). Loopback is never touched, except the ports `apksim beam config allow-loopback` opened (`Net.bypass`, issue 440: read like any host)
  (`-Dapksim.netCheckLoopback=true` lifts that for `scripts/check-net-tap.sh`, whose servers are on this machine).
- `--network-host` and `--network-trust` are also serve and beam launch options (issue 429): `--network-host` only to an
  address on `serve --network-host-allow ADDR[:PORT]` or the beam owner's `apksim beam config allow-host` (`HostAllow`,
  checked by `scripts/check-host-allow.sh`), and the certificate inline as `--network-trust inline:BASE64`
  (`Net.trustInline`; the launcher passes it through), never a host file.
- Code that runs for every app references only JDK types (no OkHttp), as for org.webrtc ("Video").
- Offline also drops the engine's open connections, flushes the JDK's lookup cache (`InetAddress.cache`, as Android
  flushes its resolver's) and takes ConnectivityManager's default network away (`NetConnectivity`). Robolectric finds
  the active network by its type (`getActiveNetwork` looks up netId TYPE_WIFI), so `HostNetworks` gives the default
  network that netId: before, apps got Robolectric's stand-in (no INTERNET or VALIDATED capability). On Linux the
  container's `eth0` counts as Ethernet; before, ConnectivityManager had no network there at all.
  `setDefaultNetworkActive(true)` with an active network calls onAvailable itself, so NetConnectivity sets it first.
  The shadow's state is static, so NetConnectivity applies an offline launch profile before the Application exists
  (`SimTestEnvironment.setUpApplicationState`): `Application.onCreate` sees no network, in Host and in tests.
- What a device's system server has already sent or sends at once, which Robolectric never does (issue 96: an app's
  coroutine awaiting a sticky broadcast or a network callback waited for ever, no exception): `SystemBroadcasts` keeps
  the sticky WIFI_STATE_CHANGED, Wi-Fi NETWORK_STATE_CHANGED, CONNECTIVITY_ACTION (from ConnectivityManager, again on every
  `network offline|online` and HostNetworks install, which publishes nothing under `--network-profile offline`) and
  ACTION_BATTERY_CHANGED (full, not charging), set before Application.onCreate and the providers InstallState creates,
  in running instances and every test. `ShadowSimConnectivityManager` runs network callbacks as ConnectivityService
  does: a default callback is owed the active network, requestNetwork and registerBestMatchingNetworkCallback one
  network (the active one if it satisfies the request, else the first that does), registerNetworkCallback every
  network that satisfies it (`NetworkRequest.canBeSatisfiedBy`). What each is owed is decided as it registers and at
  every change (`rematch`: HostNetworks install, NetConnectivity, which no longer calls callbacks itself), as diffs
  against what it was told (onAvailable + onCapabilitiesChanged + onLinkPropertiesChanged + onBlockedStatusChanged,
  changed capabilities or links, onLost), queued per callback and run in order on its handler (a background ConnectivityThread without one, as on Android);
  a change made on that thread runs the queue at once (unless it is made from inside that callback: then the drain
  under way delivers it after the current announcement), so offline and online before a registration's post give
  available, lost, available. A default callback or a request hears onLost only when its network disconnected or
  nothing replaces it: a switch to another network is just onAvailable, as Android reassigns (a listen hears every
  loss; `NetConnectivity.switchDefaultToEthernetForTest` is SystemSourcesTest's hook for it). Only connected networks
  are offered. requestNetwork with a timeout that nothing satisfies by then hears onUnavailable on its handler (the
  uptime clock) and is released; satisfied or unregistered first, the timeout is cancelled. The host's networks are
  installed before Application.onCreate in running instances too (InstallState), so a monitor registered there hears
  connected first (apksim://system-sources' "startup heard": it heard Wi-Fi down and its default network lost before
  that when Host installed them after onCreate). HostNetworks' networks carry a device Wi-Fi's capabilities (NOT_RESTRICTED, TRUSTED,
  NOT_METERED..., which a default NetworkRequest asks for). `SystemBroadcasts.ShadowSimAudioManager` sends
  VOLUME_CHANGED_ACTION (STREAM_MUTE_CHANGED_ACTION) when setStreamVolume or adjustStreamVolume changes a stream.
  Robolectric prints every broadcast it sends to stderr, so a sticky nobody listens for yet is only kept.
- WifiManager as a connected API 31+ device's (`SimWifi`; issue 96's actual cause): getMaxSignalLevel() 4 and
  calculateSignalLevel(rssi) on RssiUtil's default thresholds (-88/-77/-66/-55), the static
  calculateSignalLevel(rssi, n) by the classic -100..-55 formula, getConnectionInfo at -50 dBm, 5180 MHz, 866 Mbps while
  the active network is Wi-Fi (SSID "AndroidWifi", BSSID and network id only with ACCESS_FINE_LOCATION, else redacted
  as a device does; offline: disconnected, rssi -127). Robolectric's stub IWifiManager made getMaxSignalLevel() 0, so code that
  buckets the level as `level / (max / 4)` threw ArithmeticException (issue 96), which an app's coroutine may never
  report. HostNetworks gives the Wi-Fi network's capabilities that (redacted) WifiInfo and signal strength. A tunnel network (VPN) also gets TRANSPORT_WIFI, NOT_METERED, that WifiInfo and signal when the host has a direct interface, as a device's VPN network reports the underlying transports (issue 347); VPN-only without one.
- In test runs each test starts with the launch profile and no unmatched requests (`Instrument.RawListener`; a test
  failed for an unmatched request gets a failure bundle with `--bundles`, through `Bundles.listenerFailure`); a test
  changes conditions with `executeShellCommand("apksim network offline")`. Recording launches cold (its sandbox must
  allow writing the file and `FILE.apksim-tmp`, and only those, not the folder); replay and conditions launch warm.
- `--network-unmatched fetch:OUT` (with `--network replay IN`; docs/network.md "Fetching what a recording lacks"): a
  request IN lacks is sent to the real server by the engine (`NetEngine.fetch`: its own connection, the system's DNS via
  `NetResolver.systemLookup`, `--network-host` honoured, the app's TLS factory), answered with `X-Apksim-Replay: fetched`
  and kept in `Net.fetched` (OUT, saved as a recording is, `Recording.tapless`); a repeat is answered from it (one fetch
  per match key, `fetchLocks`); failures are unmatched as with `fail`. Launches cold unless OUT is under STATE.
  Servers that sign in with a cookie: a fetched answer's redacted headers' values (`Set-Cookie`) go to `OUT.secrets`
  (mode 600, written before OUT, never in the HAR; `Recording.saveSecrets`/`loadSecrets`, `Entry.secretResp`), and a
  replay of a recording with a `.secrets` file beside it sends them to the app (`writeRecorded`), so later fetches are
  signed in; `crawl.py`'s `NetFreeze` merges and copies it with `network.har`. A fetched 401/403 after a replayed answer
  whose redacted header had no value, or a 401 after one that had, is not kept (`NetEngine.lostCredential`: answered,
  logged once per host, counted in the status line, `log._apksimNotKept` and crawl.json's `network.not_kept`). Before,
  a replayed login left the app without its cookie and the server's 401 to every later fetch was frozen.
  `scripts/check-net-fetch.sh` (`FetchCheck`, CI quick checks).
- A connect that fails before any request (a mapped host nothing listens on, a host the sandbox refuses) is an entry:
  `CONNECT host:port` with `_apksimError: "connect failed: EXCEPTION"` (`Net.connectFailed`, from the engine's
  `connectUpstream` and the tap's `finish`); replaying, a connect to a host and port with such entries and no answer
  fails with that exception again (`Recording.connectFailure`) instead of a 599 the app retries (docs/network.md
  "Failed connects"). `scripts/check-replay-match.sh` (`ReplayMatchCheck`, CI quick checks) checks it with the
  matching above.
- Not covered: Ktor CIO and other NIO `SocketChannel` clients, `new Socket(host, port)`, Cronet's native stack
  (docs/network.md says what it would need), replay of WebSockets and of HTTP/2-only clients such as gRPC (recorded,
  messages included; the replay engine speaks HTTP/1.1). Checks: `scripts/check-net-tap.sh` (`TapCheck`, CI's quick
  checks: the hooks driven as translated code drives them, HTTP/2 against the JDK's HPACK encoder, WebSocket,
  failures, routing, a stalled decoder never blocking a write), `scripts/network-check.sh APK OUT [IMAGE]` (CI runs
  it in the image from `ci-smoke.sh`) and `NetworkReplayTest`
  (`-e networkReplay true` with `--network replay testapks/composeapp/app/src/androidTest/network/greeting.har`).
  By hand, with a network: `--rec net`, `open apksim://network?url=https://SERVER/`, "Get" must show `200 h2`.

## Code notes
- `net/`: the app's network observed (`Tap`, `TapHttp1`, `TapHttp2`, `Hpack`, `TapWebSocket`, `TapSslSocket`:
  the app's own connections read as they pass) and changed (`NetEngine`, `FakeTlsSocket`: replay, conditions,
  mapped hosts, inspector rules), `Net` deciding which per connection, `NetResolver`, `Recording`, and
  `NetConnectivity`: offline as ConnectivityManager shows it (see "Network").
- In the beam sandbox (`Net.inMemory`; `-Dapksim.netInMemory=true` elsewhere, for checks) loopback is denied, so the network
  engine, when a connection needs it (a rule, a condition, a mapped host, replay), is reached in memory: plain-factory
  sockets are then `RelaySocket`s and `connectEngine` joins them to the engine by pipes (`PipeSocket` is the engine's
  end; `TabRelay.Pipe` has a blocking mode). `HttpURLConnection` there goes out directly (its engine proxy would be on
  loopback), as does a socket the app made itself (`new Socket()`): the tap still reads them, but no rule or condition
  reaches them, one log line each (issue 317). Observing alone needs none of this there: the tap has no socket of its
  own. `network-check.sh` has an in-memory pass.
- `loopback.c`: under `--no-network` only (`DYLD_INSERT_LIBRARIES`, set through `/usr/bin/env` after
  `sandbox-exec`, since dyld drops DYLD_* variables for system binaries). The sandbox can allow only
  `localhost` (127.0.0.1 over IPv4, ::1 over IPv6), and the JDK reaches 127.0.0.1 on a dual-stack socket as
  ::ffff:127.0.0.1, which it refused; a TCP `connect()` to a mapped 127.x address swaps the socket for an IPv4
  one on the same descriptor (`dup2`, keeping a bound port), and an already swapped one connects in place.
  `dup2` drops the old socket's kqueue filters (a Selector registered before connect would hang), so it also
  interposes `kevent`/`close` to record each descriptor's filters and adds them to the new socket. Not UDP: the JDK
  later uses IPv6-level options on a DatagramChannel (disconnect fails on an IPv4 socket), so Java UDP to
  127.0.0.1 stays refused. The rest of 127/8 cannot be allowed (the profile language takes only `localhost`).
