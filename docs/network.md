# Network: record, replay and conditions

An app's network made hermetic and controllable: record what it fetches, answer from the recording with no backend,
and give it a slow, lossy or missing network, in a running instance and in `--instrument` test runs.

Why: a large app's journeys reached a server address compiled into the app, and `--no-network` (the sandbox that
allows only localhost) broke the journeys that expected the remote route. On/off is not enough; a team needs the
remote route answered the same way every run, and its offline and slow paths tested on purpose.

## Options

```
--network record FILE        capture every HTTP(S) exchange into FILE (HAR 1.2)
--network replay FILE        answer from FILE; nothing leaves the process
--network-profile NAME       launch with a condition: online (default), offline, 3g, lossy, latency:MS, bandwidth:KBPS
--network-redact NAMES       comma-separated header names to redact as well (default: Authorization,
                             Proxy-Authorization, Cookie, Set-Cookie); "-Name" takes one off the default list
--network-match FIELDS       what a replayed request must match: method,url,body (default), plus headers
--network-ignore-query NAMES query parameters left out of the match (cache busters, timestamps); case-sensitive
--network-ignore-header NAMES request headers left out of the match when it includes headers
--network-unmatched POLICY   what an unmatched replay request gets: fail (default: 599 response, logged, and in a
                             test run the test fails) or respond (599 only)
--network-replay-timing T    none (default: answer at once) or recorded (wait as long as the server took)
--network-host H=ADDR[:PORT] send connections for host H to ADDR (and PORT) instead: a fixture server for an address
                             compiled into the app (repeatable, comma-separated)
--network-trust FILE         also trust this PEM/DER certificate on the way out (recording against a local HTTPS
                             server with a self-signed certificate)
```

Any of these turns the network layer on; without them nothing changes (one static check per hooked call). Replay
and conditions launches are handed to a warm standby; a recording launches cold, since its sandbox must allow writing
the recording: that file and `FILE.apksim-tmp` beside it (written, then renamed over it), nothing else in its folder. `-Dapksim.networkTrace=true` (`--jvm`) logs every exchange.

## Control command

`network` (also `adb shell apksim network ...`, `UiAutomation.executeShellCommand("apksim network ...")` in a test,
`DeviceControls`, and the Features > Network menu):

```
network                      mode, file, profile, counts (recorded / replayed / unmatched)
network online|offline       offline: DNS fails (UnknownHostException), connects fail (ENETUNREACH), open
                             connections are closed, the JDK's lookup cache is flushed (both ways, as Android flushes
                             its resolver's), and ConnectivityManager loses its default network (callbacks get onLost;
                             back online: onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged)
network 3g|lossy             profiles below
network latency MS           extra round-trip time
network bandwidth KBPS       down and up
network unmatched [clear]    unmatched replay requests since the test started (or launch), and forget them
network save                 write the recording now (it is also written 300 ms after each exchange and on exit)
```

Profiles: `3g` = 300 ms RTT, 750 kbps down, 250 kbps up; `lossy` = 150 ms RTT ± 100 ms jitter, 2% of connections
refused, 3% of exchanges reset before their response, 1 Mbps; `latency:MS` and `bandwidth:KBPS` alone; `online` none.

## File format: HAR 1.2

The recording is a standard HAR (Chrome DevTools, Charles and most proxies read it), so it can be inspected and edited
by hand. Per entry: `startedDateTime`, `time`, `request` (method, url, httpVersion, headers, queryString, `postData`
with mimeType and text), `response` (status, statusText, headers, `content` with size, mimeType, text and
`encoding: base64` for binary), `timings` (connect, send, wait, receive). Text bodies are stored as text; gzip bodies
are stored decoded with `content._apksimGzip: true` and re-encoded on replay (only for a client that accepts gzip).
Extensions (HAR allows `_` fields):

- `log._apksimCertificates`: each host's certificate chain as the server sent it (base64 DER), so a replayed TLS
  session presents the real certificates: hostname verification and OkHttp `CertificatePinner` pins pass as they did
  when recording.
- `request._bodySha256`: informational; matching recomputes it from `postData.text`, so hand edits count.
- `postData._encoding: base64` for a binary request body; `content._apksimTruncated` for a response body over 32 MB
  (relayed, not stored; replaying it fails the request as unmatched, never an empty 200); `postData._apksimTruncated` for a request body over 32 MB: only its `_bodySha256` is
  stored, and replay matches it by that. Request bodies stream to the server as they arrive (hashed on the way), so
  an upload needs no more memory than without apksim. A gzip body is stored decoded only while it decodes to 32 MB or
  less; a bigger one is stored and replayed as sent.

