# Network: record, replay and conditions

An app's network made hermetic and controllable: record what it fetches, answer from the recording with no backend,
and give it a slow, lossy or missing network, in a running instance and in `--instrument` test runs.

Why: a large app's journeys reached a server address compiled into the app, and `--no-network` (the sandbox that
allows only localhost) broke the journeys that expected the remote route. On/off is not enough; a team needs the
remote route answered the same way every run, and its offline and slow paths tested on purpose.

## Two mechanisms: the tap and the engine

Observing the network and changing it are different jobs, and they are done by different things.

**The tap observes.** Recording to a file, the flight recorder's `net` channel and the network inspector all read the
app's own connections as they pass. The app's socket is the socket it made, connected where it connected it, with the
TLS its own factory does, the ALPN protocol the server picks (HTTP/2 stays HTTP/2, so gRPC works and is recorded) and
the timing it would have had. Nothing stands between the app and the server, so turning recording on cannot break a
request: on each read and write the app's thread pays one array copy and one queue node, and a thread of apksim's own
(`apksim-net-tap`) turns the copies into exchanges. If that thread meets bytes it cannot read, or falls 48 MB behind,
the connection stops being captured; its bytes go on as before. With nothing observing, a hooked call is the original
call after one field read.

**The engine intervenes.** Replaying a recording, network conditions, a host sent to a fixture server and an
inspector rule that answers or changes a request all need something between the app and the server. That is the
network engine, an in-process proxy, and only the connections an intervention concerns go through it:

| What is on | Which connections | How |
|---|---|---|
| nothing, or only observing (`--network record`, `--rec net`, the inspector with no rule) | all | direct, read by the tap |
| `--network replay FILE` | all | answered by the engine from the file |
| conditions (`3g`, `lossy`, `latency`, `bandwidth`) | all | relayed by the engine, shaped; TLS is still the app's, end to end, and the tap still reads it |
| `--network-host H=ADDR` | to `H` | relayed to `ADDR`; while exchanges are recorded, answered by the engine, which does the TLS to the fixture (`--network-trust`) |
| an inspector rule | to the host its pattern names (every host, if it names none) | answered by the engine, which reads each request as HTTP/1.1 |

An answered connection is the one case where the app does not get the server's own protocol: the engine reads
HTTP/1.1, so it offers no ALPN (a client that offers `h2` alone is carried as a tunnel instead, and no rule reaches
it). That is the price of a mock, paid only by the hosts being mocked and only while the rule exists: adding a rule
closes the direct connections to its host so the app's next request meets it, and removing it closes the engine's so
the app is back on its own connections.

## Options

```
--network record FILE        capture every HTTP(S) exchange into FILE (HAR 1.2)
--network replay FILE        answer from FILE; nothing leaves the process
--network-profile NAME       launch with a condition: online (default), offline, 3g, lossy, latency:MS, bandwidth:KBPS
--wifi on|off                Wi-Fi at launch (on by default); see "Wi-Fi and mobile data" below
--cellular on|off            mobile data at launch (off by default)
--wifi-signal DBM|level:N    Wi-Fi's signal at launch: dBm (-126..-1; -50 by default) or a level 0-4
--network-redact NAMES       comma-separated header names to redact as well (default: Authorization,
                             Proxy-Authorization, Cookie, Set-Cookie); "-Name" takes one off the default list
--network-match FIELDS       what a replayed request must match: method,url,body (default), plus headers
--network-ignore-query NAMES query parameters left out of the match (cache busters, timestamps); case-sensitive
--network-loose              a request no entry matches is answered by one that differs only in query values ("Matching")
--network-ignore-header NAMES request headers left out of the match when it includes headers
--network-unmatched POLICY   what an unmatched replay request gets: fail (default: 599 response, logged, and in a
                             test run the test fails), respond (599 only), or fetch:OUT (with --network replay: sent to
                             the real server, answered, and appended to OUT; below)
--network-replay-timing T    none (default: answer at once) or recorded (wait as long as the server took)
--network-host H=ADDR[:PORT] send connections for host H to ADDR (and PORT) instead: a fixture server for an address
                             compiled into the app (repeatable, comma-separated; IPv6 as [::1] or [::1]:8443)
--network-trust FILE         also trust this PEM/DER certificate on the way out (recording against a local HTTPS
                             server with a self-signed certificate): a server the app's own TLS refuses is let
                             through only if its chain holds, or was issued by, one of these, and that is logged
                             On `apksim serve` and `apksim beam` both are per-app launch options (issue 429): `--network-host`
                             only to an address the operator listed (`serve --network-host-allow ADDR[:PORT]`) or the
                             Mac's owner did (`apksim beam config allow-host ADDR[:PORT]`), and `--network-trust
                             inline:BASE64`, the certificate itself, in place of a file (`inline:` also works on the command line)
```

