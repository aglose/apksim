package dev.apksim.video;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.net.Uri;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpCookie;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

/**
 * MediaPlayer in running instances: a data source nobody registered with addMediaInfo() is a sound of the length its
 * container says (see {@link MediaProbe}) that plays silently, so prepare, start, onPrepared and onCompletion happen
 * on time as on a device, where Robolectric's ShadowMediaPlayer throws "Don't know what to do with dataSource" and
 * ended the app (issue 488). A source registered by someone else stays as registered; one this class registered is
 * probed again at every call (Robolectric's keys are coarse: every MediaDataSource is one key, a path its string, so
 * a file rewritten in place or another MediaDataSource would get the first one's duration). A local file that cannot
 * be opened throws FileNotFoundException, as a device does. Test runs keep Robolectric's strict behaviour (this shadow
 * is only added to running instances).
 */
@Implements(MediaPlayer.class)
public class ShadowSimMediaPlayer extends ShadowMediaPlayer {
  /** Sources whose MediaInfo this class put there, and may therefore replace. */
  private static final Set<DataSource> OURS = Collections.newSetFromMap(new ConcurrentHashMap<>());

  private interface Probe {
    int durationMs() throws IOException;
  }

  private static void known(DataSource ds, Probe probe) throws IOException {
    if (getMediaInfo(ds) != null && !OURS.contains(ds)) return;
    int ms = probe.durationMs();
    OURS.add(ds);
    addMediaInfo(ds, new MediaInfo(ms, 0));
  }

  /**
   * The duration of the app's descriptor, read in place. Positional reads leave its offset alone, and nothing here
   * closes it: a channel read on an interrupted thread would close the stream and with it the app's descriptor
   * (ClosedByInterruptException), so the flag is cleared for the read and put back after.
   */
  private static int fdDuration(FileDescriptor fd, long offset, long length) {
    boolean interrupted = Thread.interrupted();
    try {
      FileChannel ch = new FileInputStream(fd).getChannel();
      long size = Math.max(0, Math.min(ch.size() - offset, length < 0 ? Long.MAX_VALUE : length));
      return MediaProbe.durationMs(new MediaProbe.Source() {
        @Override public long length() { return size; }
        @Override public int read(long pos, byte[] b, int off, int len) throws IOException {
          if (pos >= size) return -1;
          return ch.read(ByteBuffer.wrap(b, off, (int) Math.min(len, size - pos)), offset + pos);
        }
      });
    } catch (Exception e) {
      return MediaProbe.UNKNOWN_MS;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  /** A local file's duration; FileNotFoundException when it cannot be read, as a device's setDataSource throws. */
  private static int pathDuration(String path) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
      return MediaProbe.durationMs(new MediaProbe.Source() {
        @Override public long length() throws IOException { return f.length(); }
        @Override public int read(long pos, byte[] b, int off, int len) throws IOException {
          f.seek(pos);
          return f.read(b, off, len);
        }
      });
    }
  }

  private static boolean local(String path) {
    return path.startsWith("/") || path.startsWith("file:");
  }

  private static String filePath(String path) {
    return path.startsWith("file:") ? Uri.parse(path).getPath() : path;
  }

  private static int uriDuration(Context context, Uri uri) throws IOException {
    String scheme = uri.getScheme();
    if (scheme == null || ContentResolver.SCHEME_FILE.equals(scheme)) return pathDuration(uri.getPath());
    if (!ContentResolver.SCHEME_CONTENT.equals(scheme) && !ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
      return MediaProbe.UNKNOWN_MS;  // http(s), rtsp...: nothing to read here
    }
    try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
      if (afd == null) return MediaProbe.UNKNOWN_MS;
      return fdDuration(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
    } catch (FileNotFoundException | SecurityException e) {
      // A device's MediaPlayer falls back to the Uri as a string here and fails at prepare; a placeholder length
      // keeps the app going.
      return MediaProbe.UNKNOWN_MS;
    }
  }

  @Implementation
  @Override
  protected void setDataSource(String path) throws IOException {
    known(DataSource.toDataSource(path), () -> local(path) ? pathDuration(filePath(path)) : MediaProbe.UNKNOWN_MS);
    super.setDataSource(path);
  }

  @Implementation
  @Override
  protected void setDataSource(String path, Map<String, String> headers) throws IOException {
    known(DataSource.toDataSource(path, headers), () -> local(path) ? pathDuration(filePath(path)) : MediaProbe.UNKNOWN_MS);
    super.setDataSource(path, headers);
  }

  // Robolectric's (Context, Uri) and (Context, Uri, Map) overloads call this one.
  @Implementation
  @Override
  protected void setDataSource(Context context, Uri uri, Map<String, String> headers, List<HttpCookie> cookies) throws IOException {
    known(DataSource.toDataSource(context, uri, headers, cookies), () -> uriDuration(context, uri));
    super.setDataSource(context, uri, headers, cookies);
  }

  @Implementation
  @Override
  protected void setDataSource(FileDescriptor fd, long offset, long length) throws IOException {
    known(DataSource.toDataSource(fd, offset, length), () -> fdDuration(fd, offset, length));
    super.setDataSource(fd, offset, length);
  }

  @Implementation
  @Override
  protected void setDataSource(AssetFileDescriptor afd) throws IOException {
    known(DataSource.toDataSource(afd), () -> fdDuration(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength()));
    super.setDataSource(afd);
  }

  // The app's own reader is not called: it may block on a network or decrypt as it goes. A placeholder length.
  @Implementation
  @Override
  protected void setDataSource(MediaDataSource source) throws IOException {
    known(DataSource.toDataSource(source), () -> MediaProbe.UNKNOWN_MS);
    super.setDataSource(source);
  }
}
