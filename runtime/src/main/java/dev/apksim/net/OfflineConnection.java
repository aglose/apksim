package dev.apksim.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

/**
 * What URL.openConnection gives while offline. Opening connects nothing, so the decision waits for the first use: still
 * offline, it fails as on a device (a host name fails its lookup; a numeric address cannot be reached) without reaching
 * the JDK, whose cached keep-alive sockets (opened before going offline, unseen by Net) or a numeric address (no lookup
 * to fail) could otherwise still carry a request; back online by then, it becomes the connection Net would open now,
 * with everything the app set on it (method, headers, timeouts, streaming mode, TLS settings), and goes on through it.
 */
final class OfflineConnection {
  private OfflineConnection() {}

  static HttpURLConnection of(URL u, Proxy proxy) {
    return u.getProtocol().equals("https") ? new Https(u, proxy) : new Http(u, proxy);
  }

  static IOException failure(URL u) {
    String host = u.getHost();
    boolean numeric = !host.isEmpty() && (Character.isDigit(host.charAt(0)) || host.startsWith("["));
    if (numeric) {
      int port = u.getPort() > 0 ? u.getPort() : u.getDefaultPort();
      return new ConnectException("failed to connect to /" + host + " (port " + port + "): connect failed: ENETUNREACH (Network is unreachable)");
    }
    return new UnknownHostException("Unable to resolve host \"" + host + "\": No address associated with hostname");
  }

  /** What both kinds share: the app's settings, and the real connection once there is one. */
  private interface Deferred {
    HttpURLConnection self();

    HttpURLConnection real();

    void real(HttpURLConnection c);

    Proxy proxy();

    /** The real connection, opened now (online again), or the offline failure. */
    default HttpURLConnection resolve() throws IOException {
      if (real() != null) return real();
      HttpURLConnection me = self();
      if (Net.conditions.offline()) throw failure(me.getURL());
      HttpURLConnection c = (HttpURLConnection) (proxy() == null ? Net.openConnection(me.getURL()) : Net.openConnection(me.getURL(), proxy()));
      c.setRequestMethod(me.getRequestMethod());
      c.setDoInput(me.getDoInput());
      c.setDoOutput(me.getDoOutput());
      c.setUseCaches(me.getUseCaches());
      c.setIfModifiedSince(me.getIfModifiedSince());
      c.setAllowUserInteraction(me.getAllowUserInteraction());
      c.setConnectTimeout(me.getConnectTimeout());
      c.setReadTimeout(me.getReadTimeout());
      c.setInstanceFollowRedirects(me.getInstanceFollowRedirects());
      for (Map.Entry<String, List<String>> h : me.getRequestProperties().entrySet()) {
        for (String v : h.getValue()) c.addRequestProperty(h.getKey(), v);
      }
      streaming(c);
      real(c);
      return c;
    }

    void streaming(HttpURLConnection c);
  }

  private static final class Http extends HttpURLConnection implements Deferred {
    private final Proxy proxy;
    private HttpURLConnection real;

    Http(URL u, Proxy proxy) {
      super(u);
      this.proxy = proxy;
    }

    @Override public HttpURLConnection self() { return this; }
    @Override public HttpURLConnection real() { return real; }
    @Override public void real(HttpURLConnection c) { real = c; }
    @Override public Proxy proxy() { return proxy; }

    @Override
    public void streaming(HttpURLConnection c) {
      if (fixedContentLengthLong >= 0) c.setFixedLengthStreamingMode(fixedContentLengthLong);
      else if (fixedContentLength >= 0) c.setFixedLengthStreamingMode(fixedContentLength);
      else if (chunkLength > 0) c.setChunkedStreamingMode(chunkLength);
    }

    @Override public void connect() throws IOException { resolve().connect(); connected = true; }
    @Override public InputStream getInputStream() throws IOException { return resolve().getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { return resolve().getOutputStream(); }
    @Override public int getResponseCode() throws IOException { return resolve().getResponseCode(); }
    @Override public String getResponseMessage() throws IOException { return resolve().getResponseMessage(); }
    @Override public InputStream getErrorStream() { return real == null ? null : real.getErrorStream(); }
    @Override public String getHeaderField(String n) { return real == null ? null : real.getHeaderField(n); }
    @Override public String getHeaderField(int i) { return real == null ? null : real.getHeaderField(i); }
    @Override public String getHeaderFieldKey(int i) { return real == null ? null : real.getHeaderFieldKey(i); }
    @Override public Map<String, List<String>> getHeaderFields() { return real == null ? Map.of() : real.getHeaderFields(); }
    @Override public void disconnect() { if (real != null) real.disconnect(); }
    @Override public boolean usingProxy() { return real != null && real.usingProxy(); }
  }

  private static final class Https extends HttpsURLConnection implements Deferred {
    private final Proxy proxy;
    private HttpURLConnection real;
    private boolean factorySet, verifierSet;

    Https(URL u, Proxy proxy) {
      super(u);
      this.proxy = proxy;
    }

    @Override public HttpURLConnection self() { return this; }
    @Override public HttpURLConnection real() { return real; }
    @Override public void real(HttpURLConnection c) { real = c; }
    @Override public Proxy proxy() { return proxy; }

    @Override
    public void setSSLSocketFactory(SSLSocketFactory f) {
      super.setSSLSocketFactory(f);
      factorySet = true;
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier v) {
      super.setHostnameVerifier(v);
      verifierSet = true;
    }

    @Override
    public void streaming(HttpURLConnection c) {
      if (fixedContentLengthLong >= 0) c.setFixedLengthStreamingMode(fixedContentLengthLong);
      else if (fixedContentLength >= 0) c.setFixedLengthStreamingMode(fixedContentLength);
      else if (chunkLength > 0) c.setChunkedStreamingMode(chunkLength);
      // The app's own TLS settings, only those it made: the connection Net opens keeps its own otherwise.
      if (c instanceof HttpsURLConnection h) {
        if (factorySet) h.setSSLSocketFactory(getSSLSocketFactory());
        if (verifierSet) h.setHostnameVerifier(getHostnameVerifier());
      }
    }

    private HttpsURLConnection tls() {
      if (!(real instanceof HttpsURLConnection h)) throw new IllegalStateException("not connected");
      return h;
    }

    @Override public void connect() throws IOException { resolve().connect(); connected = true; }
    @Override public InputStream getInputStream() throws IOException { return resolve().getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { return resolve().getOutputStream(); }
    @Override public int getResponseCode() throws IOException { return resolve().getResponseCode(); }
    @Override public String getResponseMessage() throws IOException { return resolve().getResponseMessage(); }
    @Override public InputStream getErrorStream() { return real == null ? null : real.getErrorStream(); }
    @Override public String getHeaderField(String n) { return real == null ? null : real.getHeaderField(n); }
    @Override public String getHeaderField(int i) { return real == null ? null : real.getHeaderField(i); }
    @Override public String getHeaderFieldKey(int i) { return real == null ? null : real.getHeaderFieldKey(i); }
    @Override public Map<String, List<String>> getHeaderFields() { return real == null ? Map.of() : real.getHeaderFields(); }
    @Override public void disconnect() { if (real != null) real.disconnect(); }
    @Override public boolean usingProxy() { return real != null && real.usingProxy(); }
    @Override public String getCipherSuite() { return tls().getCipherSuite(); }
    @Override public Certificate[] getLocalCertificates() { return tls().getLocalCertificates(); }
    @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException { return tls().getServerCertificates(); }
  }
}
