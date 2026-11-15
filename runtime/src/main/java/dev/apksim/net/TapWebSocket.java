package dev.apksim.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The tap's WebSocket decoder (RFC 6455), after a 101: frames from each direction put together into messages, the app's
 * unmasked, permessage-deflate (RFC 7692) inflated, each kept with its time on the upgrade's entry
 * (`_webSocketMessages` in a recording, as Chrome writes them). The newest {@link #MAX_MESSAGES} messages, {@link #MAX_BYTES}
 * in all, {@link #MAX_MESSAGE} of each.
 */
final class TapWebSocket {
  static final int MAX_MESSAGES = 2000, MAX_BYTES = 4 << 20, MAX_MESSAGE = 1 << 20;
  /** A compressed message is inflated whole (the next one may refer back into it), up to this much. */
  private static final int MAX_INFLATE_INPUT = 8 << 20;

  /** Read by a save on another thread while messages arrive: synchronized, and copied to be written. */
  final List<Recording.WsMessage> messages = Collections.synchronizedList(new ArrayList<>());
  private long kept;
  private final Side sent, received;

  TapWebSocket(String extensions) {
    String x = extensions == null ? "" : extensions.toLowerCase(Locale.ROOT);
    boolean deflate = x.contains("permessage-deflate");
    sent = new Side(true, deflate, x.contains("client_no_context_takeover"));
    received = new Side(false, deflate, x.contains("server_no_context_takeover"));
  }

  private static final class Side {
    final boolean out, deflate, fresh;
    final Tap.Held held = new Tap.Held();
    /** The frame in progress: what is left of its payload, its opcode and flags, its mask and how far into it. */
    long left;
    int opcode, at;
    boolean fin, masked, inFrame;
    final byte[] mask = new byte[4];
    ByteArrayOutputStream frame;
    /** The data message in progress (frames up to a FIN). */
    ByteArrayOutputStream message;
    int messageOpcode;
    boolean compressed, cut;
    long size;
    Inflater inflater;
    boolean inflateLost;

    Side(boolean out, boolean deflate, boolean fresh) {
      this.out = out;
      this.deflate = deflate;
      this.fresh = fresh;
    }
  }

  void bytes(boolean out, byte[] d, int from, int len, long now) throws IOException {
    Side s = out ? sent : received;
    byte[] a;
    int off, end;
    if (s.held.n == 0) {
      a = d;
      off = from;
      end = from + len;
    } else {
      s.held.add(java.util.Arrays.copyOfRange(d, from, from + len));
      a = s.held.a;
      off = 0;
      end = s.held.n;
    }
    while (off < end) {
      if (!s.inFrame) {
        if (end - off < 2) break;
        int b0 = a[off] & 0xff, b1 = a[off + 1] & 0xff;
        int n = b1 & 0x7f;
        int head = 2 + (n == 126 ? 2 : n == 127 ? 8 : 0) + ((b1 & 0x80) != 0 ? 4 : 0);
        if (end - off < head) break;
        long size = n;
        int p = off + 2;
        if (n == 126) {
          size = (a[p] & 0xff) << 8 | (a[p + 1] & 0xff);
          p += 2;
        } else if (n == 127) {
          size = 0;
          for (int i = 0; i < 8; i++) size = size << 8 | (a[p + i] & 0xff);
          p += 8;
        }
        if (size < 0) throw new IOException("a WebSocket frame of a negative length");
        s.masked = (b1 & 0x80) != 0;
        if (s.masked) System.arraycopy(a, p, s.mask, 0, 4);
        s.fin = (b0 & 0x80) != 0;
        s.opcode = b0 & 0x0f;
        if (s.opcode != 0 && s.opcode < 8) {  // a new data message
          s.message = new ByteArrayOutputStream();
          s.messageOpcode = s.opcode;
          s.compressed = s.deflate && (b0 & 0x40) != 0;
          s.cut = false;
          s.size = 0;
        }
        s.left = size;
        s.at = 0;
        s.inFrame = true;
        s.frame = s.opcode >= 8 ? new ByteArrayOutputStream() : null;
        off += head;
        if (size > 0) continue;
      }
      int n = (int) Math.min(s.left, end - off);
      if (n > 0) {
        byte[] part = java.util.Arrays.copyOfRange(a, off, off + n);
        if (s.masked) for (int i = 0; i < n; i++) part[i] ^= s.mask[(s.at + i) & 3];
        s.at += n;
        off += n;
        s.left -= n;
        if (s.frame != null) {
          if (s.frame.size() < 1024) s.frame.write(part, 0, Math.min(n, 1024 - s.frame.size()));
        } else if (s.message != null) {
          s.size += n;
          int room = (s.compressed ? MAX_INFLATE_INPUT : MAX_MESSAGE) - s.message.size();
          if (n > room) s.cut = true;
          if (room > 0) s.message.write(part, 0, Math.min(n, room));
        }
      }
      if (s.left > 0) break;
      s.inFrame = false;
      if (s.frame != null) {
        add(s.out, now, s.opcode, s.frame.toByteArray(), s.frame.size(), false);  // close, ping, pong
        s.frame = null;
      } else if (s.fin && s.message != null) {
        message(s, now);
      }
    }
    s.held.keep(a, off, end);
  }

  private void message(Side s, long now) {
    byte[] data = s.message.toByteArray();
    s.message = null;
    boolean cut = s.cut;
    if (s.compressed) {
      if (cut || s.inflateLost) {
        // Part of the stream was not kept, and later messages may refer back into it: none can be inflated from here.
        s.inflateLost = !s.fresh;
        add(s.out, now, s.messageOpcode, new byte[0], s.size, true);
        return;
      }
      try {
        if (s.inflater == null) s.inflater = new Inflater(true);
        ByteArrayOutputStream text = new ByteArrayOutputStream(Math.max(64, data.length * 3));
        byte[] buf = new byte[8192];
        for (byte[] in : new byte[][] {data, {0, 0, (byte) 0xff, (byte) 0xff}}) {
          s.inflater.setInput(in);
          for (int n; (n = s.inflater.inflate(buf)) > 0; ) {
            if (text.size() < MAX_MESSAGE) text.write(buf, 0, Math.min(n, MAX_MESSAGE - text.size()));
            else cut = true;
          }
        }
        data = text.toByteArray();
        if (s.fresh) s.inflater.reset();
      } catch (DataFormatException e) {
        s.inflateLost = true;
        add(s.out, now, s.messageOpcode, new byte[0], s.size, true);
        return;
      }
    }
    add(s.out, now, s.messageOpcode, data, s.compressed ? data.length : s.size, cut);
  }

  private void add(boolean out, long now, int opcode, byte[] data, long size, boolean cut) {
    messages.add(new Recording.WsMessage(out, Tap.wall(now), opcode, data, size, cut));
    kept += data.length + 64;
    synchronized (messages) {
      while (messages.size() > MAX_MESSAGES || (kept > MAX_BYTES && messages.size() > 1)) kept -= messages.remove(0).data().length + 64;
    }
  }
}
