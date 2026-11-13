package androidx.window.extensions;

import androidx.window.extensions.layout.WindowLayoutComponent;

/** See {@link WindowExtensionsProvider}. */
public interface WindowExtensions {
  int getVendorApiLevel();

  WindowLayoutComponent getWindowLayoutComponent();
}