Any of these turns the network layer on; without them nothing changes (one static check per hooked call). Replay
and conditions launches are handed to a warm standby; a recording launches cold, since its sandbox must allow writing
the recording: that file and `FILE.apksim-tmp` beside it (written, then renamed over it), nothing else in its folder. A
replay with `--network-unmatched fetch:OUT` launches cold for the same reason (its sandbox allows OUT,
`OUT.apksim-tmp`, and `OUT.secrets` with its own temporary file), unless OUT is inside the state folder, which a standby may write. `-Dapksim.networkTrace=true` (`--jvm`) logs every exchange.

### Fetching what a recording lacks

`--network replay IN --network-unmatched fetch:OUT` answers what IN has from IN, exactly as a replay does, and sends a
request IN lacks to the real server instead of answering it 599: to the host in the request's own URL (looked up with
the system's DNS, since replaying gives the app placeholders), or where `--network-host` maps that host; over TLS for
https, with the app's own TLS factory as when recording (and `--network-trust` for a fixture's certificate). It is sent on
a connection of its own (`Connection: close`), the answer is read whole (30 s per read), written to the app with
`X-Apksim-Replay: fetched`, and appended to OUT as `--network record` would keep it: the same HAR, the same redaction
(`--network-redact`), gzip stored decoded, the server's certificate chain. OUT holds only what this launch fetched, never
IN's entries; like a recording it is written about 300 ms after each exchange and on the way out (`OUT.apksim-tmp`, then
renamed), and `network save` writes it now. A repeat of a fetched request in the same launch (the same match key, so
`--network-match` and the ignore options apply) is answered from what was fetched, and two connections asking at once
wait for one fetch: the server sees each request once. Fetched requests are not unmatched; the status line counts them
(`N replayed, M fetched, K unmatched`: M requests reached a server; a repeat answered from one counts as replayed). What still cannot be answered is unmatched as with `fail`: a connection that is not
HTTP/1.1, a server that refuses, does not answer or times out, a body over 32 MB either way, a `101` upgrade. This is for
walks (`crawl.py`) that need every launch to see the same answers while new screens keep asking for new things: merge OUT
into the next launch's IN. The policy needs `--network replay`; without it the launch stops with an error.

**Servers that sign the app in.** A recording redacts `Set-Cookie`, and a replayed answer leaves a redacted header out,
so an app whose sign-in is answered from IN would hold no session cookie, and everything fetched after it would be the
server's 401, kept for good. Two things prevent that:

- `OUT.secrets`, beside OUT: the values of the redacted headers of each fetched answer (a sign-in's `Set-Cookie`; any
  response header `--network-redact` names), keyed by the entry's method, URL and `startedDateTime`. It is written
  before OUT, readable by its owner alone (mode 600), and only when a fetched answer had such a header. OUT itself is
  redacted as before and can be shared; the `.secrets` file holds a live session and must not be. A replay of `IN` reads
  `IN.secrets` when it is there and sends those headers to the app with the replayed answer, so the app is signed in as
  the server signed it in, and what it then asks for is fetched with that cookie. To carry a recording on, move or merge
  the two files together (`crawl.py --freeze-network` does); without the `.secrets` file a replay behaves as before, a
  redacted `Set-Cookie` setting nothing. `--network record` writes no such file.
- A fetched 401 or 403 that says nothing about the request is not kept. When this launch replayed an answer with a
  redacted header and had no value for it, a 401 or 403 fetched from that site (the same host, or one sharing its last
  two labels) reaches the app with `X-Apksim-Replay: fetched` but is left out of OUT, so no later launch is answered
  with it and a repeat asks the server again. The same holds for a 401 (not a 403) when the value did come from
  `.secrets`: the server has ended the recorded session. The first per host and status is logged (`network fetch: 401
  for GET ... is not kept: ...`), the status line counts them (`M fetched, N fetched and not kept (401/403 after a
  replayed sign-in)`), and OUT lists them in `log._apksimNotKept` (`count`, and the first 100 `requests` with method,
  URL, status and reason). Any other 401 or 403 (no sign-in replayed, another site) is the server's answer and is kept.

A token a sign-in returns in its body is not redacted, so the app gets the recorded one and sends it; a server that no
longer accepts it answers 401, which is kept (the engine cannot tell where an `Authorization` value came from): record
again, or keep such sessions alive for the length of the walk.

## Control command

`network` (also `adb shell apksim network ...`, `UiAutomation.executeShellCommand("apksim network ...")` in a test,
`DeviceControls`, and the Features > Network menu):

