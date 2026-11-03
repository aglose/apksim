package dev.apksim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * Files picked on the Mac, served to the app as content://dev.apksim.files/N/NAME the way a documents provider
 * would: readable through openInputStream/openFileDescriptor, with a display name and size for query.
 */
public final class PickedFiles extends ContentProvider {
  static final String AUTHORITY = "dev.apksim.files";
  private static final List<Path> FILES = new ArrayList<>();
  private static boolean registered;

  static synchronized Uri add(Path file) {
    if (!registered) {
      PickedFiles provider = new PickedFiles();
      android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
      info.authority = AUTHORITY;
      info.grantUriPermissions = true;
      provider.attachInfo(org.robolectric.RuntimeEnvironment.getApplication(), info);
      ShadowContentResolver.registerProviderInternal(AUTHORITY, provider);
      registered = true;
    }
    FILES.add(file);
    return Uri.parse("content://" + AUTHORITY + "/" + (FILES.size() - 1) + "/" + Uri.encode(file.getFileName().toString()));
  }

  private static synchronized Path file(Uri uri) throws FileNotFoundException {
    try {
      return FILES.get(Integer.parseInt(uri.getPathSegments().get(0)));
    } catch (RuntimeException e) {
      throw new FileNotFoundException(uri.toString());
    }
  }

  /**
   * Robolectric gives instrumented framework classes this accessor as a synthetic method, which javac does not see:
   * a subclass compiled here must declare it (app subclasses come from the DEX translator and need not).
   */
  public Object $$robo$getData() {
    try {
      java.lang.reflect.Field f = ContentProvider.class.getDeclaredField("__robo_data__");
      return f.get(this);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    Path f;
    try {
      f = file(uri);
    } catch (FileNotFoundException e) {
      return null;
    }
    String[] cols = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
    MatrixCursor c = new MatrixCursor(cols);
    Object[] row = new Object[cols.length];
    for (int i = 0; i < cols.length; i++) {
      row[i] = switch (cols[i]) {
        case OpenableColumns.DISPLAY_NAME -> f.getFileName().toString();
        case OpenableColumns.SIZE -> f.toFile().length();
        case "mime_type" -> getType(uri);
        default -> null;
      };
    }
    c.addRow(row);
    return c;
  }

  @Override
  public String getType(Uri uri) {
    String name = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
    String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    return type != null ? type : "application/octet-stream";
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    return ParcelFileDescriptor.open(file(uri).toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    return 0;
  }
}
