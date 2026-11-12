package dev.apksim.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;

/**
 * The socket app code gets from a plain `SocketFactory.createSocket()` while the tab relay is on (docs/beam.md "The tab
 * relay"): until it connects it is its factory's own socket's stand-in, so an app that connects it to any other address
 * gets that socket, connected (and unwrapped, wherever TLS is layered on it: {@link Net#createSocket}). Connected to
 * {@link TabRelay#HOST} it is a pair of in-memory pipes ended by {@link TabRelay#serveConnection}: no OS socket, so
 * the beam sandbox's refusal of loopback does not reach it.
 */
final class RelaySocket extends Socket {
  private final Socket real;
  private TabRelay.Pipe up, down;  // the app's bytes to the relay, and back
  private volatile boolean engineLink;  // the pipes end at the network engine, not the tab relay
  private volatile boolean relay, closed, shutIn, shutOut;  // relay is set last, once both pipes exist
  private volatile int soTimeout;
  private boolean tcpNoDelay, keepAlive;
  private InetSocketAddress remote;

  RelaySocket(Socket real) {
    this.real = real;
    try {  // what the factory configured stays
      soTimeout = real.getSoTimeout();
      tcpNoDelay = real.getTcpNoDelay();
      keepAlive = real.getKeepAlive();
    } catch (SocketException ignored) {
      // defaults
    }
  }

  /** The factory's own socket (what TLS is layered on), when this was not connected to the relay. */
  Socket real() {
    return real;
  }

  /** Connected to the tab relay (not to the network engine). */
  boolean isRelay() {
    return relay && !engineLink;
  }

  boolean isEngineLink() {
    return engineLink;
  }

  /** `Net.connect` for this socket: the relay's host in memory, anything else through Net on the real socket. */
  void connectTo(SocketAddress a, int timeout) throws IOException {
    Net.connect(this, a, timeout);
  }

  /**
   * Connects to the network engine in memory (the beam sandbox denies loopback, where the engine listens): the engine
   * serves the other end of a pair of pipes. `dest` is where the app was going.
   */
  synchronized void connectEngine(NetEngine engine, InetSocketAddress dest) throws IOException {
    if (relay || closed) throw new SocketException(closed ? "Socket is closed" : "already connected");
    remote = dest;
    up = new TabRelay.Pipe(256 * 1024, true);
    down = new TabRelay.Pipe(256 * 1024, true);
    engineLink = true;
    relay = true;
    engine.serveInMemory(up, down);
  }

  synchronized boolean relayConnect(SocketAddress a) throws IOException {
    if (relay || closed) throw new SocketException(closed ? "Socket is closed" : "already connected");
    if (a instanceof InetSocketAddress isa && TabRelay.matches(isa)) {
      if (!TabRelay.on) throw new ConnectException("the tab relay is off");
      remote = isa;
      up = new TabRelay.Pipe(TabRelay.MAX_BODY * 2L);
      down = new TabRelay.Pipe(Long.MAX_VALUE);
      relay = true;
      TabRelay.start(up, down);
      return true;
    }
    return false;
  }

  @Override
  public void connect(SocketAddress a) throws IOException {
    connectTo(a, 0);
  }

  @Override
  public void connect(SocketAddress a, int timeout) throws IOException {
    connectTo(a, timeout);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (!relay) return real.getInputStream();
    if (closed) throw new SocketException("Socket is closed");
    if (shutIn) throw new SocketException("Socket input is shutdown");
    InputStream in = TabRelay.reader(down, () -> soTimeout);
    return new InputStream() {
      @Override
      public int read() throws IOException {
        return in.read();
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        return in.read(b, off, len);
      }

      @Override
      public int available() {
        return 0;
      }

      @Override
      public void close() throws IOException {
        RelaySocket.this.close();
      }
    };
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (!relay) return real.getOutputStream();
    if (closed) throw new SocketException("Socket is closed");
    if (shutOut) throw new SocketException("Socket output is shutdown");
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (closed || shutOut) throw new SocketException("Socket closed");
        up.write(b, off, len);
      }