```
network                      mode, file, profile, counts (recorded / replayed / fetched / unmatched)
network online|offline       offline: DNS fails (UnknownHostException), connects fail (ENETUNREACH), open
                             connections are closed, the JDK's lookup cache is flushed (both ways, as Android flushes
                             its resolver's), and ConnectivityManager loses its default network (callbacks get onLost;
                             back online: onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged)
network 3g|lossy             profiles below
network latency MS           extra round-trip time
network bandwidth KBPS       down and up
network unmatched [clear]    unmatched replay requests since the test started (or launch), and forget them
network save                 write the recording (or fetch:OUT) now (it is also written 300 ms after each exchange and on exit)
network reconnect            close the app's open connections, as a change of network does; the app makes them again
                             (after `rec on net` in a running app: connections opened before it are not read, below)
network wifi [on|off|toggle] Wi-Fi's switch (alone: its state and signal)
network wifi signal DBM      Wi-Fi's signal in dBm, or `signal level N` (0-4)
network cellular [on|off|toggle]  mobile data's switch (`mobile` and `data` work too)
```

The status line (`network` alone) ends with the radios: `wifi on, connected, -50 dBm (level 4 of 4); mobile data off`.
The Features > Network menu has Wi-Fi and Mobile Data items (checked while on), and adb has `svc wifi
enable|disable`, `svc data enable|disable`, `cmd wifi set-wifi-enabled enabled|disabled`, `cmd wifi status` and `cmd
phone data enable|disable`.

## Wi-Fi and mobile data

`network offline` is the whole network (airplane mode). The radios (`Radios.java`, `NetConnectivity.publish`) are what
an app on a phone sees when one of them goes, through ConnectivityManager, WifiManager, TelephonyManager and their
broadcasts:

- **Wi-Fi off**: `isWifiEnabled` false and `WIFI_STATE_DISABLED` (WIFI_STATE_CHANGED says DISABLING, then DISABLED,
  each with the state before it), `getConnectionInfo` disconnected (RSSI -127), the Wi-Fi network lost (and a VPN over
  it). With mobile data off too the app is offline exactly as with `network offline` (DNS fails, connects fail, open
  connections close; `network online` does not bring it back while both are off, and the conditions chosen meanwhile
  apply once a radio is back).
- **Mobile data on**: a cellular network beside Wi-Fi, as a phone keeps both: TYPE_MOBILE, LTE, TRANSPORT_CELLULAR,
  INTERNET and VALIDATED and what a default request asks for, but metered (no NOT_METERED; `isActiveNetworkMetered`
  true while it is the default). A listen for cellular hears it; the default stays Wi-Fi until Wi-Fi goes off, when
  default callbacks hear onLost for Wi-Fi and onAvailable for it (Wi-Fi disconnected, as Android tells them), CONNECTIVITY_ACTION names it, and TelephonyManager
  says DATA_CONNECTED. It is off by default (an emulator has one, but apksim never showed one, and a suite counting
  networks would see a change): `--cellular on` launches with it. Its link properties are the host's way out (the Wi-Fi
  network's interface), so libwebrtc still finds candidates.
- **Switching the default network** (Wi-Fi to mobile data and back) closes the connections the network layer sees, as a
  device's change of network breaks them; the app reconnects.
- **The signal**: WifiInfo's RSSI, the Wi-Fi network's `NetworkCapabilities.getSignalStrength()` and its WifiInfo
  (default callbacks hear onCapabilitiesChanged), and RSSI_CHANGED_ACTION with EXTRA_NEW_RSSI to registered receivers.
  `calculateSignalLevel` uses the platform's thresholds (-88, -77, -66, -55), so `level N` picks -95, -82, -71, -60 or
  -50 dBm.
- The app's own `WifiManager.setWifiEnabled` switches Wi-Fi as the command does (after it returns, as the system's
  switch is asynchronous), at any targetSdk as Robolectric allowed it (a device refuses apps targeting 29 and up).

