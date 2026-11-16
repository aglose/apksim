package dev.apksim.fixture;

import io.grpc.InsecureServerCredentials;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.ByteString;

/**
 * The servers the test app's protocol screens talk to (apksim://grpc, apksim://websocket, apksim://media?hls=...), each a
 * real implementation of its protocol, so that apksim's network layer is checked against what an app meets in the
 * field and not against code that shares its assumptions:
 *
 * <ul>
 *   <li>gRPC (gRPC-java's Netty server) over TLS with ALPN h2, and in plain text (h2 with prior knowledge): the service
 *       `apksim.demo.Echo` below, every message a UTF-8 string (no protobuf: the transport is what is checked);
 *   <li>HTTPS (OkHttp's MockWebServer, HTTP/2 and HTTP/1.1 by ALPN) and plain HTTP: a WebSocket at /ws, the HLS clip
 *       (testapks/composeapp/media/hls) at /hls/, and /hello.
 * </ul>
 *
 * <p>One certificate for all of them, made at each start for the names and addresses given (`--san`), written to the
 * output folder as `cert.pem` and as `cert.b64` (DER, base64url without padding: what the app's screens take in their
 * `cert=` parameter and trust alone). Prints `ready {"grpcTls":N,"grpcPlain":N,"https":N,"http":N}` once every server
 * listens, then one `[fixture] ...` line per call, message and request, which a check reads for what the server saw.
 */
public final class ProtoFixture {
  private ProtoFixture() {}

  /** The service's methods, as the app (dev.apksim.composeapp.GrpcActivity) names them. */
  static final String SERVICE = "apksim.demo.Echo";

  static final MethodDescriptor.Marshaller<String> UTF8 = new MethodDescriptor.Marshaller<>() {
    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  };

  static MethodDescriptor<String, String> method(String name, MethodDescriptor.MethodType type) {
    return MethodDescriptor.<String, String>newBuilder().setType(type).setFullMethodName(SERVICE + "/" + name)
        .setRequestMarshaller(UTF8).setResponseMarshaller(UTF8).build();
  }