      @Override
      public void close() throws IOException {
        RelaySocket.this.close();
      }
    };
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    if (relay) {
      up.close();
      down.close();
    }
    real.close();
  }

  @Override
  public void shutdownInput() throws IOException {
    if (!relay) {
      real.shutdownInput();
      return;
    }
    shutIn = true;
    down.discard();  // readers blocked or reading on see the end
  }

  @Override
  public void shutdownOutput() throws IOException {
    if (!relay) {
      real.shutdownOutput();
      return;
    }
    shutOut = true;
    up.close();
  }

  @Override
  public boolean isConnected() {
    return relay ? true : real.isConnected();
  }

  @Override
  public boolean isClosed() {
    return closed || (!relay && real.isClosed());
  }

  @Override
  public boolean isBound() {
    return relay ? true : real.isBound();
  }

  @Override
  public boolean isInputShutdown() {
    return relay ? shutIn : real.isInputShutdown();
  }

  @Override
  public boolean isOutputShutdown() {
    return relay ? shutOut : real.isOutputShutdown();
  }

  @Override
  public InetAddress getInetAddress() {
    return relay ? remote.getAddress() : real.getInetAddress();
  }

  @Override
  public int getPort() {
    return relay ? remote.getPort() : real.getPort();
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return relay ? remote : real.getRemoteSocketAddress();
  }

  @Override
  public InetAddress getLocalAddress() {
    return relay ? InetAddress.getLoopbackAddress() : real.getLocalAddress();
  }

  @Override
  public int getLocalPort() {
    return relay ? 0 : real.getLocalPort();
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return relay ? new InetSocketAddress(InetAddress.getLoopbackAddress(), 0) : real.getLocalSocketAddress();
  }

  @Override
  public void bind(SocketAddress a) throws IOException {
    if (!relay) real.bind(a);
  }

  @Override
  public SocketChannel getChannel() {
    return null;
  }

  @Override
  public synchronized void setSoTimeout(int ms) throws SocketException {
    if (ms < 0) throw new IllegalArgumentException("timeout < 0");
    soTimeout = ms;
    real.setSoTimeout(ms);
  }

  @Override
  public synchronized int getSoTimeout() {
    return soTimeout;
  }

  @Override
  public void setTcpNoDelay(boolean on) throws SocketException {
    tcpNoDelay = on;
    if (!relay) real.setTcpNoDelay(on);
  }

  @Override
  public boolean getTcpNoDelay() throws SocketException {
    return relay ? tcpNoDelay : real.getTcpNoDelay();
  }

  @Override
  public void setKeepAlive(boolean on) throws SocketException {
    keepAlive = on;
    if (!relay) real.setKeepAlive(on);
  }

  @Override
  public boolean getKeepAlive() throws SocketException {
    return relay ? keepAlive : real.getKeepAlive();
  }

  @Override
  public void setReuseAddress(boolean on) throws SocketException {
    real.setReuseAddress(on);
  }

  @Override
  public boolean getReuseAddress() throws SocketException {
    return real.getReuseAddress();
  }

  @Override
  public void setSendBufferSize(int n) throws SocketException {
    real.setSendBufferSize(n);
  }

  @Override
  public int getSendBufferSize() throws SocketException {
    return real.getSendBufferSize();
  }

  @Override
  public void setReceiveBufferSize(int n) throws SocketException {
    real.setReceiveBufferSize(n);
  }

  @Override
  public int getReceiveBufferSize() throws SocketException {
    return real.getReceiveBufferSize();
  }

  @Override
  public void setSoLinger(boolean on, int linger) throws SocketException {
    real.setSoLinger(on, linger);
  }

  @Override
  public int getSoLinger() throws SocketException {
    return real.getSoLinger();
  }

  @Override
  public void setTrafficClass(int tc) throws SocketException {
    real.setTrafficClass(tc);
  }

  @Override
  public int getTrafficClass() throws SocketException {
    return real.getTrafficClass();
  }

  @Override
  public void setOOBInline(boolean on) throws SocketException {
    real.setOOBInline(on);
  }

  @Override
  public boolean getOOBInline() throws SocketException {
    return real.getOOBInline();
  }

  @Override
  public void sendUrgentData(int data) throws IOException {
    real.sendUrgentData(data);
  }

  @Override
  public String toString() {
    return relay ? "RelaySocket[" + (engineLink ? "engine" : TabRelay.HOST) + "]" : real.toString();
  }
}