Replayed responses carry `X-Apksim-Replay: recorded` and a fresh `Content-Length`; hop-by-hop headers and headers
recorded as `REDACTED` are left out (a redacted `Set-Cookie` sets nothing).

Redacted header values are written as `REDACTED`, and redacted request headers never take part in matching: the
file lists the names it redacted (`log._apksimRedacted`), and those count when replaying whatever that launch's
`--network-redact` says.

## Matching

A request's key is its method, its URL with the ignored query parameters removed and the rest sorted (repeats kept:
`?id=1&id=1` is not `?id=1`), the SHA-256 of
its body, and (with `--network-match ...,headers`) its request headers apart from hop-by-hop ones, redacted ones and
ignored ones. Entries with the same key answer in recorded order; the last repeats once they run out (polling).

An unmatched request is logged on stderr with the closest recorded entry and what differs:

```
[apksim] network replay: no recorded response for POST https://api.example.test/v1/items
         closest: POST https://api.example.test/v1/items (body differs: 3f2a.. recorded, 9c1e.. now)
```

It gets a `599 No Recorded Response` with header `X-Apksim-Replay: unmatched` and that text as its body. In a test
run the test it happened in then fails with the same text (after its own result: a passing test becomes a failure),
unless it ran `apksim network unmatched clear` (a test that checks the unmatched path) or the policy is `respond`.

## Where it hooks (measured 2026-11-05)

