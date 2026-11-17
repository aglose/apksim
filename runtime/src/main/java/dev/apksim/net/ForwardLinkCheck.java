package dev.apksim.net;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The forward link (issue 426) against a stand-in for the beam agent and a local HTTP server: the request as the engine
 * sends it (method, url, headers, body), the answer as the server gave it, an agent's refusal as an IOException, a request
 * with no agent, and the link ending with a request pending. Run by scripts/check-forward-link.sh.
 */
public final class ForwardLinkCheck {
  private static int failures;

  private ForwardLinkCheck() {}

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("apksim.forwardLink.firstLinkMs", "200");
    ForwardLink.on = true;
    HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    http.createContext("/echo", x -> {
      byte[] in = x.getRequestBody().readAllBytes();
      byte[] out = (x.getRequestMethod() + " " + x.getRequestURI() + " " + x.getRequestHeaders().getFirst("X-Test") + " " + new String(in, StandardCharsets.UTF_8))
          .getBytes(StandardCharsets.UTF_8);
      x.getResponseHeaders().add("X-Answer", "yes");
      x.sendResponseHeaders(201, out.length);
      x.getResponseBody().write(out);
      x.close();
    });
    http.createContext("/gone", x -> {
      x.sendResponseHeaders(404, -1);
      x.close();
    });
    http.start();
    String base = "http://127.0.0.1:" + http.getAddress().getPort();
    try {
      // No agent yet and none due: the request fails at once instead of waiting.
      ForwardLink.load();
      ForwardLink.on = true;
      try {
        ForwardLink.fetch("GET", base + "/echo", List.of(), null);
        check(false, "no agent: an IOException");
      } catch (IOException e) {
        check(e.getMessage().contains("no agent"), "no agent: " + e.getMessage());
      }

      Socket agentSide;
      Socket instanceSide;
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        agentSide = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        instanceSide = server.accept();
      }
      PrintWriter instanceOut = new PrintWriter(instanceSide.getOutputStream(), true);
      BufferedReader instanceIn = new BufferedReader(new InputStreamReader(instanceSide.getInputStream(), StandardCharsets.UTF_8));
      Thread.ofVirtual().start(() -> {
        try {
          ForwardLink.serve(instanceIn, instanceOut);
        } catch (IOException ignored) {
        }
      });
      BufferedReader fromInstance = new BufferedReader(new InputStreamReader(agentSide.getInputStream(), StandardCharsets.UTF_8));
      PrintWriter toInstance = new PrintWriter(agentSide.getOutputStream(), true);
      check("ok fwdwatch".equals(fromInstance.readLine()), "the link opens");
      List<String> seen = new ArrayList<>();
      // The agent: sends what it is asked, as the real one does, except that "refuse" is answered with an error.
      Thread.ofVirtual().start(() -> {
        try {
          for (String line; (line = fromInstance.readLine()) != null; ) {
            String l = line;
            Thread.ofVirtual().start(() -> {
              int sp = l.indexOf(' ', 2);
              String id = l.substring(2, sp);
              Map<?, ?> q = (Map<?, ?>) Json.parse(l.substring(sp + 1));
              seen.add(String.valueOf(q.get("url")));
              String url = String.valueOf(q.get("url"));
              if (url.contains("refuse")) {
                toInstance.println("q " + id + " {\"error\":\"not allowed\"}");
                return;
              }
              if (url.contains("hang")) return;
              try {
                List<String[]> hs = new ArrayList<>();
                for (Object o : (List<?>) q.get("headers")) hs.add(new String[] {String.valueOf(((List<?>) o).get(0)), String.valueOf(((List<?>) o).get(1))});
                String b64 = String.valueOf(q.get("body"));
                ForwardSend.Result r = ForwardSend.send(String.valueOf(q.get("method")), url, hs, b64.isEmpty() ? null : Base64.getDecoder().decode(b64), 1 << 20);
                StringBuilder b = new StringBuilder("q " + id + " {\"status\":" + r.status() + ",\"headers\":[");
                for (String[] h : r.headers()) b.append(b.charAt(b.length() - 1) == '[' ? "" : ",").append('[').append(Inspector.q(h[0])).append(',').append(Inspector.q(h[1])).append(']');
                toInstance.println(b.append("],\"body\":\"").append(Base64.getEncoder().encodeToString(r.body())).append("\"}"));
              } catch (Exception e) {
                toInstance.println("q " + id + " {\"error\":" + Inspector.q(String.valueOf(e)) + "}");
              }
            });
          }
        } catch (IOException ignored) {
        }
      });

      ForwardSend.Result r = ForwardLink.fetch("POST", base + "/echo?a=1", List.<String[]>of(new String[] {"X-Test", "t1"}), "body".getBytes(StandardCharsets.UTF_8));
      check(r.status() == 201 && new String(r.body(), StandardCharsets.UTF_8).equals("POST /echo?a=1 t1 body"), "a POST goes out and its answer comes back: " + new String(r.body(), StandardCharsets.UTF_8));
      check(r.headers().stream().anyMatch(h -> h[0].equalsIgnoreCase("X-Answer") && h[1].equals("yes")), "the answer's headers come back");
      r = ForwardLink.fetch("GET", base + "/gone", List.of(), null);
      check(r.status() == 404 && r.body().length == 0, "a 404 is the answer, not an error");
      try {
        ForwardLink.fetch("GET", base + "/refuse", List.of(), null);
        check(false, "the agent's refusal is an IOException");
      } catch (IOException e) {
        check(e.getMessage().equals("not allowed"), "the agent's refusal is an IOException: " + e.getMessage());
      }
      try {
        ForwardLink.fetch("POST", base + "/echo", List.of(), new byte[ForwardLink.MAX_BODY + 1]);
        check(false, "a body over the limit is refused");
      } catch (IOException e) {
        check(e.getMessage().contains("not forwarded"), "a body over the limit is refused: " + e.getMessage());
      }
      // The agent ends the link while a request is pending: that request fails, not waits.
      long t0 = System.currentTimeMillis();
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(300);
          agentSide.close();
        } catch (Exception ignored) {
        }
      });
      try {
        ForwardLink.fetch("GET", base + "/hang", List.of(), null);
        check(false, "a pending request fails when the agent goes");
      } catch (IOException e) {
        check(System.currentTimeMillis() - t0 < 5000, "a pending request fails when the agent goes: " + e.getMessage());
      }
    } finally {
      http.stop(0);
    }
    System.out.println(failures == 0 ? "all forward link checks passed" : failures + " forward link checks failed");
    System.exit(failures == 0 ? 0 : 1);
  }
}
