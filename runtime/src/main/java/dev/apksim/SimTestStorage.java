package dev.apksim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * AndroidX Test Storage in a test run. On a device the androidx.test.services app answers its content URIs (test
 * outputs, run files, test arguments); here no other app exists, so a suite that writes through it (a report
 * library's results, with useTestStorageService) failed or wrote nothing. This provider answers those authorities in
 * process: files under --test-outputs DIR (default: test-outputs in apksim's state folder), laid out as on a device's
 * /sdcard/googletest (test_outputfiles, test_runfiles, internal_use), and the instrumentation's arguments as testargs.
 */
public final class SimTestStorage extends ContentProvider {
  private static final Map<String, String> FOLDERS = Map.of(
      "androidx.test.services.storage.outputfiles", "test_outputfiles",
      "androidx.test.services.storage.runfiles", "test_runfiles",
      "androidx.test.services.storage._internal_use_files", "internal_use",
      "androidx.test.services.storage.properties", "internal_use");
  private static final String TEST_ARGS = "androidx.test.services.storage.testargs";

  /** At each test's setup (Robolectric clears providers between tests). */
  static void install() {
    for (String authority : FOLDERS.keySet()) register(authority);
    register(TEST_ARGS);
  }

  /**
   * Before a listener hears a test end: Robolectric resets the environment (its providers too) before JUnit reports
   * testFinished, and a report library writes its result file then. On a device the provider is another app's and
   * is always there.
   */
  static void ensureInstalled() {
    if (!TestActivities.testRun()) return;
    try {
      if (org.robolectric.shadows.ShadowContentResolver.getProvider(Uri.parse("content://" + TEST_ARGS)) != null) return;
    } catch (RuntimeException e) {
      // no provider map yet: register below
    }
    try {
      install();
    } catch (RuntimeException e) {
      System.err.println("[apksim] test storage: " + e);
    }
  }

  private static void register(String authority) {
    SimTestStorage provider = new SimTestStorage();
    ProviderInfo info = new ProviderInfo();
    info.authority = authority;
    info.exported = true;
    android.app.Application app = null;
    try {
      app = org.robolectric.RuntimeEnvironment.getApplication();
    } catch (RuntimeException e) {
      // between tests: no application; the provider needs none
    }
    provider.attachInfo(app, info);
    org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider);
  }

  /** Robolectric's synthetic accessor on instrumented framework classes, which javac does not see (as PickedFiles). */
  public Object $$robo$getData() {
    try {
      java.lang.reflect.Field f = ContentProvider.class.getDeclaredField("__robo_data__");
      return f.get(this);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** The host folder that stands for the device's /sdcard/googletest. */
  static Path root() {
    String dir = System.getProperty("apksim.testOutputs");
    Path base = dir != null ? Path.of(dir) : Path.of(System.getProperty("apksim.cacheRoot", System.getProperty("java.io.tmpdir")), "test-outputs");
    return base.resolve("googletest");
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    String folder = FOLDERS.get(uri.getAuthority());
    if (folder == null) throw new FileNotFoundException(uri.toString());
    Path base = root().resolve(folder).toAbsolutePath().normalize();
    String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/+", "");
    Path file = base.resolve(path).normalize();
    if (!file.startsWith(base)) throw new FileNotFoundException(uri + ": outside the test storage");
    int m = ParcelFileDescriptor.parseMode(mode);
    if ((m & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {  // set in "w" and "rw"; MODE_READ_WRITE has the read bit too
      try {
        Files.createDirectories(file.getParent());
      } catch (IOException e) {
        throw new FileNotFoundException(file + ": " + e);
      }
      m |= ParcelFileDescriptor.MODE_CREATE;
    }
    return ParcelFileDescriptor.open(file.toFile(), m);
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    if (!TEST_ARGS.equals(uri.getAuthority())) return new MatrixCursor(new String[] {"name", "type", "size", "_data"});
    MatrixCursor c = new MatrixCursor(new String[] {"name", "value"});
    Bundle args = InstrumentationRegistry.getArguments();
    String only = uri.getLastPathSegment();
    for (String k : args.keySet()) {
      if (only == null || only.equals(k)) c.addRow(new Object[] {k, String.valueOf(args.get(k))});
    }
    return c;
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    return 0;
  }
}
