package dev.apksim.video;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * scripts/check-media-probe.sh: {@link MediaProbe} on synthetic files of known durations (none 1000 ms, the fallback),
 * a non-MP3 that holds MP3-like bytes, and {@link ShadowSimMediaPlayer}'s descriptor probe on an interrupted thread,
 * which must leave the app's descriptor open and the flag set. Exits 1 on any failure.
 */
public final class MediaProbeCheck {
  private static int failures;

  public static void main(String[] args) throws Exception {
    expect("wav 2.5 s", wav(2500), 2500);
    expect("flac 3.2 s", flac(44100, 141120), 3200);
    expect("mp4 2.75 s, moov after 400 KB of mdat", mp4(2750, 400_000), 2750);
    expect("ogg vorbis 1.5 s", ogg(48000, 72000), 1500);
    expect("mp3 CBR, 60 frames at 128 kbps", mp3(60), 60 * 417 * 8 / 128);
    expect("adts, 43 frames at 44.1 kHz", adts(43), 43 * 1024 * 1000 / 44100);
    expect("mp3-like bytes in noise fall back", noise(), MediaProbe.UNKNOWN_MS);
    expect("garbage falls back", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, MediaProbe.UNKNOWN_MS);
    interruptedProbe();
    if (failures > 0) {
      System.out.println(failures + " media probe check(s) failed");
      System.exit(1);
    }
    System.out.println("media probe checks passed");
  }

  private static void expect(String name, byte[] file, int ms) {
    int got = MediaProbe.durationMs(new MediaProbe.Source() {
      @Override public long length() { return file.length; }
      @Override public int read(long pos, byte[] b, int off, int len) {
        if (pos >= file.length) return -1;
        int n = (int) Math.min(len, file.length - pos);
        System.arraycopy(file, (int) pos, b, off, n);
        return n;
      }
    });
    System.out.println((got == ms ? "ok   " : "FAIL ") + name + ": " + got + " ms (expected " + ms + ")");
    if (got != ms) failures++;
  }

  private static void interruptedProbe() throws Exception {
    File f = File.createTempFile("apksim-probe", ".wav");
    f.deleteOnExit();
    byte[] data = wav(2500);
    try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
    try (FileInputStream app = new FileInputStream(f)) {
      FileDescriptor fd = app.getFD();
      Method m = ShadowSimMediaPlayer.class.getDeclaredMethod("fdDuration", FileDescriptor.class, long.class, long.class);
      m.setAccessible(true);
      Thread.currentThread().interrupt();
      int ms = (int) m.invoke(null, fd, 0L, (long) data.length);
      boolean flag = Thread.interrupted();
      boolean open = fd.valid() && app.read() == 'R';
      boolean ok = ms == 2500 && flag && open;
      System.out.println((ok ? "ok   " : "FAIL ") + "probe on an interrupted thread: " + ms + " ms, flag kept " + flag + ", app's descriptor open " + open);
      if (!ok) failures++;
    }
  }

  private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

  private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

  static byte[] wav(int ms) {
    int rate = 44100, bytes = rate * 2 * ms / 1000;
    ByteBuffer b = le(44 + bytes);
    b.put(ascii("RIFF")).putInt(36 + bytes).put(ascii("WAVE"));
    b.put(ascii("fmt ")).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
        .putShort((short) 2).putShort((short) 16);
    b.put(ascii("data")).putInt(bytes);
    return b.array();
  }

  private static byte[] flac(int rate, long samples) {
    byte[] b = new byte[42 + 100];
    System.arraycopy(ascii("fLaC"), 0, b, 0, 4);
    b[4] = (byte) 0x80; b[7] = 34;  // last metadata block, STREAMINFO, 34 bytes
    // bytes 18..21: sample rate (20 bits), channels-1 (3), bits-1 (5), total samples' top 4 bits; 22..25 the rest.
    long packed = ((long) rate << 44) | (1L << 41) | (15L << 36) | samples;
    for (int i = 0; i < 8; i++) b[18 + i] = (byte) (packed >>> (56 - 8 * i));
    return b;
  }

  private static byte[] box(String type, byte[] body) {
    ByteBuffer b = ByteBuffer.allocate(8 + body.length);
    b.putInt(8 + body.length).put(ascii(type)).put(body);
    return b.array();
  }

  private static byte[] mp4(int ms, int mdat) throws Exception {
    ByteBuffer mvhd = ByteBuffer.allocate(100);
    mvhd.putInt(0).putInt(0).putInt(0).putInt(1000).putInt(ms);  // version/flags, created, modified, timescale, duration
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(box("ftyp", ascii("M4A \0\0\0\0isomM4A ")));
    out.write(box("mdat", new byte[mdat]));
    out.write(box("moov", box("mvhd", mvhd.array())));
    return out.toByteArray();
  }

  private static byte[] oggPage(long granule, byte[] body) {
    ByteBuffer b = le(28 + body.length);
    b.put(ascii("OggS")).put((byte) 0).put((byte) 0).putLong(granule).putInt(1).putInt(0).putInt(0)
        .put((byte) 1).put((byte) body.length).put(body);
    return b.array();
  }

  private static byte[] ogg(int rate, long samples) throws Exception {
    ByteBuffer ident = le(30);
    ident.put((byte) 1).put(ascii("vorbis")).putInt(0).put((byte) 2).putInt(rate);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(oggPage(0, ident.array()));
    out.write(oggPage(samples / 2, new byte[200]));
    out.write(oggPage(samples, new byte[200]));
    return out.toByteArray();
  }

  private static byte[] mp3(int frames) {
    byte[] b = new byte[frames * 417];  // MPEG-1 Layer III, 128 kbps, 44.1 kHz, no padding: 417 bytes a frame
    for (int f = 0; f < frames; f++) {
      b[f * 417] = (byte) 0xff; b[f * 417 + 1] = (byte) 0xfb; b[f * 417 + 2] = (byte) 0x90; b[f * 417 + 3] = 0x40;
    }
    return b;
  }

  private static byte[] adts(int frames) {
    int length = 200;
    byte[] b = new byte[frames * length];
    new Random(488).nextBytes(b);
    for (int f = 0; f < frames; f++) {
      int i = f * length;  // AAC-LC, 44.1 kHz (index 4), stereo, one raw data block
      b[i] = (byte) 0xff; b[i + 1] = (byte) 0xf1; b[i + 2] = (byte) (0x40 | (4 << 2)); b[i + 3] = (byte) 0x80;
      b[i + 4] = (byte) (length >> 3); b[i + 5] = (byte) ((length & 7) << 5 | 0x1f); b[i + 6] = (byte) 0xfc;
    }
    return b;
  }

  private static byte[] noise() {
    byte[] b = new byte[6000];
    new Random(488).nextBytes(b);
    b[0] = 0x12;  // no container's magic
    b[1500] = (byte) 0xff; b[1501] = (byte) 0xfb; b[1502] = (byte) 0x90; b[1503] = 0x40;  // one MP3-looking header
    return b;
  }
}