  private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "fixture-timer");
    t.setDaemon(true);
    return t;
  });

  static void log(String s) {
    synchronized (System.out) {
      System.out.println("[fixture] " + s);
      System.out.flush();
    }
  }

  /**
   * Unary: "echo: " + the request. Count (server streaming): the request is a number N, answered with N messages 40 ms
   * apart. Chat (bidirectional): "chat: " + each message, ended when the client ends. Fail: INVALID_ARGUMENT with a
   * message (non-zero grpc-status in the trailers). Slow: answers after 3 s (the app's deadline ends it first: a
   * RST_STREAM from the client). Big: 300,000 bytes, over several DATA frames and flow-control windows.
   */
  static ServerServiceDefinition service() {
    return ServerServiceDefinition.builder(SERVICE)
        .addMethod(method("Unary", MethodDescriptor.MethodType.UNARY), ServerCalls.asyncUnaryCall((String req, StreamObserver<String> out) -> {
          log("grpc Unary " + req);
          out.onNext("echo: " + req);
          out.onCompleted();
        }))
        .addMethod(method("Count", MethodDescriptor.MethodType.SERVER_STREAMING), ServerCalls.asyncServerStreamingCall((String req, StreamObserver<String> out) -> {
          int n = Integer.parseInt(req.trim());
          log("grpc Count " + n);
          AtomicInteger sent = new AtomicInteger();
          timer.schedule(new Runnable() {
            @Override
            public void run() {
              int i = sent.incrementAndGet();
              if (i > n) {
                out.onCompleted();
                return;
              }
              out.onNext("count " + i);
              timer.schedule(this, 40, TimeUnit.MILLISECONDS);
            }
          }, 40, TimeUnit.MILLISECONDS);
        }))
        .addMethod(method("Chat", MethodDescriptor.MethodType.BIDI_STREAMING), ServerCalls.asyncBidiStreamingCall((StreamObserver<String> replies) -> {
          log("grpc Chat opened");
          return new StreamObserver<String>() {
            @Override
            public void onNext(String m) {
              log("grpc Chat " + m);
              replies.onNext("chat: " + m);
            }

            @Override
            public void onError(Throwable t) {
              log("grpc Chat error " + Status.fromThrowable(t).getCode());
            }

            @Override
            public void onCompleted() {
              log("grpc Chat closed");
              replies.onCompleted();
            }
          };
        }))
        .addMethod(method("Fail", MethodDescriptor.MethodType.UNARY), ServerCalls.asyncUnaryCall((String req, StreamObserver<String> out) -> {
          log("grpc Fail " + req);
          out.onError(Status.INVALID_ARGUMENT.withDescription("fail on purpose").asRuntimeException());
        }))
        .addMethod(method("Slow", MethodDescriptor.MethodType.UNARY), ServerCalls.asyncUnaryCall((String req, StreamObserver<String> out) -> {
          log("grpc Slow " + req);
          timer.schedule(() -> {
            try {
              out.onNext("slow: " + req);
              out.onCompleted();
            } catch (RuntimeException cancelled) {
              // the client gave up first, as it is meant to
            }
          }, 3, TimeUnit.SECONDS);
        }))
        .addMethod(method("Big", MethodDescriptor.MethodType.UNARY), ServerCalls.asyncUnaryCall((String req, StreamObserver<String> out) -> {
          log("grpc Big " + req);
          StringBuilder b = new StringBuilder(300_000);
          while (b.length() < 300_000) b.append((char) ('a' + b.length() % 26));
          out.onNext(b.toString());
          out.onCompleted();
        }))
        .build();
  }

  /**
   * The WebSocket at /ws: "burst N" is answered with N messages (100 at most), a ping with "pong" from the server's side too, any other
   * text with "echo: " + it, bytes with the same bytes; a close is answered with the same code.
   */
  static final class Echo extends WebSocketListener {
    final String scheme;

    Echo(String scheme) {
      this.scheme = scheme;
    }

    @Override
    public void onOpen(WebSocket ws, Response response) {
      log("ws " + scheme + " open " + response.request().header("Sec-WebSocket-Extensions"));
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
      log("ws " + scheme + " text " + text.length() + " " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
      if (text.startsWith("burst ")) {
        int n = Math.min(100, Integer.parseInt(text.substring(6).trim()));  // a check asks for 5; nobody gets a flood
        for (int i = 1; i <= n; i++) ws.send("burst " + i);
      } else {
        ws.send("echo: " + text);
      }
    }

    @Override
    public void onMessage(WebSocket ws, ByteString bytes) {
      log("ws " + scheme + " bytes " + bytes.size());
      ws.send(bytes);
    }

    @Override
    public void onClosing(WebSocket ws, int code, String reason) {
      log("ws " + scheme + " closing " + code + " " + reason);
      ws.close(code, reason);
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response r) {
      log("ws " + scheme + " failure " + t);
    }
  }

  static Dispatcher dispatcher(Path hls, String scheme) {
    return new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        String path = request.getUrl().encodedPath();
        log(scheme + " " + request.getMethod() + " " + path + " " + request.getRequestLine().substring(request.getRequestLine().lastIndexOf(' ') + 1)
            + " " + request.getHeaders().get("User-Agent"));
        if (path.equals("/ws")) {
          // Agrees to compression when the client offers it (OkHttp does): messages then go deflated (RFC 7692).
          MockResponse.Builder b = new MockResponse.Builder().webSocketUpgrade(new Echo(scheme));
          String offered = request.getHeaders().get("Sec-WebSocket-Extensions");
          if (offered != null && offered.contains("permessage-deflate")) b.addHeader("Sec-WebSocket-Extensions", "permessage-deflate");
          return b.build();
        }
        if (path.equals("/hello")) {
          return new MockResponse.Builder().addHeader("Content-Type", "application/json").body("{\"hello\":\"" + scheme + "\"}").build();
        }
        if (path.startsWith("/hls/") && hls != null) {
          String name = path.substring(5);
          Path f = hls.resolve(name).normalize();
          if (name.matches("[A-Za-z0-9._-]+") && f.startsWith(hls) && Files.isRegularFile(f)) {
            try {
              Buffer body = new Buffer().write(Files.readAllBytes(f));
              String type = name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : name.endsWith(".ts") ? "video/mp2t" : "application/octet-stream";
              return new MockResponse.Builder().addHeader("Content-Type", type).body(body).build();
            } catch (IOException e) {
              return new MockResponse.Builder().code(500).build();
            }
          }
        }
        return new MockResponse.Builder().code(404).body("no " + path).build();
      }
    };
  }

  public static void main(String[] args) throws Exception {
    String bind = "127.0.0.1";
    List<String> sans = new ArrayList<>(List.of("localhost", "127.0.0.1"));
    Path out = Path.of(".");
    Path hls = null;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--bind" -> bind = args[++i];
        case "--san" -> sans.add(args[++i]);
        case "--out" -> out = Path.of(args[++i]);
        case "--hls" -> hls = Path.of(args[++i]).toAbsolutePath().normalize();
        default -> {
          System.err.println("usage: fixture [--bind ADDR] [--san NAME|IP]... [--out DIR] [--hls DIR]");
          System.exit(2);
        }
      }
    }
    if (!sans.contains(bind)) sans.add(bind);
    HeldCertificate.Builder cb = new HeldCertificate.Builder().commonName("apksim fixture").duration(2, TimeUnit.DAYS).rsa2048();
    for (String s : sans) cb.addSubjectAlternativeName(s);
    HeldCertificate cert = cb.build();
    Files.createDirectories(out);
    Files.writeString(out.resolve("cert.pem"), cert.certificatePem());
    Files.writeString(out.resolve("cert.b64"), Base64.getUrlEncoder().withoutPadding().encodeToString(cert.certificate().getEncoded()));

    InetAddress addr = InetAddress.getByName(bind);
    ServerCredentials tls = TlsServerCredentials.create(
        new ByteArrayInputStream(cert.certificatePem().getBytes(StandardCharsets.US_ASCII)),
        new ByteArrayInputStream(cert.privateKeyPkcs8Pem().getBytes(StandardCharsets.US_ASCII)));
    Server grpcTls = NettyServerBuilder.forAddress(new InetSocketAddress(addr, 0), tls).addService(service()).build().start();
    Server grpcPlain = NettyServerBuilder.forAddress(new InetSocketAddress(addr, 0), InsecureServerCredentials.create())
        .addService(service()).build().start();

    MockWebServer https = new MockWebServer();
    HandshakeCertificates server = new HandshakeCertificates.Builder().heldCertificate(cert).build();
    https.useHttps(server.sslSocketFactory());
    https.setProtocols(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1));
    https.setDispatcher(dispatcher(hls, "https"));
    https.start(addr, 0);
    MockWebServer http = new MockWebServer();
    http.setDispatcher(dispatcher(hls, "http"));
    http.start(addr, 0);

    String ports = "{\"grpcTls\":" + grpcTls.getPort() + ",\"grpcPlain\":" + grpcPlain.getPort() + ",\"https\":" + https.getPort()
        + ",\"http\":" + http.getPort() + ",\"bind\":\"" + bind + "\"}";
    Files.writeString(out.resolve("ports.json"), ports + "\n");
    System.out.println("ready " + ports);
    System.out.flush();
    // Until stopped (or its parent closes its input).
    while (System.in.read() >= 0) {
      // nothing is read
    }
    grpcTls.shutdownNow();
    grpcPlain.shutdownNow();
    https.close();
    http.close();
    System.exit(0);
  }

}
