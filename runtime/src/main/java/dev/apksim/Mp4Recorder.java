package dev.apksim;

import dev.apksim.video.H264Encoder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Test movies as H.264 in an MP4 (`--record-format mp4`, issue 454): the same frames and real-time pacing as
 * {@link MjpegRecorder} (a writer thread takes the newest picture at a fixed rate, repeating it while nothing changes),
 * encoded by the encoder `apksim serve`'s stream uses ({@link H264Encoder}: VideoToolbox, or OpenH264 in the Linux
 * image). The result plays in a browser: avc1, 4:2:0, constant frame rate, no reordering, and the `moov` box before the
 * `mdat` (fast start). Access units go to `PATH.part` as they are made, and the finished file is put together at the
 * end (ftyp, moov, mdat), so a process that dies mid-test leaves no .mp4, only the .part file, which is removed with
 * the movie's other leftovers by the next run.
 */
final class Mp4Recorder {
  private static final int KEY_SECONDS = 2;
  private static volatile Mp4Recorder active;
  /** Wall-clock time of the last movie's first frame (its t = 0), and its length, for the sidecar. */
  static volatile long lastStartEpochMs;
  static volatile long lastDurationMs;

  private final Path path;
  private final Path part;
  private final H264Encoder encoder;
  private final OutputStream out;
  private final int w;
  private final int h;
  private final int fps;
  private final Thread writer;
  private final long startNanos = System.nanoTime();
  private final List<int[]> samples = new ArrayList<>();  // size, key (1/0), duration in ticks
  private volatile boolean running = true;
  private byte[] latest;  // raw pixels, guarded by this
  private boolean fresh;
  private byte[] lastRaw;
  private byte[] sps, pps;
  private long dataBytes;
  private long ticks;  // encode attempts since the first frame: a frame's time is ticks / fps
  private boolean keyDue;  // a keyframe was asked for and the encoder gave none
  private long startEpochMs;

  private Mp4Recorder(String file, int w, int h, int fps) throws IOException {
    this.w = w;
    this.h = h;
    this.fps = fps;
    path = Paths.get(file);
    part = Paths.get(file + ".part");
    int kbps = (int) Math.max(300, Math.min(6000, (long) w * h * fps / 8000));
    encoder = H264Encoder.works() ? H264Encoder.open(w, h, kbps) : null;
    if (encoder == null || encoder.width != w || encoder.height != h) {
      if (encoder != null) encoder.close();
      throw new IOException("no H.264 encoder here");
    }
    try {
      out = new java.io.BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
    } catch (IOException | RuntimeException e) {
      encoder.close();
      throw e;
    }
    writer = new Thread(this::loop, "apksim-recorder");
    writer.setDaemon(true);
    writer.start();
  }

