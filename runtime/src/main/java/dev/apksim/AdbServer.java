package dev.apksim;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Makes this instance an adb device: `--adb PORT`, then `adb connect localhost:PORT`. It speaks the device side of adb's transport (CNXN, OPEN, OKAY, WRTE, CLSE)
 * as an insecure device: no AUTH, no TLS. Services: `shell:`/`exec:` (AdbShell, which maps getprop, input, am, wm,
 * settings, pm, logcat and screencap onto the control commands) and `sync:` (push, pull, ls), on the app's folders.
 * Bound to loopback, or to -Dapksim.controlBind as the control channel is.
 */
final class AdbServer {
  private static final int A_CNXN = 0x4e584e43;
  private static final int A_OPEN = 0x4e45504f;
  private static final int A_OKAY = 0x59414b4f;
  private static final int A_WRTE = 0x45545257;
  private static final int A_CLSE = 0x45534c43;
  private static final java.util.concurrent.CountDownLatch FRAMEWORK_READY = new java.util.concurrent.CountDownLatch(1);

  /** Called by Host once the sandbox is up: shell and exec requests held until now may run. */
  static void frameworkReady() {
    FRAMEWORK_READY.countDown();
  }

  /** Holds a shell/exec request until the framework is ready (Host.run, or SimRunner for test runs). */
  private static boolean awaitFramework() {
    try {
      return FRAMEWORK_READY.await(60, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Checksums are optional from this version on; it is what current adb sends. */
  private static final int VERSION = 0x01000001;
  private static final int MAX_PAYLOAD = 256 * 1024;
  private static final int SYNC_DATA_MAX = 64 * 1024;

  static volatile int port;
  /** -Dapksim.adbTrace=true: log every service a client opens, to see what a tool asks the device for. */
  static final boolean TRACE = Boolean.getBoolean("apksim.adbTrace");

  private AdbServer() {}

  static void start(int requested) throws IOException {
    String bind = System.getProperty("apksim.controlBind");
    ServerSocket server = new ServerSocket(requested, 8, bind == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(bind));
    port = server.getLocalPort();
    Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "adb.port"), String.valueOf(port));
    System.err.println("[apksim] adb device on localhost:" + port + " (adb connect localhost:" + port + ")");
    AdbLog.install();
    Thread t = new Thread(() -> {
      while (true) {
        try {
          Socket s = server.accept();
          s.setTcpNoDelay(true);
          Thread c = new Thread(new Connection(s), "apksim-adb-transport");
          c.setDaemon(true);
          c.start();
        } catch (IOException e) {
          // Keep accepting.
        }
      }
    }, "apksim-adb");
    t.setDaemon(true);
    t.start();
  }

  /** One adb server's connection to this device. Streams are multiplexed on it, each served on its own thread. */
  private static final class Connection implements Runnable {
    private final Socket socket;
    private final OutputStream out;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private int nextId = 1;
    volatile int maxPayload = 4096;

    Connection(Socket socket) throws IOException {
      this.socket = socket;
      this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    synchronized void send(int cmd, int arg0, int arg1, byte[] data, int off, int len) throws IOException {
      ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
      int sum = 0;
      for (int i = off; i < off + len; i++) sum += data[i] & 0xff;
      h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
      out.write(h.array());
      if (len > 0) out.write(data, off, len);
      out.flush();
    }

    void send(int cmd, int arg0, int arg1) throws IOException {
      send(cmd, arg0, arg1, new byte[0], 0, 0);
    }

    @Override
    public void run() {
      try (socket) {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] header = new byte[24];
        while (true) {
          in.readFully(header);
          ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
          int cmd = h.getInt();
          int arg0 = h.getInt();
          int arg1 = h.getInt();
          int len = h.getInt();
          if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("bad adb packet length " + len);
          byte[] data = new byte[len];
          in.readFully(data);
          switch (cmd) {
            case A_CNXN -> {
              maxPayload = Math.max(4096, Math.min(arg1, MAX_PAYLOAD));
              byte[] banner = banner().getBytes(StandardCharsets.UTF_8);
              send(A_CNXN, VERSION, MAX_PAYLOAD, banner, 0, banner.length);
            }
            case A_OPEN -> open(arg0, nul(data));
            case A_OKAY -> {
              Stream s = streams.get(arg1);
              if (s != null) s.window.release();
            }
            case A_WRTE -> {
              Stream s = streams.get(arg1);
              if (s == null) send(A_CLSE, 0, arg0);
              else s.received(data);
            }
            case A_CLSE -> {
              Stream s = streams.remove(arg1);
              if (s != null) s.remoteClosed();
            }
            default -> {
              // AUTH and STLS only follow a device's request for them, which this device never makes.
            }
          }
        }
      } catch (IOException e) {
        // The adb server went away (adb disconnect, or kill-server).
      } finally {
        for (Stream s : streams.values()) s.remoteClosed();
        streams.clear();
      }
    }

    private void open(int remote, String service) throws IOException {
      if (TRACE && !service.equals("track-jdwp")) System.err.println("[apksim] adb open " + service.replace("\0", "\\0"));
      boolean known = service.startsWith("shell") || service.startsWith("exec:") || service.equals("sync:")
          || service.equals("root:") || service.equals("unroot:");
      if (!known && isForward(service)) {
        // `adb forward tcp:N localabstract:NAME` (or tcp:PORT): the adb server opens this for each connection to N.
        // Connected on a thread of its own, so a slow or unreachable target does not hold up this connection's reader;
        // OKAY once connected, CLSE if not, as adbd answers.
        Thread t = new Thread(() -> {
          Socket target;
          try {
            target = forwardTarget(service);
          } catch (IOException e) {
            System.err.println("[apksim] adb " + service + ": " + e.getMessage());
            try {
              send(A_CLSE, 0, remote);
            } catch (IOException ignored) {
              // The adb server went away.
            }
            return;
          }
          Stream s;
          try {
            s = accept(remote);
          } catch (IOException e) {
            try {
              target.close();
            } catch (IOException ignored) {
              // Closing anyway.
            }
            return;
          }
          pump(s, target);
        }, "apksim-adb-forward");
        t.setDaemon(true);
        t.start();
        return;
      }
      if (!known) {
        System.err.println("[apksim] adb: unsupported service " + service);
        send(A_CLSE, 0, remote);
        return;
      }
      Stream s = accept(remote);
      Thread t = new Thread(() -> serve(s, service), "apksim-adb-" + service.split("[: ]", 2)[0]);
      t.setDaemon(true);
      t.start();
    }

    /** A new stream for the client's REMOTE id, registered before its OKAY so that the client's writes find it. */
    private Stream accept(int remote) throws IOException {
      int local;
      synchronized (this) {
        local = nextId++;
      }
      Stream s = new Stream(this, local, remote);
      streams.put(local, s);
      send(A_OKAY, local, remote);
      return s;
    }

    private static boolean isForward(String service) {
      return service.startsWith("localabstract:") || service.startsWith("localreserved:")
          || service.startsWith("localfilesystem:") || service.startsWith("tcp:");
    }

    private static Socket forwardTarget(String service) throws IOException {
      String name = service.substring(service.indexOf(':') + 1);
      if (service.startsWith("tcp:")) {
        // tcp:PORT or tcp:HOST:PORT, as adbd connects from the device: here the device's localhost is the Mac's.
        int colon = name.lastIndexOf(':');
        String host = colon < 0 ? null : name.substring(0, colon);
        Socket s = new Socket();
        try {
          s.connect(new java.net.InetSocketAddress(host == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(host),
              Integer.parseInt(name.substring(colon + 1))), 10_000);
        } catch (IOException | RuntimeException e) {
          s.close();
          throw e;
        }
        s.setTcpNoDelay(true);
        return s;
      }
      return LocalSockets.connect(name, true);
    }

    /** Copies bytes both ways between an adb stream and a socket until either side closes. */
    private void pump(Stream s, Socket socket) {
      Thread up = new Thread(() -> {
        try {
          s.in.transferTo(socket.getOutputStream());
        } catch (IOException e) {
          // Either side went away.
        } finally {
          // An adb stream has no half-close: the client's CLSE ends both directions, and closing the socket also
          // ends the read below, so neither thread nor socket outlives the stream.
          try {
            socket.close();
          } catch (IOException e) {
            // Already closed.
          }
        }
      }, "apksim-adb-forward-in");
      up.setDaemon(true);
      up.start();
      try (socket) {
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[maxPayload];
        int n;
        while ((n = in.read(buf)) > 0) {
          s.out.write(buf, 0, n);
          s.out.flush();
        }
      } catch (IOException e) {
        // Either side went away.
      } finally {
        s.close();
      }
    }

    private void serve(Stream s, String service) {
      try {
        if (service.equals("sync:")) {
          sync(s);
        } else if (service.equals("root:") || service.equals("unroot:")) {
          s.out.write("adbd is already running as root\n".getBytes(StandardCharsets.UTF_8));
        } else {
          // "shell:CMD" (or "shell,OPTIONS:CMD") and "exec:CMD". exec-out has no terminal in between, which
          // the raw stream here never has either: both deliver bytes as written.
          String cmd = service.substring(service.indexOf(':') + 1);
          boolean exec = service.startsWith("exec:");
          if (!awaitFramework()) {
            // Handling a command first would initialise framework classes (Looper) outside the sandbox.
            s.out.write("apksim: still starting, retry\n".getBytes(StandardCharsets.UTF_8));
            s.out.flush();
            return;
          }
          if (cmd.isBlank() && !exec) new AdbShell(s.out).interactive(s.in);
          else new AdbShell(s.out, s.in, s::isClosed).run(cmd);
        }
        s.out.flush();
      } catch (IOException e) {
        // The client closed the stream (Ctrl-C on logcat, for instance).
      } catch (Exception e) {
        System.err.println("[apksim] adb " + service + ": " + e);
        try {
          s.out.write(("error: " + e + "\n").getBytes(StandardCharsets.UTF_8));
          s.out.flush();
        } catch (IOException ignored) {
          // Gone.
        }
      } finally {
        s.close();
      }
    }

    private String banner() {
      String device = System.getProperty("apksim.device", "phone");
      // fixed_push_mkdir: the device makes a pushed file's folders (otherwise adb runs `mkdir -p` first).
      return "device::ro.product.name=apksim;ro.product.model=apksim_" + device + ";ro.product.device=apksim_" + device
          + ";features=fixed_push_mkdir";
    }
  }

  /** One adb stream: an input fed by WRTE packets (each OKAYed once read) and an output sent as WRTEs, one at a time. */
  static final class Stream {
    private final Connection c;
    private final int local;
    private final int remote;
    final Semaphore window = new Semaphore(1);
    private final BlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(4);
    private static final byte[] EOF = new byte[0];
    private volatile boolean closed;
    final InputStream in = new In();
    final OutputStream out;

    Stream(Connection c, int local, int remote) {
      this.c = c;
      this.local = local;
      this.remote = remote;
      this.out = new BufferedOutputStream(new Out(), c.maxPayload);
    }

    boolean isClosed() {
      return closed;
    }

    void received(byte[] data) {
      if (!incoming.offer(data)) System.err.println("[apksim] adb: peer wrote without waiting for OKAY");
    }

    void remoteClosed() {
      closed = true;
      incoming.clear();
      incoming.offer(EOF);
      window.release();
    }

    void close() {
      if (closed) return;
      closed = true;
      c.streams.remove(local);
      incoming.clear();
      incoming.offer(EOF);  // a reader of this stream's input (a forward's other half) stops too
      try {
        c.send(A_CLSE, local, remote);
      } catch (IOException e) {
        // Connection already gone.
      }
    }

    private final class In extends InputStream {
      private byte[] buf = new byte[0];
      private int pos;

      private boolean fill() throws IOException {
        while (pos >= buf.length) {
          if (buf == EOF) return false;
          try {
            byte[] next = incoming.take();
            if (next == EOF) {
              buf = EOF;
              incoming.offer(EOF);
              return false;
            }
            buf = next;
            pos = 0;
            c.send(A_OKAY, local, remote);  // ready for the next packet
          } catch (InterruptedException e) {
            throw new IOException(e);
          }
        }
        return true;
      }

      @Override
      public int read() throws IOException {
        return fill() ? buf[pos++] & 0xff : -1;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (!fill()) return -1;
        int n = Math.min(len, buf.length - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
      }
    }

    private final class Out extends OutputStream {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
          try {
            while (!window.tryAcquire(200, TimeUnit.MILLISECONDS)) {
              if (closed) throw new IOException("stream closed");
            }
          } catch (InterruptedException e) {
            throw new IOException(e);
          }
          if (closed) throw new IOException("stream closed");
          int n = Math.min(len, c.maxPayload);
          c.send(A_WRTE, local, remote, b, off, n);
          off += n;
          len -= n;
        }
      }
    }
  }

