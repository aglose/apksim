package dev.apksim;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLinux;

/**
 * android.net.LocalServerSocket and LocalSocket (Unix domain sockets, usually in Linux's abstract namespace) for
 * code in this process, and for `adb forward tcp:N localabstract:NAME`. Robolectric has none: its LocalSocketImpl
 * fails at the first native call. A name is bound to a loopback TCP port that only this process knows; abstract,
 * reserved and file-system names share one table, since nothing else here is listening. A server's descriptor can be
 * polled (Os.poll) for a waiting connection, as the Android CLI's layout server does before each accept.
 */
public final class LocalSockets {
  private static final Map<String, Server> BOUND = new ConcurrentHashMap<>();
  private static final Map<FileDescriptor, Server> BY_FD = new ConcurrentHashMap<>();
  /** Local ports of connections made for adb forwards: their peer is the shell user, as adbd's are on a device. */
  private static final Set<Integer> FROM_ADB = ConcurrentHashMap.newKeySet();
  private static final int SHELL_UID = 2000;

  private LocalSockets() {}

  /** A bound name: a non-blocking loopback channel, with a selector for polling it. */
  static final class Server {
    final String name;
    final ServerSocketChannel channel;
    final Selector selector;
    final FileDescriptor fd = new FileDescriptor();

    Server(String name) throws IOException {
      this.name = name;
      channel = ServerSocketChannel.open();
      channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
      channel.configureBlocking(false);
      selector = Selector.open();
      channel.register(selector, SelectionKey.OP_ACCEPT);
    }

    int port() throws IOException {
      return ((InetSocketAddress) channel.getLocalAddress()).getPort();
    }

    /** Whether a connection is waiting, after waiting up to timeoutMs for one (-1: for ever, 0: not at all). */
    synchronized boolean ready(int timeoutMs) throws IOException {
      if (!channel.isOpen()) return false;
      int n = timeoutMs == 0 ? selector.selectNow() : selector.select(Math.max(0, timeoutMs));
      selector.selectedKeys().clear();
      return n > 0;
    }

    Socket accept() throws IOException {
      while (true) {
        SocketChannel c = channel.accept();
        if (c != null) {
          c.configureBlocking(true);
          Socket s = c.socket();
          s.setTcpNoDelay(true);
          return s;
        }
        if (!channel.isOpen()) throw new IOException("socket closed");
        ready(-1);
      }
    }

    void close() throws IOException {
      BOUND.remove(name, this);
      BY_FD.remove(fd);
      channel.close();
      selector.close();
    }
  }

  static Server listen(String name) throws IOException {
    Server s = new Server(name);
    if (BOUND.putIfAbsent(name, s) != null) {
      s.channel.close();
      s.selector.close();
      throw new IOException("Address already in use: " + name);
    }
    BY_FD.put(s.fd, s);
    return s;
  }

