package dev.apksim.video;

import java.io.IOException;

/**
 * The duration of a sound file read from its container, for {@link ShadowSimMediaPlayer}: WAV, MP3 (Xing/Info or CBR
 * estimate), ADTS AAC (frames counted), MP4/M4A (mvhd), Ogg Vorbis/Opus (last granule position) and FLAC (STREAMINFO). Reads only the file's
 * head and, for Ogg, its tail. Anything else, or a file that does not parse, is {@link #UNKNOWN_MS}.
 */
final class MediaProbe {
  static final int UNKNOWN_MS = 1000;

  /** Random access to the bytes of one data source. */
  interface Source {
    long length() throws IOException;
    /** Reads up to len bytes at pos; returns the count, or -1 at the end. */
    int read(long pos, byte[] b, int off, int len) throws IOException;
  }

  private MediaProbe() {}

  static int durationMs(Source s) {
    try {
      byte[] h = new byte[16];
      if (fill(s, 0, h) < 12) return UNKNOWN_MS;
      long ms = -1;
      if (eq(h, 0, "RIFF") && eq(h, 8, "WAVE")) ms = wav(s);
      else if (eq(h, 0, "fLaC")) ms = flac(s);
      else if (eq(h, 0, "OggS")) ms = ogg(s);
      else if (eq(h, 4, "ftyp")) ms = mp4(s);
      else if ((h[0] & 0xff) == 0xff && (h[1] & 0xf6) == 0xf0) ms = adts(s);
      else ms = mp3(s);
      return ms > 0 && ms < Integer.MAX_VALUE ? (int) ms : UNKNOWN_MS;
    } catch (IOException | RuntimeException e) {
      return UNKNOWN_MS;
    }
  }

  private static int fill(Source s, long pos, byte[] b) throws IOException {
    int n = 0;
    while (n < b.length) {
      int r = s.read(pos + n, b, n, b.length - n);
      if (r <= 0) break;
      n += r;
    }
    return n;
  }

  private static boolean eq(byte[] b, int off, String tag) {
    for (int i = 0; i < tag.length(); i++) if (off + i >= b.length || b[off + i] != (byte) tag.charAt(i)) return false;
    return true;
  }