- The owner's R8 release build has no `okhttp3/OkHttpClient$Builder` (0 references in its DEX: R8 renamed OkHttp),
  its debug build has OkHttp (plus Ktor's OkHttp engine, OkHttp WebSockets and a `CertificatePinner`). An
  interceptor added at `OkHttpClient.Builder.build` would miss the release build entirely, and Ktor's engines, and any
  other client.
- What survives R8 is the JDK API: OkHttp 5 connects with `Socket.connect(SocketAddress, int)` and layers TLS with
  `SSLSocketFactory.createSocket(Socket, String, int, boolean)`, both called from app code, which apksim translates.
- HTTPS bodies are readable only above TLS. So the translator rewrites, in app code only:
  - `Socket.connect(...)` and `SocketFactory.createSocket(host, port)` → `dev.apksim.net.Net`: a connection to a
    non-loopback address goes to apksim's in-process network engine instead, which is told the real destination
    (`APKSIM host port timeout`) and answers `OK` or the error the real connect gave (`ERR ConnectException ...`), so
    a refused or unreachable server fails the app's connect as it would have.
  - `SSLSocketFactory.createSocket(socket, host, port, autoClose)` (and `createSocket(host, port)`) on such a socket,
    when recording or replaying → a TLS socket that does no TLS (`FakeTlsSocket`): its handshake asks the engine for
    the certificates (`APKSIM-TLS host`; recording, the engine does the real TLS handshake with the server then, so a
    bad certificate fails the app's handshake), its session reports TLS 1.3 with that chain, and its bytes reach the
    engine in plain text. ALPN reports none, so clients speak HTTP/1.1 (OkHttp does with any server that offers no
    ALPN). A socket that is an `SSLSocket` from the start (`SSLSocketFactory.createSocket()` then `connect`) connects
    directly: through the engine it would lose the host name its TLS checks need. Net turns
    `SSLSocketFactory.createSocket(host, port)` into a plain socket plus layered TLS, so that common form is covered.
  - `URL.openConnection()`, `openConnection(Proxy)`, `openStream()` → the JDK's own `HttpURLConnection` through the
    engine as an HTTP proxy (`CONNECT` for https, with `FakeTlsSocket` as its `SSLSocketFactory` when recording or
    replaying). The JDK's HttpURLConnection is not app code, so its sockets are not rewritten: this is its hook.
- The engine (`NetEngine`) parses HTTP/1.1 in both directions. Recording: it opens the real connection (real TLS,
  with the JDK's trust store plus `--network-trust`, for the name the client's TLS gave, not the address it
  connected to), relays the exchange byte for byte while capturing it, and adds an entry. It never sends a request
  twice: a kept-alive server connection that closed is replaced before the request goes out, and a failure after that
  ends the client's connection, whose own retry policy then decides. A body that ends before its Content-Length or
  last chunk is an error, never a recorded entry. It waits as long as the client stays idle (a connection opened
  ahead of its first request); a server that speaks first is relayed unrecorded, and a first line that cannot be HTTP
  (a binary hello with no newline) is handed on at its first impossible byte: relayed recording, unmatched replaying.
  Bandwidth pacing comes before each write, so even a body of a few bytes is slowed. Replaying: it answers from the file
  (a HEAD answer keeps the recorded Content-Length; a body stored still encoded, deflate or br, keeps its
  Content-Encoding). Conditions only (no file): it relays bytes and shapes them (latency
  as a delay line each way, bandwidth as pacing, loss as refused connections and resets; at most 1 MB queued each
  way, so a slow profile pushes back on the server instead of filling the heap); TLS stays end to end.
- DNS: a JDK `InetAddressResolverProvider` (`NetResolver`, META-INF/services), so every Java lookup, the JDK's own
  included, sees offline (UnknownHostException, as Android says "Unable to resolve host"), and a replay never needs
  the recorded hosts to resolve (they get a placeholder address in 198.18.0.0/15, the benchmarking range).
- Natives (`native/bionic_net.c`, Mac): offline only. `connect()` to a non-loopback address fails with ENETUNREACH,
  as do `send`/`sendto`/`sendmsg` to a non-loopback peer or destination (a socket connected before going offline, a
  datagram), and `getaddrinfo` of a non-local name fails with EAI_NONAME. A plain `write()` on a socket that was
  connected before is not stopped. libwebrtc and Cronet's native stack are not recorded.
- A redirected connection tells the engine the address the app chose as well as the name (custom DNS, one address
  of several), and the engine connects there; `--network-host` still wins, and TLS and the recording use the name.
- `URL.openConnection(proxy)` with the app's own HTTP or SOCKS proxy keeps that route: recording or shaping, the
  request goes through the app's proxy directly (logged: not recorded or shaped); replaying, the engine answers it.
- Every read from a server that waits (for the answer to begin, or in the middle of a body) checks every 500 ms
  whether the client is still there: an app that times out and closes its connection ends the server's too, so a
  server that stalls does not keep a worker and both sockets for good. A TLS handshake with the server gets the app's
  connect timeout.
- The recording is saved 300 ms after each exchange, on the way out (`Standby.halt`) and from a shutdown hook
  (Ctrl-C, the app's `System.exit`).
- Offline, `URL.openConnection` returns a connection that fails when used (UnknownHostException for a name,
  ENETUNREACH for an address) without reaching the JDK, whose cached keep-alive sockets could otherwise still carry a
  request. A connect's timeout includes the injected round trip (`connect(addr, 100)` under `latency 1500` times
  out after 100 ms). A socket factory that makes only connected sockets cannot go through the engine: replaying, its
  connect fails and counts as unmatched; recording, it connects directly, unrecorded.
- Offline also closes the engine's connections on both sides (a worker waiting on a silent server wakes), the
  connections the hooks made without the engine (before the layer was turned on by a
  `network` command, TLS-first sockets), and a numeric-address URL (no lookup to fail) still goes to the engine, which
  refuses it.
- ConnectivityManager (`NetConnectivity`, over Robolectric's shadow, whose state is static): offline removes the
  networks and tells callbacks of the default one. A launch profile of offline is set before the Application is
  created (SimTestEnvironment.setUpApplicationState, for Host and each test), so `Application.onCreate` already
  sees no network; Host's own networks (HostNetworks, installed later) stay away until `network online`. Two fixes it needed in `HostNetworks`: Robolectric finds the active network by its
  type, so the default network now has netId TYPE_WIFI (apps were handed Robolectric's stand-in, without
  NET_CAPABILITY_INTERNET or VALIDATED); and on Linux a container's `eth0` counts (it had no network at all).
- Loopback (127.0.0.0/8, ::1, localhost) is never touched: a test's MockWebServer and adb forwards keep working.
- Code that runs for every app references no optional library: `Net` uses only JDK types, so an APK without OkHttp
  is unaffected (the org.webrtc lesson in AGENTS.md "Video").

## Clients

- OkHttp 3, 4, 5, minified or not, and anything on it (Retrofit, Ktor's OkHttp engine, Coil, Glide's OkHttp
  integration): recorded and replayed. WebSockets (an upgrade) are relayed while recording but not recorded (the 101
  is left out of the file too); replaying one is an unmatched request.
- `HttpURLConnection` / `HttpsURLConnection`, Ktor's Android engine, Volley's HurlStack, `URL.readText()`: recorded and
  replayed.
- Ktor CIO: uses NIO `SocketChannel` and its own TLS; not hooked (conditions: offline DNS only).
- Cronet (`org.chromium.net`): its native stack does its own TLS in C++, below anything readable; apps fall back to
  their Java engine only when Cronet is missing. Not recorded; offline applies (bionic connect). Making it work would
  need the translator to substitute Cronet's `CronetEngine.Builder` with one that builds `JavaCronetEngine`
  (`setLibraryLoader` to a no-op and a Java provider), whose requests go through HttpURLConnection and so through here.
- `new Socket(host, port)` (the connecting constructors) and NIO channels are not hooked.
- Certificate pinning against the recorded certificates works (the real chain is replayed). A hand-written entry
  without `_apksim.certificates` presents a placeholder certificate for its host, which passes hostname checks but
  not pins.

## Measured on the test app (2026-11-05, Mac)

`scripts/network-check.sh` on the test app's NetworkActivity: recording against the fixture over HTTPS, OkHttp and
HttpURLConnection both answered and the HAR kept bodies (gzip decoded) and the certificate chain, with Authorization
and Set-Cookie redacted; with the server stopped, replay gave the same greetings (`[recorded]`); "Fetch other" got the
599 and `network unmatched` named the closest entry ("path differs"); `network latency 1500` made a fetch over a
kept-alive connection take 1505 ms (a new HttpURLConnection connection: 3 round trips, connect, TLS and the exchange);
offline gave HttpURLConnection an UnknownHostException, "connectivity: none" and one onLost; back online, 200 again.
OkHttp offline gave `ConnectException: Failed to connect to api.apksim.test/198.18.x.x:443` rather than a failed
lookup: after its pooled connection is dropped it retries the same route (an address) once, as it would on a device.
Launch times are unchanged (warm first frame median 277 ms, cold 1.7 s, on a loaded machine); a replay launch is warm
(401 ms first frame).

## In test runs

`--network replay FILE` with `--instrument` replays for the whole run; each test starts with the launch profile
(conditions a test set are undone) and an empty unmatched list. `--isolate`, `--shards` and `-j` share a replay file
fine. Record with one process: parallel processes recording to one file overwrite each other.

## Checks

- Test app, `NetworkActivity` (`open apksim://network`): "Fetch" POSTs to `https://api.apksim.test/v1/greeting`
  with OkHttp (an `Authorization` header, a JSON body) and shows the status, the greeting, `[recorded]` when replayed
  and the time it took; "Fetch other" asks for a path the recording lacks; "URL" GETs `/v1/plain` with
  HttpsURLConnection; a line shows ConnectivityManager's default network as a NetworkCallback reports it. `.test`
  never resolves, so the app works only through apksim's network layer, as an app with a server compiled in.
- `scripts/network-check.sh APK OUT [IMAGE]`: records against `scripts/network-fixture.py` (HTTPS on 127.0.0.1 with
  a throwaway certificate, `--network-host api.apksim.test=127.0.0.1:PORT --network-trust`), checks the HAR
  (Authorization and Set-Cookie redacted, bodies present, no token anywhere in the file), stops the server, replays
  (same greetings), "Fetch other" gives 599, `network latency 1500` makes the fetch take over 1.5 s, `network offline`
  gives a failed lookup and "connectivity: none", `network online` brings both back; then a launch with
  `--network-profile offline`, whose Application.onCreate saw no network, comes online. With IMAGE, in the Linux
  image (the fixture runs inside the container).
- `NetworkReplayTest` (androidTest, runs with `-e networkReplay true` and `--network replay` of the committed
  `testapks/composeapp/app/src/androidTest/network/greeting.har`, which network-check.sh recorded): replayed answers
  through both clients, offline via `executeShellCommand("apksim network offline")` (a failed fetch, no active
  network, one onLost, then one onAvailable back online), the next test starting online again, and an unmatched
  request (599, listed by `network unmatched`, then cleared). With `-e unmatchedOnPurpose true` one more test leaves an
  unmatched request and must fail with it named.
- CI: `ci-smoke.sh` runs network-check.sh in the Linux image (record, replay, conditions), and "Test run with the
  network replayed" runs NetworkReplayTest with `unmatchedOnPurpose` and checks that exactly that test failed.