The bytes still go out the Mac's own network, whichever radio the app is told it has: a weak signal or mobile data does
not slow anything by itself. Pair them with a condition for that (`network wifi signal level 1` then `network 3g`).
Not done: a cellular signal strength (TelephonyManager's SignalStrength), roaming, airplane mode's Settings value and
broadcast, `wifi_on`/`mobile_data` in Settings.Global, Wi-Fi scan results, and a VPN shown over mobile data.

Profiles: `3g` = 300 ms RTT, 750 kbps down, 250 kbps up; `lossy` = 150 ms RTT ± 100 ms jitter, 2% of connections
refused, 3% of exchanges reset before their response, 1 Mbps; `latency:MS` and `bandwidth:KBPS` alone; `online` none.

## File format: HAR 1.2

The recording is a standard HAR (Chrome DevTools, Charles and most proxies read it), so it can be inspected and edited
by hand. Per entry: `startedDateTime`, `time`, `request` (method, url, httpVersion, headers, queryString, `postData`
with mimeType and text), `response` (status, statusText, headers, `content` with size, mimeType, text and
`encoding: base64` for binary), `timings` (connect, send, wait, receive). Text bodies are stored as text; gzip bodies
are stored decoded with `content._apksimGzip: true` and re-encoded on replay (only for a client that accepts gzip).
An exchange over HTTP/2 has `httpVersion: "HTTP/2"`, its URL and method from the pseudo-headers (which are not
listed again as headers), and header names in lower case as they were sent. Each entry names its `connection` and
`serverIPAddress`. Extensions (HAR allows `_` fields):

- `log._apksimConnections`: every connection the tap saw, also the ones that carried no exchange: host, port, the
  address reached, when it opened and closed, how long the connect took, the protocol (`h2`, `http/1.1`, `opaque`),
  the TLS version, cipher suite and ALPN choice, bytes each way, how many exchanges, and the error that ended it (a
  connect that failed is here too: an IPv6 route that does not exist, a refused port).
- `_apksimError` on an entry: why it has no whole answer (`the server closed the connection before its answer was
  whole`, `the app reset the stream: CANCEL`, `java.net.SocketTimeoutException: Read timed out`, `closed by the app`).
  Its status is 0 when no answer began. A failed request is evidence, so it is kept; replay never answers with one.
  A connect that failed is an entry too, `CONNECT host:port` with `connect failed: EXCEPTION` ("Failed connects").
- `_apksimRedactedInput` on an entry of a flight recording: the request carried text the recorder leaves out of its
  input journal (a typed password), and its body and URL hold as many `x` instead (flight-recorder.md "Privacy").
  Replay answers a request with that method and URL from it even when the body differs.
- `_apksimUnfinished` on an entry: still going on when the recording was written (a gRPC or event stream that stays
  open): what there was of it by then.
- `response._apksimTrailers`: trailers after the body (gRPC's `grpc-status` and `grpc-message`).
- `_webSocketMessages` on the entry of a `101` upgrade, as Chrome writes them: `{type: send|receive, time, opcode,
  data}` per message, the app's unmasked, permessage-deflate inflated; text as text, anything else base64. The newest
  2000 messages, 4 MB in all, 1 MB of each (`_apksimSize` on one that was cut).

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
recorded as `REDACTED` are left out (a redacted `Set-Cookie` sets nothing), unless the recording has a `.secrets`
file beside it with their values ("Fetching what a recording lacks").

Redacted header values are written as `REDACTED`, and redacted request headers never take part in matching: the
file lists the names it redacted (`log._apksimRedacted`), and those count when replaying whatever that launch's
`--network-redact` says.

## Matching

A request's key is its method, its URL with the ignored query parameters removed and the rest sorted (repeats kept:
`?id=1&id=1` is not `?id=1`), with headers length-prefixed so no value passes for two headers, the SHA-256 of
its body, and (with `--network-match ...,headers`) its request headers apart from hop-by-hop ones, redacted ones and
ignored ones. Entries with the same key answer in recorded order; the last repeats once they run out (polling).

An unmatched request is logged on stderr with the closest recorded entry and what differs:

```
[apksim] network replay: no recorded response for POST https://api.example.test/v1/items
         closest: POST https://api.example.test/v1/items (body differs: 3f2a.. recorded, 9c1e.. now)
```

When the closest entry has the same method, body and query parameter names and only values differ, a third line names
the parameters (`--network-ignore-query device matches it`): a value made at run time, such as an id the app generates.
`--network-loose` (what `apksim replay --loose` passes) answers such a request without naming them: from the entry with
the same method, path, parameter names and body whose values differ in the fewest parameters, one not yet served
first. Exact matches always come first, then an entry marked `_apksimRedactedInput` with the request's method and URL,
then the loose one. `apksim replay` reports how each entry was matched (replay.md "What happened to each request").

It gets a `599 No Recorded Response` with header `X-Apksim-Replay: unmatched` and that text as its body. In a test
run the test it happened in then fails with the same text (after its own result: a passing test becomes a failure),
unless it ran `apksim network unmatched clear` (a test that checks the unmatched path) or the policy is `respond`.

## Where it hooks (measured 2026-11-05, the tap 2026-11-15)

- The owner's R8 release build has no `okhttp3/OkHttpClient$Builder` (0 references in its DEX: R8 renamed OkHttp),
  its debug build has OkHttp (plus Ktor's OkHttp engine, OkHttp WebSockets and a `CertificatePinner`). An
  interceptor added at `OkHttpClient.Builder.build` would miss the release build entirely, and Ktor's engines, and any
  other client.
- What survives R8 is the JDK API: OkHttp connects with `Socket.connect(SocketAddress, int)`, layers TLS with
  `SSLSocketFactory.createSocket(Socket, String, int, boolean)` and reads and writes through
  `Socket.getInputStream()` / `getOutputStream()` (Okio's `socket.source()`), all called from app code, which apksim
  translates. So the translator rewrites, in app code only, these calls to `dev.apksim.net.Net`:
  - `Socket.connect(...)`, `SocketFactory.createSocket(host, port)` and `createSocket(address, port)`: where the
    connection goes is decided here (`Net.route`). Directly, it is the app's own call, and the socket is remembered
    with the host it went to. Through the engine, the socket connects to the engine instead and tells it the real
    destination and whether to answer or relay (`APKSIM host port timeout address answer|relay`); the engine answers
    `OK` or the error the real connect gave (`ERR ConnectException ...`), so a refused or unreachable server fails the
    app's connect as it would have.
  - `SSLSocketFactory.createSocket(socket, host, port, autoClose)`: on a direct or relayed socket, the app's factory
    makes its TLS socket as always, and the tap notes it with the name it is for. On a socket the engine answers, a
    TLS socket that does no TLS (`FakeTlsSocket`): its handshake asks the engine for the certificates (`APKSIM-TLS
    host`; live, the engine does the real TLS handshake with the server then, so a bad certificate fails the app's
    handshake), its session reports TLS 1.3 with that chain, and its bytes reach the engine in plain text. A client
    that offers `h2` and not `http/1.1` (gRPC) cannot be read that way: live, its connection becomes a tunnel, the
    real TLS handshake running over the relayed bytes with the app's own factory (`APKSIM-TLS ... tunnel`, answered
    `TUNNEL`), so it gets the server's ALPN choice; no rule reaches it, the tap reads it, and the inspector lists a
    `CONNECT` row saying so (`scripts/check-h2-tunnel.sh`, `H2TunnelCheck`: loopback and in memory, the JDK's TLS and
    Conscrypt, gRPC's reflective ALPN, Conscrypt's `setApplicationProtocols(String[])`). Every handshake the engine
    answers logs one `TLS handshake offers ALPN none|h2|...` line per host, port, offer and set of setter calls (issue
    395), so a client whose offer never reaches the socket shows `none` and the calls it did make.
  - `Socket.getInputStream()` and `getOutputStream()` (also called on an `SSLSocket`): while the tap is on, for a
    socket the hooks saw connect or layer TLS, the stream handed back is the socket's own with each read and write
    copied to the tap. The socket itself is never wrapped, so everything a client does with it (Conscrypt's own
    ALPN calls, gRPC's reflection on the socket's class, session reuse) is what it does without apksim. Above TLS the
    bytes are in the clear; a plain socket under TLS is read by the JDK's TLS, not by app code, so its ciphertext is
    never copied.
  - `URL.openConnection()`, `openConnection(Proxy)`, `openStream()`: the JDK's `HttpURLConnection` is not app code
    (its sockets are made and read inside the JDK), so this is its hook. Its socket factory is wrapped
    (`Net.TrackingFactory`): the TLS socket the JDK gets passes every call on to the real one and copies what its
    streams carry (`TapSslSocket`; only the JDK ever holds one). Plain `http://` has no such seam, so while exchanges
    are kept it goes through the engine as an HTTP proxy, which reads HTTP/1.1 as it stands (what the JDK's client
    speaks anyway). Where the engine answers (replay, a rule, a mapped host being recorded) the connection gets the
    engine as its proxy and `FakeTlsSocket` as its factory.
  - `HttpsURLConnection.setSSLSocketFactory(f)`: keeps the wrapper (or the fake transport) and hands `f` on, so the
    app's own factory does the real handshake instead of replacing it.
- The tap's decoders (`TapHttp1`, `TapHttp2` with `Hpack`, `TapWebSocket`) read each direction of a connection on
  their own and pair requests with answers. A request is queued for them before it is sent, so it is always there
  before its answer. HTTP/2 needs every header block from the connection's first, since each may refer to fields an
  earlier one put in the table; that is why a connection opened before `rec on net` cannot be read, and is left alone
  (`network reconnect` makes the app open new ones). The TLS session (protocol, cipher suite, ALPN, the server's
  certificates) is read once app data has flowed, so reading it never starts a handshake. A connection whose socket
  the app closed without a read or write noticing is found closed within two seconds.
- The engine (`NetEngine`) parses HTTP/1.1 in both directions for the connections it answers. Live, it opens the real
  connection (real TLS, with the JDK's trust store plus `--network-trust`, for the name the client's TLS gave, not
  the address it connected to), relays the exchange byte for byte, applies the inspector's rules and keeps the
  exchange. It never sends a request twice: a kept-alive server connection that closed is replaced before the request
  goes out, and a failure after that ends the client's connection, whose own retry policy then decides. A body that
  ends before its Content-Length or last chunk is an error, never a recorded entry. It waits as long as the client
  stays idle (a connection opened ahead of its first request); a server that speaks first is relayed unread, and a
  first line that cannot be HTTP (a binary hello with no newline) is handed on at its first impossible byte: relayed
  live, unmatched replaying. Bandwidth pacing comes before each write, so even a body of a few bytes is slowed.
  Replaying: it answers from the file (a HEAD answer keeps the recorded Content-Length; a body stored still encoded,
  deflate or br, keeps its Content-Encoding). A relayed connection (conditions, a mapped host) is carried untouched
  and shaped (latency as a delay line each way, bandwidth as pacing, loss as refused connections and resets; at most
  1 MB queued each way, so a slow profile pushes back on the server instead of filling the heap).
- DNS: a JDK `InetAddressResolverProvider` (`NetResolver`, META-INF/services), so every Java lookup, the JDK's own
  included, sees offline (UnknownHostException, as Android says "Unable to resolve host"), and a replay never needs
  the recorded hosts to resolve (they get a placeholder address in 198.18.0.0/15, the benchmarking range, one per
  host, never shared).
- Natives (`native/bionic_net.c`, Mac): offline only. `connect()` to a non-loopback address fails with ENETUNREACH,
  as do `send`/`sendto`/`sendmsg` to a non-loopback peer or destination (a socket connected before going offline, a
  datagram), and `getaddrinfo` of a non-local name fails with EAI_NONAME. A plain `write()` on a socket that was
  connected before is not stopped. libwebrtc and Cronet's native stack are not recorded.
- A connection through the engine tells it the address the app chose as well as the name (custom DNS, one address
  of several), and the engine connects there; `--network-host` still wins, and TLS and the recording use the name. If that address cannot be reached (a host's AAAA on a network with no IPv6 route), the engine tries the host's other addresses, the other family first, as the app's own fast fallback would have (issue 447).
  `SocketFactory.createSocket(InetAddress, port)` keeps the address it is given too (no new lookup).
- The app's own proxy, reached through a socket the engine answers (OkHttp with `.proxy(...)`): an absolute-form
  request is recorded under its URL and forwarded to the proxy as it was; a CONNECT is forwarded too (replaying, the
  engine answers 200 itself), and the tunnel then starts over (TLS for the name the app gives, then requests,
  recorded under their own https:// URLs, so they replay as direct requests as well). Direct, the tap records the
  CONNECT as an exchange of its own and reads the TLS the app layers on the tunnel as any other.
- Uploads through the engine: the server's answer is read on its own thread while the body goes up, so one that
  answers early (401, 413, then closes) is heard and relayed; the rest of the body is read from the app and hashed
  but not sent. `Expect: 100-continue` is passed on: the body waits for the server's 100 or its final answer, or
  until the app sends it anyway. A final answer before the body is recorded as such
  (`request._apksimExpectAnswered`, by the tap too) and replayed the same way, without reading a body (several such
  answers to one request replay in recorded order).
- Where the engine answers live, the real TLS handshake with the server uses the app's own socket factory (the one
  it layered TLS with, handed to the engine), so its trust manager decides as it would have without apksim: a custom
  one, pinning done in it, client certificates; the protocols and cipher suites the app enabled on its socket hold
  for that handshake, and the session reports what it negotiated. The JDK's HttpURLConnection uses the app's default
  factory if it set one (`setDefaultSSLSocketFactory`) or one set on the connection, else the engine's own TLS (the
  JDK's trust store); a server the app refused gets through only when `--network-trust` vouches for its chain.
  Replaying, no server is reached: what the app's trust manager accepted when recording is what is replayed, and the
  session reports what a server would have negotiated within the app's protocols and suites (TLS 1.3 or 1.2), or
  refuses as one would. Direct and relayed connections need none of this: the handshake is the app's.
- `URL.openConnection(proxy)` with the app's own HTTP or SOCKS proxy keeps that route (no rule or condition reaches
  it; its HTTPS is still read by the tap); replaying, the engine answers it.
- Every HttpsURLConnection's socket factory is wrapped (one wrapper per factory, so the JDK still reuses its
  kept-alive connections), so the socket under its TLS is remembered, or the socket itself when the JDK makes it
  unconnected and connects it, and `network offline` closes it, a download in progress included.
- A direct connect that finishes while `network offline` is closing connections is closed as it registers, and so is
  an unconnected socket the JDK would connect itself. Setting conditions in a running instance (`network 3g`) closes
  the connections made directly, so the app reconnects through the engine; one whose connect was under way then is
  closed as it registers.
- Every read from a server that waits (for the answer to begin, or in the middle of a body) checks every 500 ms
  whether the client is still there: an app that times out and closes its connection ends the server's too, so a
  server that stalls does not keep a worker and both sockets for good. A TLS handshake with the server gets the app's
  connect timeout.
- A recording file is saved 300 ms after each exchange, on the way out (`Standby.halt`) and from a shutdown hook
  (Ctrl-C, the app's `System.exit`). The last save waits up to 2 s for exchanges whose answer has reached the app but
  is still being written down (a large gzip body decodes after it has passed). A save that fails is retried.
- Offline, `URL.openConnection` returns a connection that decides when it is first used: still offline, it fails
  (UnknownHostException for a name, ENETUNREACH for an address) without reaching the JDK, whose cached keep-alive
  sockets could otherwise still carry a request; online again by then, it opens the real connection with everything
  the app set on it (method, headers, timeouts, streaming mode, its TLS factory and hostname verifier) and goes on. A connect's timeout includes the injected round trip (`connect(addr, 100)` under `latency 1500` times
  out after 100 ms). A socket factory that makes only connected sockets cannot go through the engine: replaying, its
  connect fails and counts as unmatched; otherwise it connects directly (read by the tap; no rule or condition
  reaches it, and a line says so).
- Offline also closes the engine's connections on both sides (a worker waiting on a silent server wakes) and every
  direct one, and a numeric-address URL (no lookup to fail) fails without the JDK.
- ConnectivityManager (`NetConnectivity`, over Robolectric's shadow, whose state is static): offline removes the
  networks and tells callbacks of the default one. A launch profile of offline is set before the Application is
  created (SimTestEnvironment.setUpApplicationState, for Host and each test), so `Application.onCreate` already
  sees no network; Host's own networks (HostNetworks, installed later) stay away until `network online`. Two fixes it needed in `HostNetworks`: Robolectric finds the active network by its
  type, so the default network now has netId TYPE_WIFI (apps were handed Robolectric's stand-in, without
  NET_CAPABILITY_INTERNET or VALIDATED); and on Linux a container's `eth0` counts (it had no network at all).
- Loopback (127.0.0.0/8, ::1, localhost) is never touched: a test's MockWebServer and adb forwards keep working. The one exception is a port a beam agent's owner opened with `apksim beam config allow-loopback` (issue 440): connections to it are read by the tap like any other host's, so the inspector lists them. A loopback connect that fails (nothing listening on that port) is a row too (CONNECT, status 0, the connect error as its detail), so a dev server that isn't running shows in the inspector. A TLS handshake the app's factory refuses (an untrusted certificate) on a socket that then carried nothing is a row too (CONNECT, status 0), found when the app closes the socket; the row says the handshake did not finish, not why (the app's own exception has the reason).
- Code that runs for every app references no optional library: `Net` and the tap use only JDK types, so an APK
  without OkHttp is unaffected (the org.webrtc lesson in AGENTS.md "Video").

## Clients

- OkHttp 3, 4, 5, minified or not, and anything on it (Retrofit, Ktor's OkHttp engine, Coil, Glide's OkHttp
  integration): recorded over the protocol the server picks (HTTP/2 from most servers) and replayed (over HTTP/1.1,
  which OkHttp speaks to a server that offers no ALPN). WebSockets: the upgrade and every message are recorded;
  replaying one is an unmatched request.
- gRPC on OkHttp (`grpc-okhttp`) and any other HTTP/2-only client: recorded, each call an entry with its messages as
  the bodies and its status in the trailers; a stream that stays open is in a recording as it stands. Not replayed
  (the replay engine speaks HTTP/1.1).
- `HttpURLConnection` / `HttpsURLConnection`, Ktor's Android engine, Volley's HurlStack, `URL.readText()`: recorded and
  replayed.
- Ktor CIO: uses NIO `SocketChannel` and its own TLS; not hooked (conditions: offline DNS only).
- Cronet (`org.chromium.net`): its native stack does its own TLS in C++, below anything readable; apps fall back to
  their Java engine only when Cronet is missing. Not recorded; offline applies (bionic connect). Making it work would
  need the translator to substitute Cronet's `CronetEngine.Builder` with one that builds `JavaCronetEngine`
  (`setLibraryLoader` to a no-op and a Java provider), whose requests go through HttpURLConnection and so through here.
- `new Socket(host, port)` (the connecting constructors) and NIO channels are not hooked.
- Anything that is not HTTP on a socket the hooks saw (a custom protocol, MQTT): passes as it is, counted as one
  opaque connection in `_apksimConnections` and as a `CONNECT` row in the inspector; once it is known not to be HTTP
  its bytes are counted, not copied.
- An app that brings its own TLS written in Java (a bundled Conscrypt or Bouncy Castle `bctls` whose sockets are not
  the factory's): the library reads the plain socket itself, so that socket shows as an opaque connection (it carries
  ciphertext) and what the app says above the library is not read unless it comes through an `SSLSocket` the hooks saw.
- HTTP/2 connection reuse across hosts: a client may send requests for host A over a connection it opened to host B
  when both share an address and a certificate. An inspector rule that names A sends A's connections through the
  engine, not B's, so such a request passes the rule by; a pattern that names no host concerns every connection.
- Certificate pinning: direct and relayed connections do their own TLS, so pins are checked against the server as
  always. Replayed, the recorded chain is presented, so pins pass as they did. A hand-written entry without
  `_apksim.certificates` presents a placeholder certificate for its host, which passes hostname checks but not pins.

## Measured: the tap against the engine it replaced for observing (2026-11-15, Mac)

One HTTPS connection to a server on the same machine's LAN address, made through the hooks as app code makes it
(a throwaway harness on `work/apksim.jar`; three rounds each, the last shown):

| | a small request and its answer | a 200 MB download | the app's socket and protocol |
|---|---|---|---|
| nothing observing | 27 µs | 1,840 MB/s | its own; the server's ALPN choice |
| observing through the tap | 29 µs | 1,530 MB/s | its own; the server's ALPN choice |
| observing through the engine (0.38.0) | 1,620 µs | 800 MB/s | `FakeTlsSocket`; no ALPN, HTTP/1.1 only |

The engine's 1.6 ms a request is mostly its look, before each request on a kept-alive connection, at whether the
server has closed it meanwhile (a read with a 1 ms timeout); it still does that for the connections it answers.
With the test app (`--rec net`, `open apksim://network?url=https://www.cloudflare.com/cdn-cgi/trace`): "get okhttp:
200 h2 211 bytes", and the saved recording holds that exchange as HTTP/2 with its timings, the server's certificates,
the HttpsURLConnection one beside it, and three connections: OkHttp's first try over IPv6 (`connect failed:
NoRouteToHostException`), its `h2` connection (TLS 1.3, `alpn: h2`) and the JDK's `http/1.1` one.

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
fine. Record with one process: parallel processes recording to one file overwrite each other. The radios work the same
way: `--wifi`, `--cellular` and `--wifi-signal` set each test's start, and a test's `executeShellCommand("apksim
network wifi off")` (or `svc wifi disable`) lasts until the next test's setup (`RadiosTest`).

## Failed connects

A connect that fails before any request is sent leaves no exchange, and a replay that knew nothing of it accepted the
connection and answered the first request with a 599: an app that had seen "connection refused" once while recording
saw a server error in the replay and retried it (16 times, in one large app). So a recording keeps such a connect as an
entry of its own: method `CONNECT`, URL `host:port` as the app named them (not where `--network-host` sent them),
status 0, `_apksimError: "connect failed: java.net.ConnectException: Connection refused"` (the exception as it was
thrown), and how long it took. Both ways a connect is made record it: through the engine (a host `--network-host` maps
to a port nothing listens on, any host under network conditions) and directly (the tap: a host the `--no-network`
sandbox refuses, an address with no route). Loopback connects are left out, as all of loopback is.

Replaying, a connect to a host and port the recording has such entries for, and no answer from, fails with the recorded
exception's class and message (after the recorded time with `--network-replay-timing recorded`); the entries are
served in order, the last repeating. A host and port that also has answers in the recording is connected as before: its
failures were passing, and failing every connect to it would lose the answers. Not kept: a name that did not resolve
(the app's lookup fails before any connect; replaying, every name resolves to a placeholder), and connects that fail
while `rec off net` has the capture paused.

## Checks

- `scripts/check-net-tap.sh` (`TapCheck`; CI's quick checks): the tap as translated app code meets it, against servers
  on the same machine. The app's TLS socket is the one its factory made and gets the server's ALPN choice while
  capturing; HTTP/1.1 (keep-alive, a head and body in pieces, 100 Continue, chunked gzip, HEAD); HTTP/2 with header
  blocks written by the JDK's own HPACK encoder (Huffman, the dynamic table, a CONTINUATION, padded and interleaved
  DATA, gRPC messages and trailers, an open stream marked unfinished, RST_STREAM); WebSocket messages (masked,
  permessage-deflate, fragmented); a body cut short, a request never answered, a connect refused; HttpsURLConnection
  with no proxy in between; bytes that are not HTTP; which hosts a rule sends through the engine; a relayed
  connection keeping its own TLS; and 80 MB written in 31 ms while the decoder stood still for 4 s.
- `scripts/check-replay-match.sh` (`ReplayMatchCheck`; CI's quick checks): left-out input taken out of requests and the
  marked entry's match, loose query values and the option named for them, failed connects recorded and failed again, and
  the per-entry report `apksim replay` writes.
- `scripts/check-net-fetch.sh` (`FetchCheck`; CI's quick checks): `--network-unmatched fetch:OUT` against servers on the
  same machine: a request IN has answered from IN (the server never asked); ones it lacks fetched over plain HTTP and TLS
  (a POST body, gzip, chunked, a 404, a host looked up by its real name) and kept in OUT with Authorization and Set-Cookie
  redacted; a repeat on the same connection, a new one, or two at once reaching the server once; a server that is down
  and a connection that is not HTTP giving the 599 and an unmatched entry; the status line's counts; OUT written within
  a second without a save, holding the fetched exchanges alone and loading as a recording; IN unchanged; and the policy
  without `--network replay` stopping the launch.
- `scripts/media-protocols-check.py APK` (by hand, needs a LAN address): the test app's gRPC (grpc-okhttp over TLS
  and plain h2), WebSocket (wss with permessage-deflate, and ws) and HLS screens against a fixture server built from
  gRPC-java's and OkHttp's own servers, with `--rec net`: every call must succeed and the recording must hold the gRPC
  exchanges as HTTP/2 with their trailers (grpc-status 0, and 3 for the failing call), the deadline's reset stream,
  the WebSocket messages whole and the HLS segments, and list the TLS gRPC connection with ALPN `h2`.
- Test app, `apksim://network?url=URL` adds "Get" (OkHttp) and "Get URL" (HttpURLConnection) for any address, and
  shows the protocol OkHttp got: with `--rec net` on and a server that offers HTTP/2, "get okhttp: 200 h2 ...".
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
