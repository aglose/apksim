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
    java.util.function.Consumer<String> relay = null;
    try (Socket s = socket;
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
      s.setTcpNoDelay(true);  // replies are small and a tool may wait on each one
      String line;
      while ((line = in.readLine()) != null) {
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
        // The beam tab relay (dev.apksim.net.TabRelay), on this thread rather than Host's: an app waiting on the main
        // thread for an answer must not hold up the answer. `relay attach` makes this connection the one requests go
        // to; `relayed ID BASE64` answers one and gets no reply (the agent keeps such lines on a connection of their own).
        if (args[0].equals("relayed")) {
          dev.apksim.net.TabRelay.answer(args);
          continue;
        }
        if (args[0].equals("relay") && args.length == 2 && args[1].equals("attach")) {
          java.util.function.Consumer<String> to = out::println;
          String r = dev.apksim.net.TabRelay.attach(to);
          if (r.startsWith("ok")) relay = to;
          out.println(r);
          continue;
        }
        Pending p = new Pending(args);
        submit(p);
        out.println(p.await());
      }
    } catch (IOException | InterruptedException | RuntimeException e) {
      // The client went away (or sent a bad stream request).
    } finally {
      if (relay != null) dev.apksim.net.TabRelay.detach(relay);
    }
  }

  static void start(int port) throws IOException {
    // Loopback only, unless told otherwise: inside a container the host reaches it through a published port.
    String bind = System.getProperty("apksim.controlBind");
    ServerSocket server = new ServerSocket(port, 8, bind == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(bind));
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
