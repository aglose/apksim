package dev.apksim.composeapp

import android.net.ssl.SSLSockets
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The platform's TLS sockets are supported by android.net.ssl.SSLSockets and take ALPN protocols, as OkHttp's Android
 * platform needs to offer HTTP/2 (without it every connection is HTTP/1.1).
 */
@RunWith(AndroidJUnit4::class)
class TlsAlpnTest {
  @Test
  fun platformTlsSocketsAreSupportedAndTakeAlpn() {
    val socket = SSLContext.getDefault().socketFactory.createSocket() as SSLSocket
    socket.use {
      assertTrue(SSLSockets.isSupportedSocket(it))
      SSLSockets.setUseSessionTickets(it, true)
      val params = it.sslParameters
      params.applicationProtocols = arrayOf("h2", "http/1.1")
      it.sslParameters = params
      assertArrayEquals(arrayOf("h2", "http/1.1"), it.sslParameters.applicationProtocols)
    }
  }
}
