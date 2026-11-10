package dev.apksim;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Priority;
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader;

/**
 * Same steps as the stock loader, minus re-extracting the native library, fonts and ICU data from a
 * jar to a fresh temp dir on every launch: they are unpacked once by scripts/prepare-runtime.sh.
 * A stable dylib path also keeps macOS from re-scanning a brand-new binary each time.
 */
@Priority(Integer.MAX_VALUE)
public final class FastNativeLoader extends DefaultNativeRuntimeLoader {
  private boolean done;

  @Override
  public synchronized void ensureLoaded() {
    if (done) return;
    done = true;
    Timing.detail("native-load");
    try {
      Field loaded = DefaultNativeRuntimeLoader.class.getDeclaredField("loaded");
      loaded.setAccessible(true);
      ((AtomicBoolean) loaded.get(null)).set(true);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    Path dir = Path.of(System.getProperty("apksim.runtime"));
    System.setProperty("robolectric.nativeruntime.fontdir", dir.resolve("fonts") + "/");
    String keepIcuPath = System.getProperty("icu.data.path");
    System.setProperty("icu.data.path", dir.resolve("icu/icudt.dat").toString());
    System.setProperty("icu.locale.default", java.util.Locale.getDefault().toLanguageTag());
    System.setProperty("use_base_native_hostruntime", "true");
    System.setProperty("core_native_classes", String.join(",", getCoreClassNatives()));
    System.setProperty("graphics_native_classes", String.join(",", getGraphicsNatives()));
    System.setProperty("method_binding_format", METHOD_BINDING_FORMAT);
    System.load(dir.resolve(libraryName()).toString());
    Timing.detail("native-dlopen");
    invokeDeferredStaticInitializers();
    Timing.detail("native-static-init");
    setNativeSystemProperty("ro.hyphen.data.dir", dir.resolve("hyphen-data").toString());
    android.graphics.Typeface.loadPreinstalledSystemFontMap();
    Timing.detail("native-fonts");
    android.text.Hyphenator.init();
    Timing.detail("native-loaded");
  }
}
