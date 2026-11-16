import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Viewers of one shared `apksim serve` session, many at once (scripts/share-check.py runs it; docs/serve.md "Shared
 * sessions"): each joins with the session's link, reads and acknowledges frames without decoding them, and reports
 * what it saw. A thread per viewer (virtual), so a thousand cost this machine a few cores, not the server's.
 *
 *   java scripts/share-swarm.java HOST PORT PATH AUTHORITY ORIGIN VIEWERS SLOW SLOW_MS START_MS UNTIL_MS SECONDS
 *
 * PATH is the join socket's (`/ws?join=ID&key=KEY`); SLOW of the viewers acknowledge each frame SLOW_MS late; all join
 * at START_MS and watch until UNTIL_MS (epoch ms), and frame rates are over the last SECONDS. Prints one JSON object:
 * `sentFps` (frames any viewer got, a second) and `viewers`: each one's mode, seconds to its first frame, frames, bytes,
 * keyframes, whether its first frame was a keyframe, whether its chain broke (a frame it missed not followed by a
 * keyframe; H.264), its frame rate, the last viewer count it was told and how it ended.
 */
public class ShareSwarm {
  static final byte[] ACK = masked(1, "ack".getBytes(StandardCharsets.US_ASCII));
  static final ConcurrentSkipListSet<Long> ALL = new ConcurrentSkipListSet<>();
  static final ScheduledExecutorService LATER = Executors.newScheduledThreadPool(2);

  static byte[] masked(int op, byte[] data) {
    byte[] b = new byte[6 + data.length];
    b[0] = (byte) (0x80 | op);
    b[1] = (byte) (0x80 | data.length);  // under 126 bytes: acks and pongs
    b[2] = 1; b[3] = 2; b[4] = 3; b[5] = 4;
    for (int i = 0; i < data.length; i++) b[6 + i] = (byte) (data[i] ^ b[2 + (i & 3)]);
    return b;
  }

  static final class Viewer implements Runnable {
    final boolean slow;
    final String host, path, authority, origin;
    final int port, slowMs;
    final long until;
    final CountDownLatch done;
    double first = -1;
    long frames, bytes, keys;
    Boolean firstKey;
    long[] ts = new long[2048];
    boolean[] key = new boolean[2048];
    int n;
    String codec = "", end = "";
    int told;
    boolean closed;

    Viewer(boolean slow, String host, int port, String path, String authority, String origin, int slowMs, long until, CountDownLatch done) {
      this.slow = slow; this.host = host; this.port = port; this.path = path; this.authority = authority; this.origin = origin;
      this.slowMs = slowMs; this.until = until; this.done = done;
    }

    @Override public void run() {
      long t0 = System.nanoTime();
      try (Socket s = new Socket()) {
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 20_000);
        OutputStream out = s.getOutputStream();
        byte[] k = new byte[16];
        new java.util.Random().nextBytes(k);
        out.write(("GET " + path + " HTTP/1.1\r\nHost: " + authority + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(k) + "\r\nSec-WebSocket-Version: 13\r\nOrigin: " + origin
            + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
          int c = in.read();
          if (c < 0) break;
          head.append((char) c);
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) {
          closed = true;
          end = "refused: " + head.toString().split("\r\n")[0];
          return;
        }
        byte[] small = new byte[21];
        while (true) {
          long left = until - System.currentTimeMillis();
          if (left <= 0) break;
          s.setSoTimeout((int) Math.max(1, left));
          int b0, b1;
          try {
            b0 = in.readUnsignedByte();
            s.setSoTimeout(20_000);
            b1 = in.readUnsignedByte();
          } catch (java.net.SocketTimeoutException e) {
            break;
          }
          int op = b0 & 0x0f;
          long len = b1 & 0x7f;
          if (len == 126) len = in.readUnsignedShort();
          else if (len == 127) len = in.readLong();
          if (op == 2) {
            int take = (int) Math.min(len, 21);
            in.readFully(small, 0, take);
            in.skipNBytes(len - take);
            if (first < 0) first = (System.nanoTime() - t0) / 1e9;
            boolean isKey = !codec.equals("h264") || len > 20 && small[20] == 1;
            if (firstKey == null) firstKey = isKey;
            frames++;
            bytes += len;
            if (isKey) keys++;
            if (n == ts.length) {
              ts = Arrays.copyOf(ts, n * 2);
              key = Arrays.copyOf(key, n * 2);
            }
            long t = java.nio.ByteBuffer.wrap(small, 8, 8).getLong();
            ts[n] = t;
            key[n++] = isKey;
            ALL.add(t);
            if (!slow) {
              synchronized (out) {
                out.write(ACK);
              }
            } else {
              LATER.schedule(() -> {
                try {
                  synchronized (out) {
                    out.write(ACK);
                  }
                } catch (IOException e) {
                  // gone
                }
              }, slowMs, TimeUnit.MILLISECONDS);
            }
          } else {
            byte[] p = in.readNBytes((int) len);
            if (op == 1) {
              String text = new String(p, StandardCharsets.UTF_8);
              if (text.startsWith("c ")) codec = text.substring(2);
              else if (text.startsWith("x ")) end = text.substring(2);
              else if (text.startsWith("d {\"viewers\":")) told = Integer.parseInt(text.replaceAll("\\D", ""));
            } else if (op == 9 && p.length < 126) {
              synchronized (out) {
                out.write(masked(0xA, p));
              }
            } else if (op == 8) {
              closed = true;
              break;
            }
          }
        }
      } catch (IOException e) {
        closed = true;
        if (end.isEmpty()) end = "io: " + e.getMessage();
      } finally {
        done.countDown();
      }
    }
  }

  public static void main(String[] a) throws Exception {
    String host = a[0], path = a[2], authority = a[3], origin = a[4];
    int port = Integer.parseInt(a[1]), viewers = Integer.parseInt(a[5]), slow = Integer.parseInt(a[6]), slowMs = Integer.parseInt(a[7]);
    long start = Long.parseLong(a[8]), until = Long.parseLong(a[9]);
    double seconds = Double.parseDouble(a[10]);
    CountDownLatch done = new CountDownLatch(viewers);
    List<Viewer> all = new ArrayList<>();
    for (int i = 0; i < viewers; i++) all.add(new Viewer(i < slow, host, port, path, authority, origin, slowMs, until, done));
    Thread.sleep(Math.max(0, start - System.currentTimeMillis()));
    // Two milliseconds apart: a thousand in two seconds, as people follow a link, not one burst of connections.
    for (Viewer v : all) {
      Thread.ofVirtual().start(v);
      Thread.sleep(2);
    }
    done.await(until - System.currentTimeMillis() + 30_000, TimeUnit.MILLISECONDS);
    LATER.shutdownNow();
    // The order of frames is that of the viewer that got the most (a frame's time is not it: a picture sent again on
    // request carries the time it was sent, which may be later than the next picture's own). What another viewer
    // misses between two of its frames must end at a keyframe.
    Viewer ref = all.get(0);
    for (Viewer v : all) if (v.n > ref.n) ref = v;
    java.util.HashMap<Long, Integer> order = new java.util.HashMap<>();
    for (int j = 0; j < ref.n; j++) order.put(ref.ts[j], j);
    long[] union = ALL.stream().mapToLong(Long::longValue).toArray();
    long lo = union.length == 0 ? 0 : union[union.length - 1] - (long) (seconds * 1e6);
    int sent = 0;
    for (long t : union) if (t >= lo) sent++;
    StringBuilder b = new StringBuilder("{\"sentFps\":").append(sent / seconds).append(",\"viewers\":[");
    for (int i = 0; i < all.size(); i++) {
      Viewer v = all.get(i);
      boolean broken = false;
      int last = -1, inWindow = 0;
      for (int j = 0; j < v.n; j++) {
        Integer at = order.get(v.ts[j]);
        if (v.ts[j] >= lo) inWindow++;
        if (at == null) {  // a frame the reference missed: nothing to compare the next with
          last = -1;
          continue;
        }
        if (last >= 0 && at != last && at != last + 1 && !v.key[j]) broken = true;
        last = at;
      }
      if (i > 0) b.append(',');
      b.append("{\"mode\":\"").append(v.slow ? "slow" : "fast").append("\",\"first\":").append(v.first).append(",\"frames\":").append(v.frames)
          .append(",\"bytes\":").append(v.bytes).append(",\"keys\":").append(v.keys).append(",\"first_key\":").append(v.firstKey == null ? "null" : v.firstKey.toString())
          .append(",\"broken\":").append(broken).append(",\"rate\":").append(inWindow / seconds).append(",\"told\":").append(v.told)
          .append(",\"closed\":").append(v.closed).append(",\"codec\":\"").append(v.codec).append("\",\"end\":\"")
          .append(v.end.replaceAll("[^A-Za-z0-9 :.,/()-]", "?")).append("\"}");
    }
    System.out.println(b.append("]}"));
    System.exit(0);
  }
}
