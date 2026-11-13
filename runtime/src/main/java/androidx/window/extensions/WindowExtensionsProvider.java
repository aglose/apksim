package androidx.window.extensions;

/**
 * apksim's stand-in for the system library {@code androidx.window.extensions} (a device's system image provides the
 * real one): Jetpack WindowManager in the app looks this class up by name and, when its shape is what it expects,
 * asks it for the window's display features. Only the layout component is here (folds, for WindowInfoTracker); activity
 * embedding and window areas are not, which the library takes as a device without them. See dev.apksim.FoldExtensions.
 */
public class WindowExtensionsProvider {
  private WindowExtensionsProvider() {}

  public static WindowExtensions getWindowExtensions() {
    return dev.apksim.FoldExtensions.INSTANCE;
  }
}