  /** A connection to the socket bound to NAME, or an IOException as connect() gives when nothing listens. */
  static Socket connect(String name, boolean fromAdb) throws IOException {
    Server s = BOUND.get(name);
    if (s == null || !s.channel.isOpen()) throw new IOException("Connection refused: no local socket named " + name);
    // Bound first, so the port is marked before the server can accept the connection and ask who it is from.
    Socket c = new Socket();
    c.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    int port = c.getLocalPort();
    if (fromAdb) FROM_ADB.add(port);
    try {
      c.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), s.port()));
    } catch (IOException e) {
      FROM_ADB.remove(port);
      c.close();
      throw e;
    }
    c.setTcpNoDelay(true);
    return c;
  }

  @Implements(LocalServerSocket.class)
  public static class ShadowSimLocalServerSocket {
    private Server server;

    @Implementation
    protected void __constructor__(String name) throws IOException {
      server = listen(name);
    }

    @Implementation
    protected void __constructor__(FileDescriptor fd) throws IOException {
      throw new IOException("apksim: a LocalServerSocket on an inherited descriptor is not supported");
    }

    @Implementation
    protected LocalSocketAddress getLocalSocketAddress() {
      return new LocalSocketAddress(server.name);
    }

    @Implementation
    protected LocalSocket accept() throws IOException {
      Socket c = server.accept();
      LocalSocket s = new LocalSocket();
      ShadowSimLocalSocket shadow = Shadow.extract(s);
      shadow.attach(c, FROM_ADB.remove(c.getPort()));
      return s;
    }

    @Implementation
    protected FileDescriptor getFileDescriptor() {
      return server.fd;
    }

    @Implementation
    protected void close() throws IOException {
      server.close();
    }
  }

  @Implements(LocalSocket.class)
  public static class ShadowSimLocalSocket {
    private Socket socket;
    private boolean shellPeer;
    private boolean closed;

    void attach(Socket s, boolean shell) {
      socket = s;
      shellPeer = shell;
    }

    @Implementation
    protected void connect(LocalSocketAddress endpoint) throws IOException {
      if (socket != null) throw new IOException("already connected");
      socket = LocalSockets.connect(endpoint.getName(), false);
    }

    @Implementation
    protected void connect(LocalSocketAddress endpoint, int timeout) throws IOException {
      connect(endpoint);
    }

    @Implementation
    protected void bind(LocalSocketAddress bindpoint) throws IOException {
      throw new IOException("apksim: binding a client LocalSocket is not supported");
    }

    private Socket open() throws IOException {
      if (socket == null) throw new IOException("socket not connected");
      return socket;
    }

    @Implementation
    protected InputStream getInputStream() throws IOException {
      return open().getInputStream();
    }

    @Implementation
    protected OutputStream getOutputStream() throws IOException {
      return open().getOutputStream();
    }

    /** adb's connections come from the shell user (2000), as through adbd; the app's own from the app. */
    @Implementation
    protected Credentials getPeerCredentials() {
      int uid = shellPeer ? SHELL_UID : android.os.Process.myUid();
      return new Credentials(android.os.Process.myPid(), uid, uid);
    }

    @Implementation
    protected void close() throws IOException {
      closed = true;
      if (socket != null) socket.close();
    }

    @Implementation
    protected void shutdownInput() throws IOException {
      open().shutdownInput();
    }

    @Implementation
    protected void shutdownOutput() throws IOException {
      open().shutdownOutput();
    }

    @Implementation
    protected boolean isConnected() {
      return socket != null && socket.isConnected();
    }

    @Implementation
    protected boolean isClosed() {
      return closed;
    }

    @Implementation
    protected boolean isBound() {
      return socket != null;
    }

    @Implementation
    protected boolean isInputShutdown() {
      return socket != null && socket.isInputShutdown();
    }

    @Implementation
    protected boolean isOutputShutdown() {
      return socket != null && socket.isOutputShutdown();
    }

    @Implementation
    protected void setSoTimeout(int n) throws IOException {
      open().setSoTimeout(n);
    }

    @Implementation
    protected int getSoTimeout() throws IOException {
      return open().getSoTimeout();
    }

    @Implementation
    protected void setReceiveBufferSize(int size) {}

    @Implementation
    protected void setSendBufferSize(int n) {}

    @Implementation
    protected FileDescriptor getFileDescriptor() {
      return null;
    }
  }

  /**
   * Os.poll on a LocalServerSocket's descriptor: readable once a connection waits. Other descriptors keep what
   * Robolectric gives, and a mix waits on the servers only.
   */
  @Implements(className = "libcore.io.Linux", isInAndroidSdk = false, minSdk = 26)
  public static class ShadowSimLinux extends ShadowLinux {
    @org.robolectric.annotation.RealObject private Object self;

    /** Robolectric's own poll, for descriptors that are not a LocalServerSocket's. */
    private int original(StructPollfd[] fds, int timeoutMs) throws android.system.ErrnoException {
      try {
        java.lang.reflect.Method m = self.getClass().getDeclaredMethod("$$robo$$libcore_io_Linux$poll", StructPollfd[].class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(self, fds, timeoutMs);
      } catch (java.lang.reflect.InvocationTargetException e) {
        if (e.getCause() instanceof android.system.ErrnoException x) throw x;
        if (e.getCause() instanceof RuntimeException r) throw r;
        if (e.getCause() instanceof Error r) throw r;
        throw new IllegalStateException(e.getCause());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    @Implementation
    protected int poll(StructPollfd[] fds, int timeoutMs) throws android.system.ErrnoException {
      boolean any = false;
      for (StructPollfd p : fds) any |= p.fd != null && BY_FD.containsKey(p.fd);
      if (!any) return original(fds, timeoutMs);
      long end = timeoutMs < 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMs * 1_000_000L;
      while (true) {
        int ready = 0;
        int ours = 0;
        for (StructPollfd p : fds) {
          p.revents = 0;
          Server s = p.fd == null ? null : BY_FD.get(p.fd);
          if (s == null) continue;
          ours++;
          try {
            if (!s.channel.isOpen()) {
              p.revents = (short) OsConstants.POLLNVAL;
              ready++;
            } else if ((p.events & OsConstants.POLLIN) != 0 && s.ready(0)) {
              p.revents = (short) OsConstants.POLLIN;
              ready++;
            }
          } catch (IOException e) {
            p.revents = (short) OsConstants.POLLERR;
            ready++;
          }
        }
        if (ready > 0 || ours == 0) return ready;
        long left = end - System.nanoTime();
        if (left <= 0) return 0;
        // One server waits on its selector; several take turns in short slices.
        int slice = (int) Math.min(ours == 1 ? Integer.MAX_VALUE : 20, Math.max(1, left / 1_000_000L));
        for (StructPollfd p : fds) {
          Server s = p.fd == null ? null : BY_FD.get(p.fd);
          if (s == null) continue;
          try {
            if (s.ready(timeoutMs < 0 && ours == 1 ? -1 : slice)) break;
          } catch (IOException e) {
            break;
          }
          if (ours == 1) break;
        }
      }
    }
  }
}
