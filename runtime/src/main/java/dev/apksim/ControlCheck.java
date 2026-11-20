package dev.apksim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks who the control channel lets in (scripts/check-control-auth.sh, on a plain JVM): an HTTP request is never
 * read as commands, and with `--control-token-file` a connection must present the token first. Only the gate is
 * exercised: commands themselves need Host's loop.
 */
final class ControlCheck {
  private static int failed;

  private static void expect(String what, boolean ok) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failed++;
  }

  /** Sends text and returns the first line answered, or null when the server closed without one. */
  private static String say(int port, String text) throws Exception {
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
      s.setSoTimeout(3000);
      OutputStream out = s.getOutputStream();
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.flush();
      return new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
    } catch (java.net.SocketException e) {
      return null;  // reset: closed with our bytes unread
    }
  }

  public static void main(String[] args) throws Exception {
    expect("an HTTP request line is recognised", Control.looksLikeHttp("POST / HTTP/1.1") && Control.looksLikeHttp("GET /x?y HTTP/1.0"));
    expect("commands are not taken for HTTP", !Control.looksLikeHttp("tap 100 200") && !Control.looksLikeHttp("open http://x/ HTTP/1.1")
        && !Control.looksLikeHttp("POST"));
    expect("no token: a command is a command", Control.admit("ping", null) == Control.COMMAND);
    expect("no token: auth is answered, so clients may always send it", Control.admit("auth anything", null) == Control.AUTHENTICATED);
    expect("no token: HTTP is still refused", Control.admit("POST / HTTP/1.1", null) == Control.REFUSED);
    String token = "0123456789abcdef0123456789abcdef";
    expect("token: the right one is let in", Control.admit("auth " + token, token) == Control.AUTHENTICATED);
    expect("token: a wrong one is refused", Control.admit("auth " + token.replace('0', '1'), token) == Control.REFUSED);
    expect("token: a prefix of it is refused", Control.admit("auth " + token.substring(0, 20), token) == Control.REFUSED);
    expect("token: a command without it is refused", Control.admit("quit", token) == Control.REFUSED);
    expect("token: HTTP is refused", Control.admit("POST / HTTP/1.1", token) == Control.REFUSED);

    Path dir = Files.createTempDirectory("control-check");
    System.setProperty("java.io.tmpdir", dir.toString());
    Path file = dir.resolve("token");
    Files.writeString(file, token + "\n");
    boolean threw = false;
    try {
      Control.readToken(dir.resolve("missing").toString());
    } catch (java.io.IOException e) {
      threw = true;
    }
    expect("a token file that cannot be read stops the channel from opening", threw);
    Files.writeString(dir.resolve("short"), "abc\n");
    threw = false;
    try {
      Control.readToken(dir.resolve("short").toString());
    } catch (java.io.IOException e) {
      threw = true;
    }
    expect("a short token is refused", threw);

    Control.start(0, file.toString());
    int port = Integer.parseInt(Files.readString(dir.resolve("control.port")).strip());
    expect("over the socket: the token is answered ok", "ok".equals(say(port, "auth " + token + "\n")));
    String refused = say(port, "quit\n");
    expect("over the socket: a command without the token gets an error and nothing else", refused != null && refused.startsWith("error:"));
    expect("over the socket: a wrong token gets an error", String.valueOf(say(port, "auth wrong-wrong-wrong-wrong\n")).startsWith("error:"));
    String page = "POST / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nOrigin: https://example.invalid\r\nContent-Type: text/plain\r\n"
        + "Content-Length: 5\r\n\r\nquit\n";
    expect("over the socket: a web page's POST is closed with no answer, its body unread", say(port, page) == null);
    System.out.println(failed == 0 ? "control check: all passed" : "control check: " + failed + " failed");
    System.exit(failed == 0 ? 0 : 1);
  }
}
