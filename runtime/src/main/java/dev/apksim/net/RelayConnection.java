package dev.apksim.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What `URL.openConnection` gives app code for `http://page.apksim.invalid/...` while the tab relay is on
 * ({@link TabRelay}): an HttpURLConnection whose request goes to the tab. No redirects are followed (the answer is the
 * tab's, as it gave it), and the read timeout applies to the wait for the tab's answer.
 */
final class RelayConnection extends HttpURLConnection {
  private TabRelay.Reply reply;
  private IOException failure;  // a request is sent once: a later accessor repeats this, never the request
  private InputStream stream;
  private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
  private final List<String[]> header = new ArrayList<>();  // [key or null, value]; index 0 is the status line

  RelayConnection(URL u) {
    super(u);
  }

  @Override
  public synchronized void connect() {
    snapshot();
    connected = true;
  }

  private List<String[]> requestHeaders;

  /** The request headers as they are before connecting (the base class refuses to list them afterwards). */
  private synchronized void snapshot() {
    if (requestHeaders != null) return;
    List<String[]> l = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : getRequestProperties().entrySet()) {
      if (e.getKey() != null) for (String v : e.getValue()) l.add(new String[] {e.getKey(), v});
    }
    requestHeaders = l;
  }

  @Override
  public void disconnect() {}

  @Override
  public boolean usingProxy() {
    return false;
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (reply != null) throw new IOException("Cannot write output after reading input.");
    if (!doOutput) throw new IOException("Cannot write to a URLConnection if doOutput=false - call setDoOutput(true)");
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (sent.size() + len > TabRelay.MAX_BODY) throw new IOException("the tab relay carries bodies up to " + TabRelay.MAX_BODY / 1024 + " KB");
        sent.write(b, off, len);
      }
    };
  }

  private synchronized void execute() throws IOException {
    if (failure != null) throw failure;
    if (reply != null) return;
    String m = method;
    if (doOutput && m.equals("GET")) m = "POST";
    snapshot();
    List<String[]> headers = requestHeaders;
    String file = url.getFile();
    String path = file == null || file.isEmpty() ? "/" : file.startsWith("/") ? file : "/" + file;
    byte[] body = sent.toByteArray();
    String method = m;
    CompletableFuture<TabRelay.Reply> f = new CompletableFuture<>();
    Thread.ofVirtual().name("apksim-relay-url").start(() -> f.complete(TabRelay.fetch(method, path, headers, body)));
    connected = true;
    try {
      reply = getReadTimeout() > 0 ? f.get(getReadTimeout(), TimeUnit.MILLISECONDS) : f.get();
    } catch (TimeoutException e) {
      failure = new SocketTimeoutException("Read timed out");
      throw failure;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failure = new java.io.InterruptedIOException();
      throw failure;
    } catch (ExecutionException e) {
      failure = new IOException(e.getCause());
      throw failure;
    }
    responseCode = reply.status();
    responseMessage = Net.statusText(reply.status());
    header.add(new String[] {null, "HTTP/1.1 " + reply.status() + " " + responseMessage});
    for (String[] h : reply.headers()) {
      if (!h[0].equalsIgnoreCase("content-length") && !h[0].equalsIgnoreCase("content-encoding")) header.add(h);
    }
    String length = String.valueOf(reply.body().length);
    if (method.equals("HEAD")) {  // the tab's length describes the GET's body
      for (String[] h : reply.headers()) if (h[0].equalsIgnoreCase("content-length")) length = h[1];
    }
    header.add(new String[] {"Content-Length", length});
  }

  @Override
  public int getResponseCode() throws IOException {
    execute();
    return responseCode;
  }

  @Override
  public String getResponseMessage() throws IOException {
    execute();
    return responseMessage;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    execute();
    if (responseCode >= 400) {
      if (responseCode == 404 || responseCode == 410) throw new FileNotFoundException(url.toString());
      throw new IOException("Server returned HTTP response code: " + responseCode + " for URL: " + url);
    }
    if (stream == null) stream = new ByteArrayInputStream(method.equals("HEAD") ? new byte[0] : reply.body());
    return stream;
  }

  @Override
  public InputStream getErrorStream() {
    if (reply == null || responseCode < 400) return null;
    return new ByteArrayInputStream(reply.body());
  }

  private void ready() {
    try {
      execute();
    } catch (IOException e) {
      // like HttpURLConnection: a header lookup after a failed request finds none
    }
  }

  @Override
  public String getHeaderField(String name) {
    ready();
    if (name == null) return header.isEmpty() ? null : header.get(0)[1];
    for (int i = header.size() - 1; i > 0; i--) if (name.equalsIgnoreCase(header.get(i)[0])) return header.get(i)[1];
    return null;
  }

  @Override
  public String getHeaderField(int n) {
    ready();
    return n >= 0 && n < header.size() ? header.get(n)[1] : null;
  }

  @Override
  public String getHeaderFieldKey(int n) {
    ready();
    return n >= 0 && n < header.size() ? header.get(n)[0] : null;
  }

  @Override
  public Map<String, List<String>> getHeaderFields() {
    ready();
    Map<String, List<String>> m = new LinkedHashMap<>();
    for (String[] h : header) m.computeIfAbsent(h[0], k -> new ArrayList<>()).add(h[1]);
    Map<String, List<String>> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(k, Collections.unmodifiableList(v)));
    return Collections.unmodifiableMap(out);
  }
}
