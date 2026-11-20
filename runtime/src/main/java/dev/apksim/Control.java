package dev.apksim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/**
 * Line-based control channel so tools and agents can drive an instance: `--control PORT`, then
 * commands such as `tap 100 200`, `swipe x1 y1 x2 y2`, `back`, `shot /path.png`, `tree`, `quit`.
 * Bound to loopback only.
 */
final class Control {
  /** A command waiting to run on the Android main thread. */
  static final class Pending {
    final String[] args;
    private final CountDownLatch done = new CountDownLatch(1);
    private String result = "ok";

    Pending(String[] args) {
      this.args = args;
    }

    void finish(String r) {
      result = r;
      done.countDown();
    }

    String await() throws InterruptedException {
      done.await();
      return result;
    }
  }

  static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();

  /** Queues a command for Host's loop and wakes the loop if it sleeps (Host.idleWait). */
  static void submit(Pending p) {
    QUEUE.add(p);
    Host.wake();
  }

  private Control() {}

  private static void serve(Socket socket) {
    try (Socket s = socket;
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
      s.setTcpNoDelay(true);  // replies are small and a tool may wait on each one
      String line;
      boolean first = true;
      while ((line = in.readLine()) != null) {
        if (first) {
          first = false;
          int gate = admit(line, token);
          if (gate == REFUSED) {
            // No answer for a browser's request (a page could not read it anyway); a word for a tool that forgot.
            if (!looksLikeHttp(line)) out.println("error: this control channel wants `auth TOKEN` first (--control-token-file)");
            return;
          }
          if (gate == AUTHENTICATED) {
            out.println("ok");
            continue;
          }
        }
        String[] args = line.trim().split("\\s+");
        if (args[0].equals("stream")) {
          // stream [QUALITY] [h264 [KBPS]]: this connection becomes a stream of frames (FrameStream) until it closes:
          // JPEG, or H.264 when asked for and there is an encoder here; the answer names the codec when one was asked.
          boolean asked = args.length > 2 && args[2].equals("h264");
          boolean h264 = asked && FrameStream.h264Available();
          out.println(asked ? "ok stream " + (h264 ? "h264" : "jpeg") : "ok stream");
          FrameStream.serve(s.getInputStream(), s.getOutputStream(), args.length > 1 ? Integer.parseInt(args[1]) : 80,
              h264, args.length > 3 ? Integer.parseInt(args[3]) : 4000);
          return;
        }
        if (args[0].equals("netwatch")) {
          // netwatch: this connection becomes the network inspector's event stream (dev.apksim.net.Inspector).
          out.println(dev.apksim.net.Net.inspecting() ? "ok netwatch" : "error: the network inspector is off (--network-inspect)");
          if (dev.apksim.net.Net.inspecting()) dev.apksim.net.Net.watch(s, out);
          return;
        }
        if (args[0].equals("audiowatch")) {
          // audiowatch: this connection becomes the app's audio as PCM chunks, `u ...` lines (dev.apksim.video.AudioTap).
          dev.apksim.video.AudioTap.serve(in, out);
          return;
        }
        if (args[0].equals("relaywatch")) {
          // relaywatch: this connection becomes the tab relay's link, `y ID {json}` lines both ways (dev.apksim.net.TabRelay).
          dev.apksim.net.TabRelay.serve(in, out);
          return;
        }
        if (args[0].equals("fwdwatch")) {
          // fwdwatch: this connection is the link by which the beam agent sends forward rules' requests (dev.apksim.net.ForwardLink).
          dev.apksim.net.ForwardLink.serve(in, out);
          return;
        }
        if (args[0].equals("hostwatch")) {
          // hostwatch: this connection hears of the web links the app opens outside itself, `o URL [REDIRECT]` (HostLinks).
          HostLinks.serve(in, out);
          return;
        }
        if (args[0].equals("tabshell")) {
          // tabshell: an adb shell line from a browser tab (TabShell), run here: its commands wait for Host's loop.
          out.println(oneLine(TabShell.run(args)));
          continue;
        }
        if (args.length > 1 && args[0].equals("network") && (args[1].equals("rule") || args[1].equals("detail")) && dev.apksim.net.Net.inspecting()) {
          // The inspector's rules and details answer here, not through Host's loop (issue 445): the rules a page sends the
          // moment its session opens are applied as they arrive, before the app's first activity starts and its first
          // requests, in order, rather than a few ms apart once the loop is running. The inspector's state is thread-safe. Not logged as a
          // `control` line and not offered to FlightRecorder (it reads SystemClock, which must not initialize outside the sandbox).
          String reply;
          try {
            reply = dev.apksim.net.Net.command(args);
          } catch (RuntimeException e) {
            reply = "error: " + e;  // as Host's loop answers a command that throws; the connection stays
          }
          out.println(oneLine(reply));
          continue;
        }
        Pending p = new Pending(args);
        submit(p);
        out.println(oneLine(p.await()));
      }
    } catch (IOException | InterruptedException | RuntimeException e) {
      // The client went away (or sent a bad stream request).
    }
  }

