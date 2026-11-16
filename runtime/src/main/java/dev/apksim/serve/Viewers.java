package dev.apksim.serve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * The view-only joiners of one session (`apksim serve --allow-share`, docs/serve.md "Shared sessions"). Each frame the
 * session reads from its instance is offered to every viewer as well as to the session's own tab: the same bytes, no
 * second encode and no second stream from the instance, so a viewer costs the server a socket, two virtual threads
 * and at most a few frames of queue.
 *
 * A viewer never slows the session's tab or another viewer. Each has its own queue and its own window of frames in
 * flight (it acknowledges frames as the tab does); every write to its socket is made by its own sender thread. JPEG:
 * the newest frame replaces one still waiting. H.264: frames wait in order, and a viewer more than
 * {@link #MAX_WAITING} behind drops what waits and takes up again at the next keyframe, which is asked of the instance
 * at most once every {@link #KEY_INTERVAL_MS} for all viewers together, a viewer that keeps falling behind asking
 * only every two seconds (a keyframe costs every watcher its size): it sees a picture every second or two. A viewer that acknowledges nothing for
 * {@link #STALL_MS} is dropped.
 *
 * What a viewer may send: `ack`, `key` (its decoder lost its place) and `t` (clock). Anything else is refused: no
 * input, no device command, no file, shell or recording.
 */
final class Viewers {
  /** Frames in flight to a viewer, and their bytes past the first two. */
  static final int WINDOW = 4;
  static final long WINDOW_BYTES = 2 << 20;
  /** H.264 frames that may wait for a viewer before it drops to the next keyframe. */
  static final int MAX_WAITING = 8;
  static final long KEY_INTERVAL_MS = 1000;
  static final long STALL_MS = 30_000;
  private static final long PING_MS = 20_000;
  private static final byte[] NONE = new byte[0];
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

  /** The secret a join link carries (32 hex digits): whoever has it may watch this session, and nothing else. */
  final String key;
  private final String sessionId;
  private final int max;
  /** Asks the instance for a keyframe (nothing on a JPEG stream). */
  private final Runnable askKey;
  /** Told the number of viewers whenever one joins or leaves. */
  private final IntConsumer onCount;
  private final CopyOnWriteArrayList<Viewer> list = new CopyOnWriteArrayList<>();
  /** The stream's codec once the instance has answered: null before. */
  private volatile Boolean h264;
  /** The last JPEG frame, for a viewer that joins a still screen. */
  private volatile byte[] last;
  /** What the session told its tab that a viewer needs too (the app's name and device), sent to each as it joins. */
  private volatile String info;
  private boolean ended;  // guarded by this
  private long lastKeyAsk;  // guarded by this
  private boolean keyScheduled;  // guarded by this
  final AtomicLong joined = new AtomicLong(), framesSent = new AtomicLong(), bytesSent = new AtomicLong(),
      keyDrops = new AtomicLong(), keysAsked = new AtomicLong(), stalled = new AtomicLong(), fanoutNanos = new AtomicLong();

  Viewers(String sessionId, int max, Runnable askKey, IntConsumer onCount) {
    byte[] k = new byte[16];
    RANDOM.nextBytes(k);
    this.key = HexFormat.of().formatHex(k);
    this.sessionId = sessionId;
    this.max = max;
    this.askKey = askKey;
    this.onCount = onCount;
  }

  boolean keyMatches(String given) {
    return given != null && MessageDigest.isEqual(key.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.UTF_8));
  }

  int count() {
    return list.size();
  }

  /** The `i` message's part every viewer gets first (the app's name and device), as JSON fields. */
  void info(String fields) {
    info = fields;
  }

  // ---------------------------------------------------------------- from the session

  /** The instance answered which codec the stream is: every viewer is told before any frame. */
  void codec(boolean isH264) {
    h264 = isH264;
    for (Viewer v : list) v.text("c " + (isH264 ? "h264" : "jpeg"));
  }

  /**
   * A frame from the instance (20 bytes of header, then for H.264 a byte that is 1 on a keyframe). Called on the thread
   * that reads the instance's stream for the session's own tab, so it only hands the frame to the shards: offering a
   * frame to a viewer wakes its sender (~7 us), and a thousand viewers at 120 frames a second took that thread whole,
   * the tab's frame rate with it.
   */
  void frame(byte[] f) {
    Boolean codec = h264;
    if (codec == null) return;
    if (!codec) last = f;
    if (list.isEmpty()) return;
    for (Shard sh : shards) sh.put(f);
  }

  /** Viewers are spread over a few threads, each offering every frame to its own in order. */
  private final Shard[] shards = new Shard[Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2))];
  private final AtomicLong nextShard = new AtomicLong();
  {
    for (int i = 0; i < shards.length; i++) shards[i] = new Shard();
  }

  private final class Shard implements Runnable {
    /** Frames that may wait for this shard's thread; past it they go and its viewers take up again at a keyframe. */
    static final int MAX_QUEUE = 64;
    final CopyOnWriteArrayList<Viewer> members = new CopyOnWriteArrayList<>();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();  // guarded by this
    private boolean overflowed, running, stopped;  // guarded by this
    private Thread thread;  // guarded by this

    synchronized void put(byte[] f) {
      if (!running) return;
      if (queue.size() >= MAX_QUEUE) {
        queue.clear();
        overflowed = true;
      }
      queue.add(f);
      notify();
    }

    synchronized void add(Viewer v) {
      members.add(v);
      if (!running && !stopped) {  // a viewer that joins as the session ends starts no thread: nothing would stop it
        running = true;
        thread = Thread.ofVirtual().name("serve-viewer-shard-" + sessionId).start(this);
      }
    }

    synchronized void stop() {
      stopped = true;
      running = false;
      queue.clear();
      if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
      try {
        while (true) {
          byte[] f;
          boolean lost;
          synchronized (this) {
            while (queue.isEmpty()) wait();
            f = queue.poll();
            lost = overflowed;
            overflowed = false;
          }
          boolean video = Boolean.TRUE.equals(h264), isKey = !video || f.length > 20 && f[20] == 1;
          long now = System.currentTimeMillis(), t0 = System.nanoTime();
          boolean want = false;
          for (Viewer v : members) {
            if (lost && video) v.wantKey();  // frames were dropped before this one: only a keyframe carries on
            want |= v.offer(f, video, isKey, now);
          }
          if (want) requestKey();
          fanoutNanos.addAndGet(System.nanoTime() - t0);
        }
      } catch (InterruptedException e) {
        // the session ended
      }
    }
  }

  /** A line for every viewer (the session's progress before its first frame, its device). */
  void text(String line) {
    for (Viewer v : list) v.text(line);
  }

  /** Pings, and viewers that stopped acknowledging; from the timer every session shares, so nothing here blocks. */
  void tick() {
    long now = System.currentTimeMillis();
    for (Viewer v : list) {
      if (v.stalledSince(now) > STALL_MS) {
        if (v.bye("this viewer stopped receiving the screen: reload to watch again")) stalled.incrementAndGet();
        continue;
      }
      if (now - v.lastPing >= PING_MS && v.pinging.compareAndSet(false, true)) {
        v.lastPing = now;
        Thread.ofVirtual().name("serve-viewer-ping").start(() -> {
          try {
            v.ws.ping();
          } finally {
            v.pinging.set(false);
          }
        });
      }
    }
  }

  /** The session ended: every viewer is told why and its connection closed. */
  void end(String why) {
    synchronized (this) {
      if (ended) return;
      ended = true;
    }
    for (Shard sh : shards) sh.stop();
    for (Viewer v : list) v.bye(why);
  }

  String json() {
    return "{\"count\":" + list.size() + ",\"max\":" + max + ",\"joined\":" + joined + ",\"framesSent\":" + framesSent
        + ",\"mbSent\":" + (bytesSent.get() >> 20) + ",\"keyDrops\":" + keyDrops + ",\"keysAsked\":" + keysAsked
        + ",\"stalled\":" + stalled + ",\"fanoutMs\":" + fanoutNanos.get() / 1_000_000 + "}";
  }

  // ---------------------------------------------------------------- one viewer

  /**
   * Runs a viewer on the caller's thread (its connection's) until it leaves, is dropped or the session ends. A session
   * that is full or over says so and closes the connection.
   */
  void run(WebSocket ws) {
    Viewer v = new Viewer(ws);
    synchronized (this) {
      if (ended) {
        ws.sendText("x the session has ended");
        ws.close("ended");
        return;
      }
      if (list.size() >= max) {
        ws.sendText("x all " + max + " places to watch this session are taken");
        ws.close("full");
        return;
      }
      list.add(v);
    }
    Shard shard = shards[(int) (nextShard.getAndIncrement() % shards.length)];
    joined.incrementAndGet();
    Boolean codec = h264;
    String fields = info;
    v.text("i {\"id\":" + Http.q(sessionId) + ",\"viewer\":true" + (fields == null ? "" : "," + fields) + "}");
    if (codec != null) v.text("c " + (codec ? "h264" : "jpeg"));
    // In its shard before the keyframe is asked for: the frame that answers must find it there.
    shard.add(v);
    if (codec != null) {
      // The screen as it is: a still one sends nothing by itself. H.264 starts at a keyframe, asked for below.
      byte[] f = last;
      if (!codec && f != null) v.offerFirst(f);
      if (codec) requestKey();
    } else {
      v.text("p waiting for the app to start");
    }
    Thread sender = Thread.ofVirtual().name("serve-viewer-send-" + sessionId).start(v::send);
    changed();
    try {
      ws.readLoop(text -> onText(v, text));
    } finally {
      shard.members.remove(v);
      list.remove(v);
      v.gone = true;
      sender.interrupt();
      changed();
    }
  }

  private final AtomicBoolean telling = new AtomicBoolean();
  private volatile boolean countChanged;
  private int told = -1;  // the teller thread only

  /**
   * A viewer came or went: everyone hears the new count, from one thread at a time so that counts arrive in order, and
   * a moment later, so that a hundred joining together are one message to each rather than a hundred.
   */
  private void changed() {
    countChanged = true;
    if (!telling.compareAndSet(false, true)) return;
    Thread.ofVirtual().name("serve-viewer-count-" + sessionId).start(() -> {
      do {
        try {
          while (countChanged) {
            Thread.sleep(100);
            countChanged = false;
            int n = list.size();
            if (n == told) continue;
            told = n;
            for (Viewer v : list) v.text("d {\"viewers\":" + n + "}");
            onCount.accept(n);
          }
        } catch (InterruptedException e) {
          return;
        } finally {
          telling.set(false);
        }
        // One that changed between the last look and letting go is told by this thread too.
      } while (countChanged && telling.compareAndSet(false, true));
    });
  }

  private void onText(Viewer v, String text) {
    if (text.equals("ack")) {
      v.acked();
    } else if (text.equals("key")) {
      // Its decoder lost its place: nothing more until a keyframe. One a second from a viewer: each ask is a keyframe
      // for everyone, the tab included.
      long now = System.currentTimeMillis();
      if (now - v.lastKeyMessage < KEY_INTERVAL_MS) return;
      v.lastKeyMessage = now;
      v.wantKey();
      requestKey();
    } else if (text.startsWith("t ") && text.length() < 40) {
      java.time.Instant now = java.time.Instant.now();
      v.text(text + " " + (now.getEpochSecond() * 1000 + now.getNano() / 1_000_000.0));
    } else if (v.refusals++ < 8) {
      // Said a few times, then silently: a script sending input in a loop gets no stream of answers.
      v.text("r refused: this link only watches the session");
    }
  }

  /** A keyframe for the viewers waiting on one: now, or once {@link #KEY_INTERVAL_MS} have passed since the last ask. */
  private void requestKey() {
    long wait;
    synchronized (this) {
      if (keyScheduled || ended) return;
      long now = System.currentTimeMillis();
      wait = lastKeyAsk + KEY_INTERVAL_MS - now;
      if (wait <= 0) lastKeyAsk = now;
      else keyScheduled = true;
    }
    if (wait <= 0) {
      keysAsked.incrementAndGet();
      askKey.run();
      return;
    }
    Thread.ofVirtual().name("serve-viewer-key-" + sessionId).start(() -> {
      try {
        Thread.sleep(wait);
      } catch (InterruptedException e) {
        return;
      }
      synchronized (this) {
        keyScheduled = false;
        lastKeyAsk = System.currentTimeMillis();
      }
      // Still wanted: a keyframe someone else's ask brought meanwhile served them all.
      long now = System.currentTimeMillis();
      for (Viewer v : list) {
        if (v.wantsKey(now)) {
          keysAsked.incrementAndGet();
          askKey.run();
          return;
        }
      }
    });
  }

  private final class Viewer {
    final WebSocket ws;
    // Guarded by this viewer.
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final ArrayDeque<String> texts = new ArrayDeque<>();
    private int inFlight;
    private long inFlightBytes;
    private final ArrayDeque<Integer> flying = new ArrayDeque<>();
    private boolean needKey = true, offered;
    /** A viewer that keeps falling behind asks for its keyframe later: at once, then after 1 s, then every 2 s. */
    private long keyBackoffMs, keyNotBefore;
    private int sinceDrop;
    private long lastAck = System.currentTimeMillis();
    volatile boolean gone;
    volatile long lastPing = System.currentTimeMillis();
    final AtomicBoolean pinging = new AtomicBoolean(), leaving = new AtomicBoolean();
    int refusals;  // its reader thread only
    long lastKeyMessage;  // its reader thread only

    Viewer(WebSocket ws) {
      this.ws = ws;
    }

    /** Whether this viewer is left waiting for a keyframe it may ask for now. */
    synchronized boolean offer(byte[] f, boolean video, boolean isKey, long now) {
      if (gone) return false;
      if (!video) {
        frames.clear();
        frames.add(f);
        offered = true;
      } else if (isKey) {
        frames.clear();
        frames.add(f);
        needKey = false;
      } else if (needKey) {
        return now >= keyNotBefore;
      } else if (frames.size() >= MAX_WAITING) {
        frames.clear();
        needKey = true;
        keyDrops.incrementAndGet();
        // Twice in a short while: it cannot keep up at this rate, so it sees fewer keyframes rather than asking for more.
        keyBackoffMs = sinceDrop > 600 ? 0 : Math.min(2000, Math.max(1000, keyBackoffMs * 2));
        sinceDrop = 0;
        keyNotBefore = now + keyBackoffMs;
        return keyBackoffMs == 0;
      } else {
        frames.add(f);
        sinceDrop++;
      }
      notifyAll();
      return false;
    }

    /** The screen as it was when a JPEG viewer joined, unless its shard has given it a newer picture meanwhile. */
    synchronized void offerFirst(byte[] f) {
      if (gone || offered) return;
      frames.add(f);
      offered = true;
      notifyAll();
    }

    synchronized void wantKey() {
      frames.clear();
      needKey = true;
    }

    synchronized boolean wantsKey(long now) {
      return needKey && !gone && now >= keyNotBefore;
    }

    synchronized void text(String line) {
      if (texts.size() >= 64) texts.poll();
      texts.add(line);
      notifyAll();
    }

    synchronized void acked() {
      Integer len = flying.poll();
      if (len != null) {
        inFlight--;
        inFlightBytes -= len;
      }
      lastAck = System.currentTimeMillis();
      notifyAll();
    }

    /** How long this viewer has had frames in flight without acknowledging any, in ms; 0 while it keeps up. */
    synchronized long stalledSince(long now) {
      return inFlight == 0 ? 0 : now - lastAck;
    }

    private boolean mayFly(int len) {
      return inFlight < 2 || inFlight < WINDOW && inFlightBytes + len <= WINDOW_BYTES;
    }

    /** The viewer's sender: every text and frame to its socket, in order, so a socket that stops reading blocks only this. */
    void send() {
      try {
        while (!gone) {
          String line = null;
          byte[] f = null;
          synchronized (this) {
            while (texts.isEmpty() && (frames.isEmpty() || !mayFly(frames.peek().length))) wait();
            if (!texts.isEmpty()) {
              line = texts.poll();
            } else {
              f = frames.poll();
              if (inFlight == 0) lastAck = System.currentTimeMillis();  // the stall clock starts with the first unanswered frame
              inFlight++;
              inFlightBytes += f.length;
              flying.add(f.length);
            }
          }
          if (line != null) {
            ws.sendText(line);
            if (ws.closed()) return;
          } else {
            ws.sendBinary(NONE, f, 0, f.length);
            framesSent.incrementAndGet();
            bytesSent.addAndGet(f.length);
          }
        }
      } catch (IOException | InterruptedException e) {
        // the viewer or the session ended
      }
    }

    /**
     * Tells the viewer why and closes its connection; dropped after two seconds if it does not take the message. False
     * when it was already told.
     */
    boolean bye(String why) {
      if (!leaving.compareAndSet(false, true)) return false;
      gone = true;
      Thread.ofVirtual().name("serve-viewer-bye").start(() -> {
        Thread notice = Thread.ofVirtual().start(() -> {
          ws.sendText("x " + why);
          ws.close(why.length() > 60 ? why.substring(0, 60) : why);
        });
        try {
          if (!notice.join(java.time.Duration.ofSeconds(2))) ws.abort();
        } catch (InterruptedException e) {
          ws.abort();
        }
      });
      return true;
    }
  }
}
