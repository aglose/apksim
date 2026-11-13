package androidx.window.extensions.layout;

import android.app.Activity;
import android.content.Context;

/** Vendor API level 2's methods: an Activity with a java.util Consumer (level 1), a UI Context with the library's. */
public interface WindowLayoutComponent {
  void addWindowLayoutInfoListener(Activity activity, java.util.function.Consumer<WindowLayoutInfo> consumer);

  void removeWindowLayoutInfoListener(java.util.function.Consumer<WindowLayoutInfo> consumer);

  void addWindowLayoutInfoListener(Context context,
      androidx.window.extensions.core.util.function.Consumer<WindowLayoutInfo> consumer);

  void removeWindowLayoutInfoListener(androidx.window.extensions.core.util.function.Consumer<WindowLayoutInfo> consumer);
}
