package dev.apksim.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;

/**
 * What URL.openConnection gives while offline: a connection that fails when it is used, as on a device (a host name
 * fails its lookup; a numeric address cannot be reached). It never reaches the JDK's implementation, so neither the
 * JDK's cached keep-alive sockets (opened before going offline, and unseen by Net) nor a numeric address that needs
 * no lookup can carry a request out.
 */
final class OfflineConnection {
  private OfflineConnection() {}

  static HttpURLConnection of(URL u) {
    return u.getProtocol().equals("https") ? new Https(u) : new Http(u);
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

  private static final class Http extends HttpURLConnection {
    Http(URL u) {
      super(u);
    }

    @Override public void connect() throws IOException { throw failure(url); }
    @Override public InputStream getInputStream() throws IOException { throw failure(url); }
    @Override public OutputStream getOutputStream() throws IOException { throw failure(url); }
    @Override public InputStream getErrorStream() { return null; }
    @Override public void disconnect() {}
    @Override public boolean usingProxy() { return false; }
  }

  private static final class Https extends HttpsURLConnection {
    Https(URL u) {
      super(u);
    }

    @Override public void connect() throws IOException { throw failure(url); }
    @Override public InputStream getInputStream() throws IOException { throw failure(url); }
    @Override public OutputStream getOutputStream() throws IOException { throw failure(url); }
    @Override public InputStream getErrorStream() { return null; }
    @Override public void disconnect() {}
    @Override public boolean usingProxy() { return false; }
    @Override public String getCipherSuite() { throw new IllegalStateException("not connected"); }
    @Override public Certificate[] getLocalCertificates() { throw new IllegalStateException("not connected"); }
    @Override public Certificate[] getServerCertificates() { throw new IllegalStateException("not connected"); }
  }
}
