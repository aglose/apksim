# Network: record, replay and conditions

```bash
apksim --network record api.har app.apk                  # use the app against its real backend; every exchange is saved
apksim --network replay api.har app.apk                  # the same answers, with nothing leaving the process
apksim --network replay api.har --instrument app-androidTest.apk app.apk   # hermetic test runs
apksim --network-profile 3g app.apk                      # or offline, lossy, latency:MS, bandwidth:KBPS
```

The recording is a standard HAR 1.2 file, so browser dev tools and proxies can read it, and it can be edited by hand.
`Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` are redacted by default (`--network-redact` adds
names). A replayed request must match on method, URL and body (`--network-match` adds headers, `--network-ignore-query`
and `--network-ignore-header` for cache busters, `--network-loose` for query values made at run time). One that doesn't gets a 599, is logged with the closest recorded
entry, and in a test run fails the test.

It works on the app's own HTTP stack, through the JDK calls R8 never renames, so minified builds record too.
Recording only reads: the app's connections are its own, made as it makes them, with its own TLS (its trust manager
and pins decide) and the protocol its servers pick, so HTTP/2 and gRPC work while recorded and are in the recording,
as are WebSocket messages, requests that failed and why, and every connection with its TLS version and ALPN choice.
A request costs about 2 microseconds more while recorded. Only what has to stand in the way does: replay, network
conditions, a mapped host, an inspector rule for a host. The server's real certificate chain is replayed. To record against a local fixture server for an address compiled into the app, use
`--network-host api.example.com=127.0.0.1:8443` and, for its throwaway certificate, `--network-trust cert.pem`.

Offline is offline the whole way down. DNS fails, connections fail, open ones close, and `ConnectivityManager` loses
its network, so the app's network callbacks fire as on a device. `network offline`, `network 3g` and the others switch
conditions while the app runs (also from the Features > Network menu, `adb shell apksim network ...` or a test's shell
command).

Recorded and replayed: OkHttp 3-5 and everything on it (Retrofit, Ktor's OkHttp engine, Coil), `HttpURLConnection`,
Ktor's Android engine and Volley, including requests through the app's own proxy. Recorded but not replayed:
WebSockets (the upgrade and each message) and HTTP/2-only clients such as gRPC on OkHttp. Not recorded: Cronet's native stack and Ktor CIO, which bring their own TLS and sockets
(offline still applies to them), and NIO channels. Details and measurements: [docs/network.md](network.md).