  private static long le(byte[] b, int off, int n) {
    long v = 0;
    for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xff);
    return v;
  }

  private static long be(byte[] b, int off, int n) {
    long v = 0;
    for (int i = 0; i < n; i++) v = (v << 8) | (b[off + i] & 0xff);
    return v;
  }

  private static long wav(Source s) throws IOException {
    long len = s.length(), pos = 12, byteRate = 0;
    byte[] c = new byte[8];
    for (int guard = 0; guard < 64 && pos + 8 <= len; guard++) {
      if (fill(s, pos, c) < 8) return -1;
      long size = le(c, 4, 4);
      if (eq(c, 0, "fmt ")) {
        byte[] f = new byte[16];
        if (fill(s, pos + 8, f) < 16) return -1;
        byteRate = le(f, 8, 4);
      } else if (eq(c, 0, "data")) {
        if (byteRate <= 0) return -1;
        long bytes = size == 0 || size == 0xffffffffL || pos + 8 + size > len ? len - pos - 8 : size;
        return bytes * 1000 / byteRate;
      }
      pos += 8 + size + (size & 1);
    }
    return -1;
  }

  private static long flac(Source s) throws IOException {
    byte[] b = new byte[42];
    if (fill(s, 0, b) < 42) return -1;
    long rate = (be(b, 18, 3) >> 4);
    long total = ((b[21] & 0x0fL) << 32) | be(b, 22, 4);
    return rate > 0 ? total * 1000 / rate : -1;
  }

  private static long mp4(Source s) throws IOException {
    long len = s.length(), pos = 0, end = len;
    byte[] h = new byte[16];
    boolean inMoov = false;
    for (int guard = 0; guard < 256 && pos + 8 <= end; guard++) {
      if (fill(s, pos, h) < 8) return -1;
      long size = be(h, 0, 4);
      int header = 8;
      if (size == 1) { size = be(h, 8, 8); header = 16; }
      else if (size == 0) size = end - pos;
      if (size < header) return -1;
      if (eq(h, 4, "moov") && !inMoov) { inMoov = true; end = Math.min(end, pos + size); pos += header; continue; }
      if (eq(h, 4, "mvhd")) {
        byte[] m = new byte[32];
        if (fill(s, pos + header, m) < 32) return -1;
        boolean v1 = m[0] == 1;
        long scale = v1 ? be(m, 20, 4) : be(m, 12, 4);
        long dur = v1 ? be(m, 24, 8) : be(m, 16, 4);
        return scale > 0 ? dur * 1000 / scale : -1;
      }
      pos += size;
    }
    return -1;
  }

  private static long ogg(Source s) throws IOException {
    byte[] head = new byte[512];
    int n = fill(s, 0, head);
    long rate = 0, skip = 0;
    for (int i = 0; i + 16 < n; i++) {
      if (eq(head, i, "\u0001vorbis")) { rate = le(head, i + 12, 4); break; }
      if (eq(head, i, "OpusHead")) { rate = 48000; skip = le(head, i + 10, 2); break; }
    }
    if (rate <= 0) return -1;
    long len = s.length(), from = Math.max(0, len - 65536);
    byte[] tail = new byte[(int) (len - from)];
    int t = fill(s, from, tail);
    for (int i = t - 14; i >= 0; i--) {
      if (eq(tail, i, "OggS")) {
        long granule = le(tail, i + 6, 8);
        if (granule <= 0) return -1;
        return (granule - skip) * 1000 / rate;
      }
    }
    return -1;
  }

  private static final int[] ADTS_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

  /** ADTS AAC: its frames walked to the end, 1024 samples each at the first header's rate. */
  private static long adts(Source s) throws IOException {
    long len = s.length(), pos = 0, frames = 0;
    int rate = 0;
    byte[] h = new byte[7];
    while (pos + 7 <= len && frames < 1_000_000) {
      if (fill(s, pos, h) < 7 || (h[0] & 0xff) != 0xff || (h[1] & 0xf6) != 0xf0) break;
      int index = (h[2] >> 2) & 15;
      if (index >= ADTS_RATES.length) break;
      if (rate == 0) rate = ADTS_RATES[index];
      int length = ((h[3] & 3) << 11) | ((h[4] & 0xff) << 3) | ((h[5] & 0xe0) >> 5);
      if (length < 7) break;
      frames += 1 + (h[6] & 3);  // raw data blocks in this frame
      pos += length;
    }
    return rate > 0 && frames > 0 ? frames * 1024 * 1000 / rate : -1;
  }

  private static final int[][] BITRATES = {
      {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},   // V1 L1
      {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},      // V1 L2
      {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},       // V1 L3
      {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},      // V2 L1
      {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};          // V2 L2/L3
  private static final int[] RATES = {44100, 48000, 32000};

  /** {kbps, sample rate, samples per frame, frame length in bytes, layer} of a header at b[i], or null. */
  private static int[] frame(byte[] b, int i) {
    if (i + 4 > b.length || (b[i] & 0xff) != 0xff || (b[i + 1] & 0xe0) != 0xe0) return null;
    int version = (b[i + 1] >> 3) & 3, layer = (b[i + 1] >> 1) & 3, br = (b[i + 2] >> 4) & 15, sr = (b[i + 2] >> 2) & 3;
    if (version == 1 || layer == 0 || br == 0 || br == 15 || sr == 3) return null;
    boolean v1 = version == 3;
    int l = 4 - layer;  // 1..3
    int kbps = BITRATES[v1 ? l - 1 : (l == 1 ? 3 : 4)][br];
    int rate = RATES[sr] >> (version == 2 ? 1 : version == 0 ? 2 : 0);
    int samples = l == 1 ? 384 : (l == 3 && !v1 ? 576 : 1152);
    int padding = (b[i + 2] >> 1) & 1;
    int length = l == 1 ? (12 * kbps * 1000 / rate + padding) * 4 : samples / 8 * kbps * 1000 / rate + padding;
    return length < 4 ? null : new int[] {kbps, rate, samples, length, l};
  }

  private static long mp3(Source s) throws IOException {
    long len = s.length(), pos = 0;
    byte[] id = new byte[10];
    if (fill(s, 0, id) == 10 && eq(id, 0, "ID3")) {
      pos = 10 + ((id[6] & 0x7fL) << 21 | (id[7] & 0x7fL) << 14 | (id[8] & 0x7fL) << 7 | (id[9] & 0x7fL));
    }
    byte[] b = new byte[4096];
    int n = fill(s, pos, b);
    for (int i = 0; i + 4 < n; i++) {
      int[] f = frame(b, i);
      if (f == null) continue;
      // A frame must be followed by another where it ends: two bytes that merely look like a header (inside an ADTS
      // AAC stream, say) are not an MP3. A frame running past what was read is taken on its own.
      if (i + f[3] + 4 <= n && frame(b, i + f[3]) == null) continue;
      boolean v1 = ((b[i + 1] >> 3) & 3) == 3;
      int l = f[4], kbps = f[0], rate = f[1], samples = f[2];
      boolean mono = ((b[i + 3] >> 6) & 3) == 3;
      int side = v1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
      int tag = i + 4 + side;
      if (l == 3 && tag + 12 <= n && (eq(b, tag, "Xing") || eq(b, tag, "Info")) && (b[tag + 7] & 1) != 0) {
        long total = be(b, tag + 8, 4) * samples;
        int flags = b[tag + 7], off = tag + 12;
        if ((flags & 2) != 0) off += 4;    // byte count
        if ((flags & 4) != 0) off += 100;  // seek table
        if ((flags & 8) != 0) off += 4;    // quality
        // LAME's tag (also written by FFmpeg's encoder as "Lavc"/"Lavf"): encoder delay and padding, which a decoder
        // trims, at 21 bytes in, 12 bits each.
        if (off + 24 <= n && (eq(b, off, "LAME") || eq(b, off, "Lavc") || eq(b, off, "Lavf"))) {
          long delay = ((b[off + 21] & 0xffL) << 4) | ((b[off + 22] & 0xf0L) >> 4);
          long padding = ((b[off + 22] & 0x0fL) << 8) | (b[off + 23] & 0xffL);
          if (delay + padding < total) total -= delay + padding;
        }
        return total * 1000L / rate;
      }
      return (len - pos - i) * 8L / kbps;  // CBR estimate: bytes * 8 / (kbps * 1000) s
    }
    return -1;
  }
}
