package dev.apksim.video;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.net.Uri;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpCookie;
import java.util.List;
import java.util.Map;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

/**
 * MediaPlayer in running instances: a data source nobody registered with addMediaInfo() is a sound of the length its
 * container says (see {@link MediaProbe}) that plays silently, so prepare, start, onPrepared and onCompletion happen
 * on time as on a device, where Robolectric's ShadowMediaPlayer throws "Don't know what to do with dataSource" and
 * ended the app (issue 488). A source a test registered stays as registered. Test runs keep Robolectric's strict
 * behaviour (this shadow is only added to running instances).
 */
@Implements(MediaPlayer.class)
public class ShadowSimMediaPlayer extends ShadowMediaPlayer {
  private static void known(DataSource ds, MediaProbe.Source source) {
    if (ds == null || getMediaInfo(ds) != null) return;
    int ms = source == null ? MediaProbe.UNKNOWN_MS : MediaProbe.durationMs(source);
    addMediaInfo(ds, new MediaInfo(ms, 0));
  }

  private static MediaProbe.Source fd(FileDescriptor fd, long offset, long length) {
    return new MediaProbe.Source() {
      private final FileInputStream in = new FileInputStream(fd);
      private long size = -1;
      @Override public long length() throws IOException {
        if (size < 0) size = Math.min(in.getChannel().size() - offset, length < 0 ? Long.MAX_VALUE : length);
        return size;
      }
      @Override public int read(long pos, byte[] b, int off, int len) throws IOException {
        if (pos >= length()) return -1;
        return in.getChannel().read(java.nio.ByteBuffer.wrap(b, off, (int) Math.min(len, length() - pos)), offset + pos);
      }
    };
  }

  private static MediaProbe.Source stream(Context context, Uri uri) {
    try {
      java.io.File file = "file".equals(uri.getScheme()) || uri.getScheme() == null ? new java.io.File(uri.getPath()) : null;
      if (file != null) return path(file.getPath());
      try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
        if (afd == null) return null;
        // The descriptor closes with this block, so the probe reads through a copy of the bytes it needs.
        return buffered(fd(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength()));
      }
    } catch (Exception e) {
      return null;
    }
  }

  private static MediaProbe.Source path(String path) {
    try {
      java.io.RandomAccessFile f = new java.io.RandomAccessFile(path, "r");
      return buffered(new MediaProbe.Source() {
        @Override public long length() throws IOException { return f.length(); }
        @Override public int read(long pos, byte[] b, int off, int len) throws IOException {
          f.seek(pos);
          return f.read(b, off, len);
        }
      });
    } catch (Exception e) {
      return null;
    }
  }

  /** The source's head and tail kept in memory, so the descriptor or file behind it may be closed at once. */
  private static MediaProbe.Source buffered(MediaProbe.Source s) throws IOException {
    long len = s.length();
    final int cap = 70000;
    byte[] head = new byte[(int) Math.min(len, cap)];
    int h = 0;
    while (h < head.length) { int r = s.read(h, head, h, head.length - h); if (r <= 0) break; h += r; }
    long tailFrom = Math.max(h, len - cap);
    byte[] tail = new byte[(int) (len - tailFrom)];
    int t = 0;
    while (t < tail.length) { int r = s.read(tailFrom + t, tail, t, tail.length - t); if (r <= 0) break; t += r; }
    final int hh = h, tt = t;
    return new MediaProbe.Source() {
      @Override public long length() { return len; }
      @Override public int read(long pos, byte[] b, int off, int n) {
        if (pos >= len) return -1;
        if (pos < hh) { int c = (int) Math.min(n, hh - pos); System.arraycopy(head, (int) pos, b, off, c); return c; }
        if (pos >= tailFrom && pos - tailFrom < tt) { int c = (int) Math.min(n, tt - (pos - tailFrom)); System.arraycopy(tail, (int) (pos - tailFrom), b, off, c); return c; }
        return -1;
      }
    };
  }

  @Implementation
  @Override
  protected void setDataSource(String path) throws IOException {
    known(DataSource.toDataSource(path), path(path));
    super.setDataSource(path);
  }

  @Implementation
  @Override
  protected void setDataSource(String path, Map<String, String> headers) throws IOException {
    known(DataSource.toDataSource(path, headers), path(path));
    super.setDataSource(path, headers);
  }

  @Implementation
  @Override
  protected void setDataSource(Context context, Uri uri) throws IOException {
    known(DataSource.toDataSource(context, uri), stream(context, uri));
    super.setDataSource(context, uri);
  }

  @Implementation
  @Override
  protected void setDataSource(Context context, Uri uri, Map<String, String> headers) throws IOException {
    known(DataSource.toDataSource(context, uri, headers), stream(context, uri));
    super.setDataSource(context, uri, headers);
  }

  @Implementation
  @Override
  protected void setDataSource(Context context, Uri uri, Map<String, String> headers, List<HttpCookie> cookies) throws IOException {
    known(DataSource.toDataSource(context, uri, headers, cookies), stream(context, uri));
    super.setDataSource(context, uri, headers, cookies);
  }

  @Implementation
  @Override
  protected void setDataSource(FileDescriptor fd, long offset, long length) throws IOException {
    MediaProbe.Source src = null;
    try { src = buffered(fd(fd, offset, length)); } catch (Exception ignored) { }
    known(DataSource.toDataSource(fd, offset, length), src);
    super.setDataSource(fd, offset, length);
  }

  @Implementation
  @Override
  protected void setDataSource(AssetFileDescriptor afd) throws IOException {
    MediaProbe.Source src = null;
    try { src = buffered(fd(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength())); } catch (Exception ignored) { }
    known(DataSource.toDataSource(afd), src);
    super.setDataSource(afd);
  }

  @Implementation
  @Override
  protected void setDataSource(MediaDataSource source) throws IOException {
    MediaProbe.Source src = null;
    try {
      src = buffered(new MediaProbe.Source() {
        @Override public long length() throws IOException { return source.getSize(); }
        @Override public int read(long pos, byte[] b, int off, int len) throws IOException { return source.readAt(pos, b, off, len); }
      });
    } catch (Exception ignored) { }
    known(DataSource.toDataSource(source), src);
    super.setDataSource(source);
  }
}