  /** Null on success, else why there is no encoder or file (the caller may fall back to an AVI). */
  static synchronized String start(String file, int w, int h, int fps) {
    if (active != null) return "already recording";
    try {
      lastStartEpochMs = 0;
      lastDurationMs = 0;
      active = new Mp4Recorder(file, w, h, fps);
      return null;
    } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
      return e.getMessage() != null ? e.getMessage() : e.toString();
    }
  }

  /** Finishes the movie; its length in ms, or -1 if nothing was recording or nothing was written. */
  static synchronized long stop() {
    Mp4Recorder r = active;
    if (r == null) return -1;
    active = null;
    return r.finish();
  }

  /** From the capture: the frame just drawn (BGRA, w x h). */
  static void frame(ByteBuffer pixels) {
    Mp4Recorder r = active;
    if (r == null) return;
    synchronized (r) {
      if (r.latest == null || r.latest.length != pixels.remaining()) r.latest = new byte[pixels.remaining()];
      pixels.duplicate().get(r.latest);
      r.fresh = true;
    }
  }

  private void loop() {
    long next = System.nanoTime();
    while (running) {
      next += 1_000_000_000L / fps;
      if (!step()) return;
      long wait = next - System.nanoTime();
      if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
    }
    boolean pending;
    synchronized (this) {
      pending = fresh;
    }
    if (pending) step();  // a short test's only frame still goes in
  }

  private boolean step() {
    synchronized (this) {
      if (fresh) {
        lastRaw = latest.clone();
        fresh = false;
      }
    }
    if (lastRaw == null || lastRaw.length != w * h * 4) return true;
    try {
      boolean key = keyDue || samples.isEmpty() || ticks % (fps * KEY_SECONDS) == 0;
      byte[] au = encoder.encode(lastRaw, w * 4, ticks * 1_000_000L / fps, key);
      ticks++;
      byte[] avcc = au == null || au.length < 2 ? null : toAvcc(au, 1);
      if (avcc == null) {
        // The encoder dropped this frame (rate control, or nothing changed): the picture on screen simply stays up
        // another tick. Before the first picture there is nothing to extend.
        if (!samples.isEmpty()) samples.get(samples.size() - 1)[2] += 1;
        else ticks = 0;
        keyDue = key;
        return true;
      }
      keyDue = false;
      if (sps == null || pps == null) throw new IOException("the first frame has no parameter sets");
      if (samples.isEmpty()) startEpochMs = System.currentTimeMillis();
      out.write(avcc);
      samples.add(new int[] {avcc.length, au[0] == 1 ? 1 : 0, 1});
      dataBytes += avcc.length;
      return true;
    } catch (IOException | RuntimeException e) {
      System.err.println("[apksim] recording stopped: " + e);
      return false;
    }
  }

  /** Annex B to length-prefixed NALs; keeps the first SPS and PPS for avcC and leaves them (and delimiters) out. */
  private byte[] toAvcc(byte[] a, int from) {
    ByteArrayOutputStream bo = new ByteArrayOutputStream(a.length);
    int i = from;
    int n = a.length;
    while (i + 3 <= n) {
      int sc = startCode(a, i);
      if (sc < 0) {
        i++;
        continue;
      }
      int begin = i + sc;
      int j = begin;
      while (j + 3 <= n && startCode(a, j) < 0) j++;
      int end = j + 3 <= n ? j : n;
      if (end > begin) {
        int type = a[begin] & 0x1f;
        byte[] nal = java.util.Arrays.copyOfRange(a, begin, end);
        if (type == 7) {
          if (sps == null) sps = nal;
        } else if (type == 8) {
          if (pps == null) pps = nal;
        } else if (type != 9) {
          bo.write(nal.length >>> 24);
          bo.write(nal.length >>> 16);
          bo.write(nal.length >>> 8);
          bo.write(nal.length);
          bo.write(nal, 0, nal.length);
        }
      }
      i = end;
    }
    return bo.size() == 0 ? null : bo.toByteArray();
  }

  /** Length of the start code at i (3 or 4), or -1. */
  private static int startCode(byte[] a, int i) {
    if (i + 3 <= a.length && a[i] == 0 && a[i + 1] == 0 && a[i + 2] == 1) return 3;
    if (i + 4 <= a.length && a[i] == 0 && a[i + 1] == 0 && a[i + 2] == 0 && a[i + 3] == 1) return 4;
    return -1;
  }

  private long finish() {
    running = false;
    java.util.concurrent.locks.LockSupport.unpark(writer);
    long ms = -1;
    try {
      writer.join(5000);
      if (writer.isAlive()) {
        // Still inside the encoder: closing it now would free what that thread is using. The encoder is left to it
        // (leaked) and the movie is not kept.
        System.err.println("[apksim] the recording's writer did not stop: no movie");
        return -1;
      }
      out.close();
      encoder.close();
      if (!samples.isEmpty() && sps != null && pps != null) {
        assemble();
        lastStartEpochMs = startEpochMs;
        long total = 0;
        for (int[] t : samples) total += t[2];
        lastDurationMs = total * 1000L / fps;
        ms = (System.nanoTime() - startNanos) / 1_000_000;
      }
    } catch (IOException | InterruptedException | RuntimeException e) {
      System.err.println("[apksim] could not finish the recording: " + e);
    } finally {
      try {
        Files.deleteIfExists(part);
      } catch (IOException e) {
        // left for the next run's clean-up
      }
    }
    return ms;
  }

  // ---------------------------------------------------------------- MP4 layout

  private void assemble() throws IOException {
    byte[] ftyp = box("ftyp", cat(ascii("isom"), u32(512), ascii("isomiso2avc1mp41")));
    byte[] moov = moov(0);
    long dataStart = ftyp.length + moov.length + 8;
    moov = moov(dataStart);
    Path tmp = Paths.get(path + ".tmp");
    try (OutputStream o = new java.io.BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
         InputStream in = Files.newInputStream(part)) {
      o.write(ftyp);
      o.write(moov);
      if (dataBytes + 8 > 0xffffffffL) throw new IOException("movie too large for this writer");
      o.write(u32((int) (dataBytes + 8)));
      o.write(ascii("mdat"));
      in.transferTo(o);
    }
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
  }

  private byte[] moov(long dataStart) throws IOException {
    int n = samples.size();
    int scale = fps * 1000;
    long total = 0;
    for (int[] t : samples) total += t[2];
    long dur = total * 1000;  // in media units: a tick is 1000 of fps*1000 a second
    long ms = total * 1000L / fps;
    byte[] mvhd = box("mvhd", cat(u32(0), u32(0), u32(0), u32(1000), u32((int) ms), u32(0x00010000), new byte[]{1, 0, 0, 0},
        new byte[8], matrix(), new byte[24], u32(2)));
    byte[] tkhd = box("tkhd", cat(u32(3), u32(0), u32(0), u32(1), u32(0), u32((int) ms), new byte[8], new byte[]{0, 0, 0, 0},
        new byte[]{0, 0, 0, 0}, matrix(), u32(w << 16), u32(h << 16)));
    byte[] mdhd = box("mdhd", cat(u32(0), u32(0), u32(0), u32(scale), u32((int) dur), new byte[]{0x55, (byte) 0xc4, 0, 0}));
    byte[] hdlr = box("hdlr", cat(u32(0), u32(0), ascii("vide"), new byte[12], ascii("VideoHandler"), new byte[]{0}));
    byte[] vmhd = box("vmhd", cat(u32(1), new byte[8]));
    byte[] dinf = box("dinf", box("dref", cat(u32(0), u32(1), box("url ", u32(1)))));
    byte[] avcC = box("avcC", cat(new byte[]{1, sps[1], sps[2], sps[3], (byte) 0xff, (byte) 0xe1}, u16(sps.length), sps,
        new byte[]{1}, u16(pps.length), pps));
    byte[] avc1 = box("avc1", cat(new byte[6], u16(1), new byte[16], u16(w), u16(h), u32(0x00480000), u32(0x00480000),
        u32(0), u16(1), new byte[32], u16(0x18), u16(0xffff), avcC));
    byte[] stsd = box("stsd", cat(u32(0), u32(1), avc1));
    ByteArrayOutputStream tt = new ByteArrayOutputStream();
    int runs = 0;
    for (int i = 0; i < n; ) {
      int j = i;
      while (j < n && samples.get(j)[2] == samples.get(i)[2]) j++;
      tt.write(u32(j - i));
      tt.write(u32(samples.get(i)[2] * 1000));
      runs++;
      i = j;
    }
    byte[] stts = box("stts", cat(u32(0), u32(runs), tt.toByteArray()));
    ByteArrayOutputStream ss = new ByteArrayOutputStream();
    int keys = 0;
    for (int[] s : samples) if (s[1] == 1) keys++;
    ss.write(u32(0));
    ss.write(u32(keys));
    for (int i = 0; i < n; i++) if (samples.get(i)[1] == 1) ss.write(u32(i + 1));
    byte[] stss = box("stss", ss.toByteArray());
    byte[] stsc = box("stsc", cat(u32(0), u32(1), u32(1), u32(n), u32(1)));
    ByteArrayOutputStream sz = new ByteArrayOutputStream();
    sz.write(u32(0));
    sz.write(u32(0));
    sz.write(u32(n));
    for (int[] s : samples) sz.write(u32(s[0]));
    byte[] stsz = box("stsz", sz.toByteArray());
    byte[] stco = box("stco", cat(u32(0), u32(1), u32((int) dataStart)));
    byte[] stbl = box("stbl", cat(stsd, stts, stss, stsc, stsz, stco));
    byte[] minf = box("minf", cat(vmhd, dinf, stbl));
    byte[] mdia = box("mdia", cat(mdhd, hdlr, minf));
    byte[] trak = box("trak", cat(tkhd, mdia));
    return box("moov", cat(mvhd, trak));
  }

  private static byte[] matrix() {
    return cat(u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0), u32(0), u32(0), u32(0x40000000));
  }

  private static byte[] box(String type, byte[] body) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(body.length + 8);
    DataOutputStream d = new DataOutputStream(b);
    d.writeInt(body.length + 8);
    d.write(ascii(type));
    d.write(body);
    return b.toByteArray();
  }

  private static byte[] cat(byte[]... parts) {
    int n = 0;
    for (byte[] p : parts) n += p.length;
    byte[] r = new byte[n];
    int at = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, r, at, p.length);
      at += p.length;
    }
    return r;
  }

  private static byte[] u32(int v) {
    return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
  }

  private static byte[] u16(int v) {
    return new byte[] {(byte) (v >>> 8), (byte) v};
  }

  private static byte[] ascii(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }
}