  // ---------------------------------------------------------------- sync: (push, pull, ls)

  private static void sync(Stream s) throws IOException {
    DataInputStream in = new DataInputStream(s.in);
    byte[] header = new byte[8];
    while (true) {
      try {
        in.readFully(header);
      } catch (EOFException e) {
        return;
      }
      String id = new String(header, 0, 4, StandardCharsets.US_ASCII);
      int len = le(header, 4);
      if (id.equals("QUIT")) return;
      byte[] arg = new byte[len];
      in.readFully(arg);
      String path = new String(arg, StandardCharsets.UTF_8);
      switch (id) {
        case "STAT" -> {
          Path p = AdbShell.Paths.host(path, "/");
          int[] st = AdbShell.Paths.stat(path, p);
          s.out.write(msg("STAT", st[0], st[1], st[2]));
        }
        case "LIST" -> {
          for (AdbShell.Paths.Entry e : AdbShell.Paths.list(path)) {
            byte[] name = e.name().getBytes(StandardCharsets.UTF_8);
            s.out.write(msg("DENT", e.mode(), e.size(), e.mtime(), name.length));
            s.out.write(name);
          }
          s.out.write(msg("DONE", 0, 0, 0, 0));
        }
        case "SEND" -> {
          int comma = path.lastIndexOf(',');
          String target = comma > 0 ? path.substring(0, comma) : path;
          String error = null;
          Path p = AdbShell.Paths.host(target, "/");
          Path tmp = null;
          OutputStream file = null;
          if (p == null) {
            error = target + ": apksim maps only /sdcard, /data/local/tmp and the app's /data/data folder";
          } else {
            try {
              if (Files.isDirectory(p)) throw new IOException("is a directory");
              Files.createDirectories(p.getParent());
              tmp = p.resolveSibling("." + p.getFileName() + ".adbpush");
              file = Files.newOutputStream(tmp);
            } catch (IOException e) {
              error = target + ": " + e.getMessage();
            }
          }
          // As adbd does: a failure is reported at once, and the DATA chunks the client sends anyway are read to DONE.
          boolean reported = error != null;
          if (reported) {
            fail(s.out, error);
            s.out.flush();
          }
          while (true) {
            in.readFully(header);
            String part = new String(header, 0, 4, StandardCharsets.US_ASCII);
            int n = le(header, 4);
            if (part.equals("DONE")) {
              if (file != null) {
                file.close();
                try {
                  Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                  Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(n & 0xffffffffL, TimeUnit.SECONDS));
                } catch (IOException e) {
                  error = target + ": " + e.getMessage();
                }
              }
              break;
            }
            if (!part.equals("DATA") || n < 0 || n > SYNC_DATA_MAX) {
              if (file != null) file.close();
              if (tmp != null) Files.deleteIfExists(tmp);
              fail(s.out, "bad sync packet " + part);
              return;
            }
            byte[] data = new byte[n];
            in.readFully(data);
            if (file != null) {
              try {
                file.write(data);
              } catch (IOException e) {
                error = target + ": " + e.getMessage();
                file.close();
                file = null;
                Files.deleteIfExists(tmp);
              }
            }
          }
          if (reported) continue;
          if (error != null) fail(s.out, error);
          else s.out.write(msg("OKAY", 0));
        }
        case "RECV" -> {
          Path p = AdbShell.Paths.host(path, "/");
          if (p == null || !Files.isRegularFile(p)) {
            fail(s.out, "remote object '" + path + "' does not exist");
          } else {
            try (InputStream file = Files.newInputStream(p)) {
              byte[] buf = new byte[SYNC_DATA_MAX];
              int n;
              while ((n = file.readNBytes(buf, 0, buf.length)) > 0) {
                s.out.write(msg("DATA", n));
                s.out.write(buf, 0, n);
              }
            }
            s.out.write(msg("DONE", 0));
          }
        }
        default -> {
          fail(s.out, "unknown sync request " + id);
          s.out.flush();
          return;
        }
      }
      s.out.flush();
    }
  }

  private static void fail(OutputStream out, String why) throws IOException {
    byte[] m = why.getBytes(StandardCharsets.UTF_8);
    out.write(msg("FAIL", m.length));
    out.write(m);
  }

  private static byte[] msg(String id, int... words) {
    ByteBuffer b = ByteBuffer.allocate(4 + 4 * words.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put(id.getBytes(StandardCharsets.US_ASCII));
    for (int w : words) b.putInt(w);
    return b.array();
  }

  private static int le(byte[] b, int off) {
    return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static String nul(byte[] data) {
    int n = 0;
    while (n < data.length && data[n] != 0) n++;
    return new String(data, 0, n, StandardCharsets.UTF_8);
  }
}
