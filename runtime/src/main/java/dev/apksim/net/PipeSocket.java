package dev.apksim.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;

/**
 * The network engine's end of an in-memory link ({@link RelaySocket#connectEngine}): reads what the app wrote to `in`,
 * writes the answer to `out`. Only the calls the engine makes on a client socket are real.
 */
final class PipeSocket extends Socket {
  private final TabRelay.Pipe in, out;
  private volatile boolean closed;
  private volatile int soTimeout;

  PipeSocket(TabRelay.Pipe in, TabRelay.Pipe out) {
    this.in = in;
    this.out = out;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (closed) throw new SocketException("Socket is closed");
    return TabRelay.reader(in, () -> soTimeout);
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (closed) throw new SocketException("Socket is closed");
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new SocketException("Socket closed");
        out.write(b, off, len);
      }
    };
  }

  @Override
  public synchronized void close() {
    closed = true;
    in.close();
    out.close();
  }

  @Override
  public void shutdownOutput() {
    out.close();
  }

  @Override
  public void shutdownInput() {
    in.discard();
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public boolean isConnected() {
    return true;
  }

  @Override
  public synchronized void setSoTimeout(int ms) {
    soTimeout = ms;
  }

  @Override
  public synchronized int getSoTimeout() {
    return soTimeout;
  }

  @Override
  public void setTcpNoDelay(boolean on) {}

  @Override
  public String toString() {
    return "PipeSocket";
  }
}
