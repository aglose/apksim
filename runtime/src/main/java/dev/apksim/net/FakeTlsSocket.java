package dev.apksim.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A TLS socket that does no TLS, over a connection to apksim's network engine (record and replay). Its handshake asks
 * the engine for the server's certificates (recorded, or fetched from the real server while recording) and reports
 * a TLS 1.3 session with them, so the client's hostname check and certificate pins see the real chain; its bytes
 * then reach the engine in plain text, where HTTP is readable. No ALPN protocol, so clients speak HTTP/1.1.
 */
final class FakeTlsSocket extends SSLSocket {
  private static String[] defaultSuites;
  private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  private final java.net.Socket raw;
  private final String host;
  private final int port;
  private final boolean autoClose;
  private final List<HandshakeCompletedListener> listeners = new CopyOnWriteArrayList<>();
  private String[] suites;
  private String[] protocols = PROTOCOLS.clone();
  private boolean clientMode = true;
  private boolean need, want, create = true;
  private volatile Session session;
  private volatile boolean closed;

  /** The app's own TLS (its factory: its trust manager, pins in it, client certificates), for the real handshake. */
  private final SSLSocketFactory appTls;

  FakeTlsSocket(java.net.Socket raw, String host, int port, boolean autoClose) {
    this(raw, host, port, autoClose, null);
  }

  FakeTlsSocket(java.net.Socket raw, String host, int port, boolean autoClose, SSLSocketFactory appTls) {
    this.appTls = appTls;
    this.raw = raw;
    this.host = host;
    this.port = port;
    this.autoClose = autoClose;
    this.suites = supported();
  }

  private static synchronized String[] supported() {
    if (defaultSuites == null) {
      try {
        defaultSuites = SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites();
      } catch (Exception e) {
        defaultSuites = new String[] {"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"};
      }
    }
    return defaultSuites.clone();
  }

