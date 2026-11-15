package dev.apksim.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.function.BiFunction;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * The JDK's own HTTPS client under the tap. App code's sockets are tapped where the app asks for their streams
 * ({@link Net#inputStream}), but `HttpsURLConnection` is JDK code: it reads its socket's streams itself, where no hook
 * reaches. So the factory Net gives such a connection ({@link Net.TrackingFactory}) hands the JDK this instead: the real
 * TLS socket with every call passed on, and streams that copy what passes. Only the JDK ever holds one; app code that
 * makes its own TLS sockets keeps the socket its factory made.
 */
final class TapSslSocket extends SSLSocket {
  private final SSLSocket s;
  private volatile Tap.Conn conn;
  private final String host;
  private final int port;

  /** `host` null: not connected yet (the JDK connects it itself, and the address says where). */
  TapSslSocket(SSLSocket s, String host, int port) {
    this.s = s;
    this.host = host;
    this.port = port;
    if (host != null) conn = Tap.open(s, host, port, -1);
  }

  private void connected(SocketAddress a, long ms) {
    if (conn == null && a instanceof InetSocketAddress isa) conn = Tap.open(s, isa.getHostString(), isa.getPort(), ms);
  }

  @Override
  public void connect(SocketAddress a) throws IOException {
    connect(a, 0);
  }

  @Override
  public void connect(SocketAddress a, int timeout) throws IOException {
    long t0 = System.nanoTime();
    try {
      s.connect(a, timeout);
    } catch (IOException e) {
      if (a instanceof InetSocketAddress isa) Tap.connectFailed(isa.getHostString(), isa.getPort(), e);
      throw e;
    }
    connected(a, (System.nanoTime() - t0) / 1_000_000);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    InputStream in = s.getInputStream();
    Tap.Conn c = conn;
    return c == null || c.dead ? in : new Tap.In(in, c);
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    OutputStream out = s.getOutputStream();
    Tap.Conn c = conn;
    return c == null || c.dead ? out : new Tap.Out(out, c);
  }

  @Override
  public void close() throws IOException {
    Tap.Conn c = conn;
    if (c != null) c.end(false, null);
    s.close();
  }

  // Everything else is the real socket's.
  @Override public String[] getSupportedCipherSuites() { return s.getSupportedCipherSuites(); }
  @Override public String[] getEnabledCipherSuites() { return s.getEnabledCipherSuites(); }
  @Override public void setEnabledCipherSuites(String[] suites) { s.setEnabledCipherSuites(suites); }
  @Override public String[] getSupportedProtocols() { return s.getSupportedProtocols(); }
  @Override public String[] getEnabledProtocols() { return s.getEnabledProtocols(); }
  @Override public void setEnabledProtocols(String[] protocols) { s.setEnabledProtocols(protocols); }
  @Override public SSLSession getSession() { return s.getSession(); }
  @Override public SSLSession getHandshakeSession() { return s.getHandshakeSession(); }
  @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) { s.addHandshakeCompletedListener(l); }
  @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) { s.removeHandshakeCompletedListener(l); }
  @Override public void startHandshake() throws IOException { s.startHandshake(); }
  @Override public void setUseClientMode(boolean mode) { s.setUseClientMode(mode); }
  @Override public boolean getUseClientMode() { return s.getUseClientMode(); }
  @Override public void setNeedClientAuth(boolean need) { s.setNeedClientAuth(need); }
  @Override public boolean getNeedClientAuth() { return s.getNeedClientAuth(); }
  @Override public void setWantClientAuth(boolean want) { s.setWantClientAuth(want); }
  @Override public boolean getWantClientAuth() { return s.getWantClientAuth(); }
  @Override public void setEnableSessionCreation(boolean flag) { s.setEnableSessionCreation(flag); }
  @Override public boolean getEnableSessionCreation() { return s.getEnableSessionCreation(); }
  @Override public SSLParameters getSSLParameters() { return s.getSSLParameters(); }
  @Override public void setSSLParameters(SSLParameters p) { s.setSSLParameters(p); }
  @Override public String getApplicationProtocol() { return s.getApplicationProtocol(); }
  @Override public String getHandshakeApplicationProtocol() { return s.getHandshakeApplicationProtocol(); }
  @Override public void setHandshakeApplicationProtocolSelector(BiFunction<SSLSocket, List<String>, String> f) { s.setHandshakeApplicationProtocolSelector(f); }
  @Override public BiFunction<SSLSocket, List<String>, String> getHandshakeApplicationProtocolSelector() { return s.getHandshakeApplicationProtocolSelector(); }

  @Override public void bind(SocketAddress a) throws IOException { s.bind(a); }
  @Override public InetAddress getInetAddress() { return s.getInetAddress(); }
  @Override public InetAddress getLocalAddress() { return s.getLocalAddress(); }
  @Override public int getPort() { return s.getPort(); }
  @Override public int getLocalPort() { return s.getLocalPort(); }
  @Override public SocketAddress getRemoteSocketAddress() { return s.getRemoteSocketAddress(); }
  @Override public SocketAddress getLocalSocketAddress() { return s.getLocalSocketAddress(); }
  @Override public SocketChannel getChannel() { return s.getChannel(); }
  @Override public void setTcpNoDelay(boolean on) throws SocketException { s.setTcpNoDelay(on); }
  @Override public boolean getTcpNoDelay() throws SocketException { return s.getTcpNoDelay(); }
  @Override public void setSoLinger(boolean on, int linger) throws SocketException { s.setSoLinger(on, linger); }
  @Override public int getSoLinger() throws SocketException { return s.getSoLinger(); }
  @Override public void sendUrgentData(int data) throws IOException { s.sendUrgentData(data); }
  @Override public void setOOBInline(boolean on) throws SocketException { s.setOOBInline(on); }
  @Override public boolean getOOBInline() throws SocketException { return s.getOOBInline(); }
  @Override public void setSoTimeout(int timeout) throws SocketException { s.setSoTimeout(timeout); }
  @Override public int getSoTimeout() throws SocketException { return s.getSoTimeout(); }
  @Override public void setSendBufferSize(int size) throws SocketException { s.setSendBufferSize(size); }
  @Override public int getSendBufferSize() throws SocketException { return s.getSendBufferSize(); }
  @Override public void setReceiveBufferSize(int size) throws SocketException { s.setReceiveBufferSize(size); }
  @Override public int getReceiveBufferSize() throws SocketException { return s.getReceiveBufferSize(); }
  @Override public void setKeepAlive(boolean on) throws SocketException { s.setKeepAlive(on); }
  @Override public boolean getKeepAlive() throws SocketException { return s.getKeepAlive(); }
  @Override public void setTrafficClass(int tc) throws SocketException { s.setTrafficClass(tc); }
  @Override public int getTrafficClass() throws SocketException { return s.getTrafficClass(); }
  @Override public void setReuseAddress(boolean on) throws SocketException { s.setReuseAddress(on); }
  @Override public boolean getReuseAddress() throws SocketException { return s.getReuseAddress(); }
  @Override public void shutdownInput() throws IOException { s.shutdownInput(); }
  @Override public void shutdownOutput() throws IOException { s.shutdownOutput(); }
  @Override public boolean isConnected() { return s.isConnected(); }
  @Override public boolean isBound() { return s.isBound(); }
  @Override public boolean isClosed() { return s.isClosed(); }
  @Override public boolean isInputShutdown() { return s.isInputShutdown(); }
  @Override public boolean isOutputShutdown() { return s.isOutputShutdown(); }
  @Override public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) { s.setPerformancePreferences(connectionTime, latency, bandwidth); }
  @Override public <T> java.net.Socket setOption(java.net.SocketOption<T> name, T value) throws IOException { s.setOption(name, value); return this; }
  @Override public <T> T getOption(java.net.SocketOption<T> name) throws IOException { return s.getOption(name); }
  @Override public java.util.Set<java.net.SocketOption<?>> supportedOptions() { return s.supportedOptions(); }

  @Override
  public String toString() {
    return "TapSslSocket[" + s + "]";
  }
}
