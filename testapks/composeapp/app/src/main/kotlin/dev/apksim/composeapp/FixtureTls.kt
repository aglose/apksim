package dev.apksim.composeapp

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS that trusts one certificate only: the fixture server's (testapks/composeapp/fixture), made fresh at each of its
 * starts and handed to a screen in its deep link's `cert=` parameter (DER, base64url). Nothing else is trusted, and
 * hostnames are still checked against the certificate's names, so a connection through apksim must reach that very
 * server with its own TLS.
 */
class FixtureTls(b64: String) {
  val certificate: X509Certificate = CertificateFactory.getInstance("X.509")
    .generateCertificate(ByteArrayInputStream(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))) as X509Certificate
  val trustManager: X509TrustManager
  val socketFactory: SSLSocketFactory

  init {
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      setCertificateEntry("fixture", certificate)
    }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    socketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
  }

  companion object {
    fun from(cert: String?): FixtureTls? = cert?.takeIf { it.isNotBlank() }?.let { FixtureTls(it) }
  }
}

/** One check's outcome as the protocol and media screens show it, a line each: "NAME: ok|unsupported|FAIL DETAIL". */
data class CheckResult(val name: String, val status: String, val detail: String) {
  val line: String get() = "$name: $status${if (detail.isEmpty()) "" else " $detail"}"

  companion object {
    fun ok(name: String, detail: String = "") = CheckResult(name, "ok", detail)
    fun unsupported(name: String, detail: String) = CheckResult(name, "unsupported", detail)
    fun fail(name: String, detail: String) = CheckResult(name, "FAIL", detail)
  }
}

/** "PREFIX: N ok, N unsupported, N failed" for a list of results, and "running" while some are still to come. */
fun summarize(prefix: String, results: List<CheckResult>, done: Boolean): String {
  val ok = results.count { it.status == "ok" }
  val unsupported = results.count { it.status == "unsupported" }
  val failed = results.count { it.status == "FAIL" }
  return "$prefix: ${if (done) "done" else "running"}, $ok ok, $unsupported unsupported, $failed failed"
}
