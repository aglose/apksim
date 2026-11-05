package dev.apksim;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * android.net.ssl.SSLSockets for the sockets an app gets here, the JDK's. The framework's accepts only Conscrypt's,
 * so OkHttp's Android platform (OkHttp 5's Android build always uses it) set no ALPN on any socket: every connection
 * was HTTP/1.1, and a load balancer that serves only HTTP/2 answered 464 to every request (a suite's mock server
 * behind one). The JDK's SSLSocket does ALPN through SSLParameters.setApplicationProtocols, which is what OkHttp's
 * Android10SocketAdapter calls once SSLSockets says a socket is supported, and session tickets on its own.
 */
@Implements(android.net.ssl.SSLSockets.class)
public class ShadowSimSSLSockets {
  @Implementation
  protected static boolean isSupportedSocket(SSLSocket socket) {
    return socket != null;
  }

  @Implementation
  protected static void setUseSessionTickets(SSLSocket socket, boolean useSessionTickets) {
    if (socket == null) throw new IllegalArgumentException("Socket is not a supported socket");
    // The JDK's client sends the session ticket extension by default (jdk.tls.client.enableSessionTicketExtension).
  }

  @Implementation
  protected static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length)
      throws SSLException {
    if (socket == null) throw new IllegalArgumentException("Socket is not a supported socket");
    throw new SSLException("exporting keying material is not supported on this JDK's TLS");
  }
}
