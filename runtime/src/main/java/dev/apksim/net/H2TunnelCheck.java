package dev.apksim.net;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Checks that a client offering h2 gets the server's ALPN choice while the network engine captures (issue 367): a TLS server on
 * this machine's LAN address (loopback is never captured) that picks h2, reached through the engine with the inspector on.
 * One that offers nothing still gets none (HTTP/1.1, as before). Skips when the machine has no non-loopback address.
 * Run by scripts/check-h2-tunnel.sh; exits 1 on any failure.
 */
public final class H2TunnelCheck {
  private static int failures;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "ok   " : "FAIL ") + what);
    if (!ok) failures++;
  }

  public static void main(String[] args) throws Exception {
    InetAddress lan = null;
    for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      for (InetAddress a : Collections.list(ni.getInetAddresses())) {
        if (!a.isLoopbackAddress() && !a.isLinkLocalAddress() && a instanceof java.net.Inet4Address) lan = a;
      }
    }
    if (lan == null) {
      System.out.println("skip: no non-loopback address");
      return;
    }
    Path dir = Files.createTempDirectory("h2tunnel");
    Path ks = dir.resolve("ks.p12");
    new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-dname",
        "CN=fixture.test", "-keystore", ks.toString(), "-storepass", "changeit", "-storetype", "PKCS12", "-validity", "2")
        .redirectErrorStream(true).start().waitFor();
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(ks)) {
      store.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(store, "changeit".toCharArray());
    SSLContext server = SSLContext.getInstance("TLS");
    server.init(kmf.getKeyManagers(), null, null);
    SSLServerSocket ss = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0, 5, lan);
    SSLParameters sp = ss.getSSLParameters();
    sp.setApplicationProtocols(new String[] {"h2", "http/1.1"});
    ss.setSSLParameters(sp);
    Thread t = new Thread(() -> {
      while (true) {
        try {
          SSLSocket c = (SSLSocket) ss.accept();
          Thread.ofVirtual().start(() -> {
            try (c) {
              c.startHandshake();
              InputStream in = c.getInputStream();
              OutputStream out = c.getOutputStream();
              out.write(("proto=" + c.getApplicationProtocol() + "\n").getBytes());
              out.flush();
              in.read();
              out.write('k');
              out.flush();
            } catch (Exception ignored) {
            }
          });
        } catch (Exception e) {
          return;
        }
      }
    });
    t.setDaemon(true);
    t.start();
    SSLContext client = SSLContext.getInstance("TLS");
    client.init(null, new TrustManager[] {new X509TrustManager() {
      public void checkClientTrusted(X509Certificate[] c, String a) {}
      public void checkServerTrusted(X509Certificate[] c, String a) {}
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }}, null);

    System.setProperty("apksim.networkInspect", "true");
    Net.configure();
    check(Net.active && Inspector.on, "the engine captures with the inspector on");
    int port = ss.getLocalPort();
    for (int mode = 0; mode < 3; mode++) {
      boolean h2 = mode == 0;
      Socket plain = new Socket();
      Net.connect(plain, new InetSocketAddress(lan, port), 5000);
      SSLSocket s = (SSLSocket) Net.createSocket(client.getSocketFactory(), plain, "fixture.test", port, true);
      check(s instanceof FakeTlsSocket, "TLS over the engine's socket is the capturing one");
      if (h2) {
        SSLParameters p = s.getSSLParameters();
        p.setApplicationProtocols(new String[] {"h2"});
        s.setSSLParameters(p);
      } else if (mode == 1) {
        SSLParameters p = s.getSSLParameters();
        p.setApplicationProtocols(new String[] {"h2", "http/1.1"});
        s.setSSLParameters(p);
      }
      s.startHandshake();
      String proto = s.getApplicationProtocol();
      if (h2) {
        check("h2".equals(proto), "offering only h2: the server's choice, h2 (got '" + proto + "')");
        InputStream in = s.getInputStream();
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = in.read()) != '\n' && ch >= 0) line.append((char) ch);
        check(line.toString().equals("proto=h2"), "the server saw h2 on its side too (" + line + ")");
        s.getOutputStream().write('x');
        s.getOutputStream().flush();
        check(in.read() == 'k', "bytes flow both ways through the tunnel");
        check(Inspector.eventsForCheck().stream().anyMatch(e -> e.contains("CONNECT") && e.contains(":" + port)), "the inspector lists a tunnel row");
      } else {
        check("".equals(proto), (mode == 1 ? "offering h2 and http/1.1 (OkHttp's default)" : "offering nothing") + ": captured, no ALPN, HTTP/1.1 as before (got '" + proto + "')");
      }
      s.close();
    }
    System.exit(failures == 0 ? 0 : 1);
  }
}
