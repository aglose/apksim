package dev.apksim.serve;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** The server side of RFC 6455, enough for one page: text and binary messages, ping, close. */
final class WebSocket {
  private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int MAX_MESSAGE = 1 << 20;

  /** A message from the browser: text, or null when the connection closed. */
  interface Listener {
    void onText(String text);
  }

  private final java.net.Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private volatile boolean closed;
  private boolean closeSent;  // guarded by writing: a second close frame is a protocol error to the browser
  /** Held while a frame is written. A tab that stops reading blocks a write; closing then gives up on the lock. */
  private final java.util.concurrent.locks.ReentrantLock writing = new java.util.concurrent.locks.ReentrantLock();

  WebSocket(java.net.Socket socket, InputStream in, OutputStream out) {
    this.socket = socket;
    this.in = in;
    this.out = out;
  }

  static String acceptKey(String key) {
    try {
      byte[] sha = MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
      return Base64.getEncoder().encodeToString(sha);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  boolean closed() {
    return closed;
  }

  /** Reads messages until the browser closes the connection (or it breaks). */
  void readLoop(Listener listener) {
    ByteArrayOutputStream message = new ByteArrayOutputStream();
    int messageOp = 0;
    try {
      while (!closed) {
        int b0 = read();
        int b1 = read();
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) {
          len = (read() << 8) | read();
        } else if (len == 127) {
          len = 0;
          for (int i = 0; i < 8; i++) len = (len << 8) | read();
        }
        if (!masked || len > MAX_MESSAGE || len < 0) break;  // a browser always masks; anything else is not one
        byte[] mask = in.readNBytes(4);
        byte[] payload = in.readNBytes((int) len);
        if (mask.length < 4 || payload.length < len) break;
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        switch (op) {
          case 0x8 -> {
            sendClose(payload, Math.min(payload.length, 2));
            closed = true;
          }
          case 0x9 -> sendFrame(0xA, payload, 0, payload.length);
          case 0xA -> {
          }
          case 0x0, 0x1, 0x2 -> {
            if (op != 0) {
              messageOp = op;
              message.reset();
            }
            if (message.size() + payload.length > MAX_MESSAGE) {
              closed = true;
              break;
            }
            message.write(payload);
            if (fin && messageOp == 0x1) listener.onText(message.toString(StandardCharsets.UTF_8));
          }
          default -> closed = true;
        }
      }
    } catch (IOException e) {
      // the connection broke
    } finally {
      closed = true;
    }
  }

  private int read() throws IOException {
    int b = in.read();
    if (b < 0) throw new EOFException();
    return b;
  }

  /**
   * A ping (the browser answers with a pong by itself): sent every {@link Session#PING_MS} so proxies and load balancers
   * that drop idle WebSockets see traffic both ways, with no help from the client.
   */
  void ping() {
    try {
      sendFrame(0x9, new byte[0], 0, 0);
    } catch (IOException e) {
      closed = true;
    }
  }

  void sendText(String text) {
    byte[] b = text.getBytes(StandardCharsets.UTF_8);
    try {
      sendFrame(0x1, b, 0, b.length);
    } catch (IOException e) {
      closed = true;
    }
  }

  /** One binary message made of a header and a body, without copying them together. */
  void sendBinary(byte[] header, byte[] body, int bodyOffset, int bodyLength) throws IOException {
    writing.lock();
    try {
      if (closeSent) throw new IOException("closed");
      writeHeader(0x2, header.length + bodyLength);
      out.write(header);
      out.write(body, bodyOffset, bodyLength);
      out.flush();
    } finally {
      writing.unlock();
    }
  }

  /** Drops the connection without a close frame and without waiting for a write in progress (which it ends). */
  void abort() {
    closed = true;
    try {
      socket.close();
    } catch (IOException e) {
      // gone already
    }
  }

  void close(String reason) {
    if (closed) return;
    byte[] r = reason.getBytes(StandardCharsets.UTF_8);
    byte[] p = new byte[2 + Math.min(r.length, 120)];
    p[0] = (byte) (1000 >> 8);
    p[1] = (byte) (1000 & 0xff);
    System.arraycopy(r, 0, p, 2, p.length - 2);
    try {
      // A tab that stopped reading holds the lock in a blocked write: then just drop the connection.
      if (writing.tryLock(1, java.util.concurrent.TimeUnit.SECONDS)) {
        try {
          sendClose(p, p.length);
        } finally {
          writing.unlock();
        }
        // The reader stops now rather than when (if) the other end answers.
        socket.shutdownInput();
      } else {
        socket.close();
      }
    } catch (IOException e) {
      // gone already
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    closed = true;
  }

  private void sendClose(byte[] payload, int len) throws IOException {
    writing.lock();
    try {
      if (closeSent) return;
      closeSent = true;
      sendFrame(0x8, payload, 0, len);
    } finally {
      writing.unlock();
    }
  }

  private void sendFrame(int op, byte[] b, int off, int len) throws IOException {
    writing.lock();
    try {
      if (closeSent && op != 0x8) return;  // nothing after a close
      writeHeader(op, len);
      out.write(b, off, len);
      out.flush();
    } finally {
      writing.unlock();
    }
  }

  private void writeHeader(int op, long len) throws IOException {
    out.write(0x80 | op);
    if (len < 126) {
      out.write((int) len);
    } else if (len < 65536) {
      out.write(126);
      out.write((int) (len >> 8));
      out.write((int) len);
    } else {
      out.write(127);
      for (int i = 7; i >= 0; i--) out.write((int) (len >> (8 * i)));
    }
  }
}