  @Override
  public synchronized void startHandshake() throws IOException {
    if (session != null) return;
    if (closed) throw new SocketException("Socket is closed");
    OutputStream out = raw.getOutputStream();
    // The engine is in this process: the app's factory goes to it by a token, for the handshake with the server.
    String token = appTls == null ? "" : " " + Net.lendTls(appTls);
    out.write(("APKSIM-TLS " + host + token + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    String reply = Net.readLine(raw.getInputStream());
    if (reply == null) throw new SSLHandshakeException("Connection closed by peer");
    if (reply.startsWith("ERR ")) {
      IOException e = Net.exception(reply.substring(4), true);
      throw e instanceof javax.net.ssl.SSLException ? e : new SSLHandshakeException(e.getMessage());
    }
    List<X509Certificate> chain = new ArrayList<>();
    String certs = reply.length() > 3 ? reply.substring(3).trim() : "";
    if (!certs.isEmpty()) {
      try {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (String c : certs.split(",")) {
          chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(c))));
        }
      } catch (Exception e) {
        chain.clear();
      }
    }
    if (chain.isEmpty()) chain.add(new PlaceholderCertificate(host));
    session = new Session(chain.toArray(new X509Certificate[0]));
    HandshakeCompletedEvent ev = new HandshakeCompletedEvent(this, session);
    for (HandshakeCompletedListener l : listeners) l.handshakeCompleted(ev);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    startHandshake();
    return raw.getInputStream();
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    startHandshake();
    return raw.getOutputStream();
  }

  @Override
  public SSLSession getSession() {
    if (session == null) {
      try {
        startHandshake();
      } catch (IOException e) {
        return new Session(new X509Certificate[0]);
      }
    }
    return session;
  }

  @Override
  public SSLSession getHandshakeSession() {
    return null;
  }

  @Override
  public String getApplicationProtocol() {
    return "";
  }

  @Override
  public String getHandshakeApplicationProtocol() {
    return "";
  }

  @Override public String[] getSupportedCipherSuites() { return supported(); }
  @Override public String[] getEnabledCipherSuites() { return suites.clone(); }
  @Override public void setEnabledCipherSuites(String[] s) { suites = s.clone(); }
  @Override public String[] getSupportedProtocols() { return PROTOCOLS.clone(); }
  @Override public String[] getEnabledProtocols() { return protocols.clone(); }
  @Override public void setEnabledProtocols(String[] p) { protocols = p.clone(); }
  @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) { listeners.add(l); }
  @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) { listeners.remove(l); }
  @Override public void setUseClientMode(boolean m) { clientMode = m; }
  @Override public boolean getUseClientMode() { return clientMode; }
  @Override public void setNeedClientAuth(boolean n) { need = n; }
  @Override public boolean getNeedClientAuth() { return need; }
  @Override public void setWantClientAuth(boolean w) { want = w; }
  @Override public boolean getWantClientAuth() { return want; }
  @Override public void setEnableSessionCreation(boolean f) { create = f; }
  @Override public boolean getEnableSessionCreation() { return create; }

  // The connection itself is the raw socket's.
  @Override public void connect(SocketAddress a, int t) throws IOException { throw new SocketException("already connected"); }
  @Override public void bind(SocketAddress a) throws IOException { throw new SocketException("already bound"); }
  @Override public InetAddress getInetAddress() { return raw.getInetAddress(); }
  @Override public InetAddress getLocalAddress() { return raw.getLocalAddress(); }
  @Override public int getPort() { return raw.getPort(); }
  @Override public int getLocalPort() { return raw.getLocalPort(); }
  @Override public SocketAddress getRemoteSocketAddress() { return raw.getRemoteSocketAddress(); }
  @Override public SocketAddress getLocalSocketAddress() { return raw.getLocalSocketAddress(); }
  @Override public void setTcpNoDelay(boolean on) throws SocketException { raw.setTcpNoDelay(on); }
  @Override public boolean getTcpNoDelay() throws SocketException { return raw.getTcpNoDelay(); }
  @Override public void setSoLinger(boolean on, int l) throws SocketException { raw.setSoLinger(on, l); }
  @Override public int getSoLinger() throws SocketException { return raw.getSoLinger(); }
  @Override public void setSoTimeout(int t) throws SocketException { raw.setSoTimeout(t); }
  @Override public int getSoTimeout() throws SocketException { return raw.getSoTimeout(); }
  @Override public void setSendBufferSize(int s) throws SocketException { raw.setSendBufferSize(s); }
  @Override public int getSendBufferSize() throws SocketException { return raw.getSendBufferSize(); }
  @Override public void setReceiveBufferSize(int s) throws SocketException { raw.setReceiveBufferSize(s); }
  @Override public int getReceiveBufferSize() throws SocketException { return raw.getReceiveBufferSize(); }
  @Override public void setKeepAlive(boolean on) throws SocketException { raw.setKeepAlive(on); }
  @Override public boolean getKeepAlive() throws SocketException { return raw.getKeepAlive(); }
  @Override public void shutdownInput() throws IOException { throw new UnsupportedOperationException("shutdownInput on an SSLSocket"); }
  @Override public void shutdownOutput() throws IOException { throw new UnsupportedOperationException("shutdownOutput on an SSLSocket"); }
  @Override public boolean isConnected() { return raw.isConnected(); }
  @Override public boolean isBound() { return raw.isBound(); }
  @Override public boolean isClosed() { return closed || raw.isClosed(); }
  @Override public boolean isInputShutdown() { return raw.isInputShutdown(); }
  @Override public boolean isOutputShutdown() { return raw.isOutputShutdown(); }

  @Override
  public void close() throws IOException {
    closed = true;
    if (autoClose) raw.close();
  }

  @Override
  public String toString() {
    return "FakeTlsSocket[" + host + ":" + port + " over " + raw + "]";
  }

  /** The socket factory the JDK's HttpsURLConnection gets while recording or replaying (Net.openConnection). */
  static final SSLSocketFactory FACTORY = new SSLSocketFactory() {
    @Override public String[] getDefaultCipherSuites() { return supported(); }
    @Override public String[] getSupportedCipherSuites() { return supported(); }

    @Override
    public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) {
      return new FakeTlsSocket(s, host, port, autoClose);
    }

    @Override
    public java.net.Socket createSocket(String host, int port) throws IOException {
      java.net.Socket s = new java.net.Socket();
      Net.connect(s, new java.net.InetSocketAddress(host, port), 0);
      return new FakeTlsSocket(s, host, port, true);
    }

    @Override
    public java.net.Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
      return createSocket(host, port);
    }

    @Override
    public java.net.Socket createSocket(InetAddress a, int port) throws IOException {
      return createSocket(a.getHostName(), port);
    }

    @Override
    public java.net.Socket createSocket(InetAddress a, int port, InetAddress local, int localPort) throws IOException {
      return createSocket(a.getHostName(), port);
    }
  };

  private final class Session implements SSLSession {
    private final X509Certificate[] chain;
    private final long created = System.currentTimeMillis();
    private final Map<String, Object> values = new HashMap<>();

    Session(X509Certificate[] chain) {
      this.chain = chain;
    }

    @Override public byte[] getId() { return new byte[32]; }
    @Override public SSLSessionContext getSessionContext() { return null; }
    @Override public long getCreationTime() { return created; }
    @Override public long getLastAccessedTime() { return created; }
    @Override public void invalidate() {}
    @Override public boolean isValid() { return true; }
    @Override public void putValue(String n, Object v) { values.put(n, v); }
    @Override public Object getValue(String n) { return values.get(n); }
    @Override public void removeValue(String n) { values.remove(n); }
    @Override public String[] getValueNames() { return values.keySet().toArray(new String[0]); }

    @Override
    public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
      if (chain.length == 0) throw new SSLPeerUnverifiedException("peer not authenticated");
      return chain.clone();
    }

    @Override public Certificate[] getLocalCertificates() { return null; }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
      if (chain.length == 0) throw new SSLPeerUnverifiedException("peer not authenticated");
      return chain[0].getSubjectX500Principal();
    }

    @Override public Principal getLocalPrincipal() { return null; }
    @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
    @Override public String getProtocol() { return "TLSv1.3"; }
    @Override public String getPeerHost() { return host; }
    @Override public int getPeerPort() { return port; }
    @Override public int getPacketBufferSize() { return 16709; }
    @Override public int getApplicationBufferSize() { return 16384; }
  }
}