  /**
   * A reply as one line: its line breaks become "; ", as viewtree joins its lines. Every client reads one line per
   * command, so a second line (an exception message, the app's clipboard) was read as the next command's answer.
   */
  static String oneLine(String reply) {
    if (reply == null) return "null";
    if (reply.indexOf('\n') < 0 && reply.indexOf('\r') < 0) return reply;  // nearly every reply: no copy
    return LINE_BREAK.matcher(reply.strip()).replaceAll("; ");
  }

  private static final java.util.regex.Pattern LINE_BREAK = java.util.regex.Pattern.compile("\\s*[\\r\\n]+\\s*");

  /** The token every connection must present first (`--control-token-file`), or null: any local client may connect. */
  private static volatile String token;

  static final int REFUSED = 0, AUTHENTICATED = 1, COMMAND = 2;

  private static final java.util.regex.Pattern HTTP_REQUEST =
      java.util.regex.Pattern.compile("^\\s*\\S+\\s+\\S+\\s+HTTP/\\d.*");  // any method: no command ends in HTTP/<digit>

  /** Whether a connection's first line is an HTTP request line: a browser, sent here by a web page. */
  static boolean looksLikeHttp(String line) {
    return HTTP_REQUEST.matcher(line).matches();
  }

  /**
   * What a connection's first line means. An HTTP request line is always refused: a page in the user's browser can
   * POST to a loopback port, and the lines of its body would otherwise run as commands (its request line and headers
   * being answered "unknown command"). With a token, the first line must be `auth TOKEN`; without one, `auth ...` is
   * answered ok, so a client may always send it, and anything else is the first command.
   */
  static int admit(String firstLine, String token) {
    if (looksLikeHttp(firstLine)) return REFUSED;
    String line = firstLine.trim();
    boolean auth = line.startsWith("auth ") || line.equals("auth");
    if (token == null) return auth ? AUTHENTICATED : COMMAND;
    if (!auth) return REFUSED;
    byte[] given = line.substring(4).trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return java.security.MessageDigest.isEqual(given, token.getBytes(java.nio.charset.StandardCharsets.UTF_8)) ? AUTHENTICATED : REFUSED;
  }

  /** Reads the token from the file `--control-token-file` names. The channel does not open without it. */
  static String readToken(String file) throws IOException {
    String t = java.nio.file.Files.readString(Storage.absolute(file)).strip();
    if (t.length() < 16 || t.chars().anyMatch(Character::isWhitespace)) {
      throw new IOException("the control token in " + file + " must be at least 16 characters with no spaces");
    }
    return t;
  }

  static void start(int port) throws IOException {
    start(port, System.getProperty("apksim.controlTokenFile"));
  }

  static void start(int port, String tokenFile) throws IOException {
    if (tokenFile != null) token = readToken(tokenFile);
    // Loopback only, unless told otherwise: inside a container the host reaches it through a published port.
    String bind = System.getProperty("apksim.controlBind");
    ServerSocket server = LoopbackPorts.listen(port, bind == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(bind));
    // Port 0 ("--control auto") takes any free port. Either way it is written into this instance's scratch dir,
    // where scripts/ctl.py finds running instances.
    java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "control.port"),
        String.valueOf(server.getLocalPort()));
    if (port == 0) System.err.println("[apksim] control port " + server.getLocalPort());
    Thread t = new Thread(() -> {
      // Each connection on its own thread: a tool can watch (`stream`) while another drives the instance.
      while (true) {
        Socket s;
        try {
          s = server.accept();
        } catch (IOException e) {
          continue;
        }
        Thread c = new Thread(() -> serve(s), "apksim-control-" + s.getPort());
        c.setDaemon(true);
        c.start();
      }
    }, "apksim-control");
    t.setDaemon(true);
    t.start();
  }
}
