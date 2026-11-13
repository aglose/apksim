package dev.apksim;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import androidx.window.extensions.WindowExtensions;
import androidx.window.extensions.layout.DisplayFeature;
import androidx.window.extensions.layout.FoldingFeature;
import androidx.window.extensions.layout.WindowLayoutComponent;
import androidx.window.extensions.layout.WindowLayoutInfo;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a device's window extensions tell Jetpack WindowManager about folds (androidx.window.extensions' stand-in, see
 * WindowExtensionsProvider): each listener hears its window's layout as it registers and whenever it changes ({@link
 * Fold}: the posture, a rotation, another display). Every device has the library, as phones do; only a folding
 * device that is not closed reports a fold, a line across the middle of the inner display, flat or half-opened.
 * Windows here fill the display, so the fold's place in the window is its place on the display.
 * {@code -Dapksim.windowExtensions=false} reports vendor API level 0, which the library takes as no extensions.
 */
public final class FoldExtensions implements WindowExtensions, WindowLayoutComponent {
  public static final FoldExtensions INSTANCE = new FoldExtensions();

  private record Listener(Context context, java.util.function.Consumer<WindowLayoutInfo> consumer, WindowLayoutInfo[] last) {}

  /** By the consumer the app gave (either kind). */
  private final Map<Object, Listener> listeners = new IdentityHashMap<>();

  private FoldExtensions() {
    Fold.onChange(this::changed);
  }

  @Override
  public int getVendorApiLevel() {
    return Boolean.parseBoolean(System.getProperty("apksim.windowExtensions", "true")) ? 2 : 0;
  }

  @Override
  public WindowLayoutComponent getWindowLayoutComponent() {
    return this;
  }

  @Override
  public void addWindowLayoutInfoListener(Activity activity, java.util.function.Consumer<WindowLayoutInfo> consumer) {
    add(activity, consumer, consumer);
  }

  @Override
  public void removeWindowLayoutInfoListener(java.util.function.Consumer<WindowLayoutInfo> consumer) {
    remove(consumer);
  }

  @Override
  public void addWindowLayoutInfoListener(Context context,
      androidx.window.extensions.core.util.function.Consumer<WindowLayoutInfo> consumer) {
    add(context, consumer, consumer::accept);
  }

  @Override
  public void removeWindowLayoutInfoListener(androidx.window.extensions.core.util.function.Consumer<WindowLayoutInfo> consumer) {
    remove(consumer);
  }

  private void add(Context context, Object key, java.util.function.Consumer<WindowLayoutInfo> consumer) {
    Listener l = new Listener(context, consumer, new WindowLayoutInfo[1]);
    synchronized (listeners) {
      if (listeners.containsKey(key)) return;
      listeners.put(key, l);
    }
    onMain(() -> tell(l));
  }

  private void remove(Object key) {
    synchronized (listeners) {
      listeners.remove(key);
    }
  }

  private void changed() {
    List<Listener> all;
    synchronized (listeners) {
      // A destroyed activity's listener the app never took away: a device drops it with the activity.
      listeners.values().removeIf(l -> l.context instanceof Activity a && a.isDestroyed());
      all = new ArrayList<>(listeners.values());
    }
    onMain(() -> {
      for (Listener l : all) tell(l);
    });
  }

  /** The listener's layout now, if it is not what it heard last. */
  private void tell(Listener l) {
    synchronized (listeners) {
      if (!listeners.containsValue(l)) return;
    }
    WindowLayoutInfo now = layout(l.context);
    if (now.equals(l.last[0])) return;
    l.last[0] = now;
    try {
      l.consumer.accept(now);
    } catch (RuntimeException e) {
      // One listener's failure is its own: the others still hear, and a `fold` command still answers.
      System.err.println("[apksim] fold: a window layout listener threw " + e);
    }
  }

  private static WindowLayoutInfo layout(Context context) {
    List<DisplayFeature> features = new ArrayList<>();
    try {
      WindowManager wm = context.getSystemService(WindowManager.class);
      Rect window = wm.getCurrentWindowMetrics().getBounds();
      int[] b = Fold.bounds(window.width(), window.height(), wm.getDefaultDisplay().getRotation());
      if (b != null) {
        features.add(new FoldingFeature(new Rect(b[0], b[1], b[2], b[3]), FoldingFeature.TYPE_FOLD,
            Fold.halfOpened() ? FoldingFeature.STATE_HALF_OPENED : FoldingFeature.STATE_FLAT));
      }
    } catch (RuntimeException e) {
      // Not a window's context (or its window is gone): no features, as the platform answers.
    }
    return new WindowLayoutInfo(features);
  }

  private static void onMain(Runnable r) {
    if (Looper.getMainLooper().isCurrentThread()) r.run();
    else new Handler(Looper.getMainLooper()).post(r);
  }

  /** A test's setup: the last test's listeners (and the activities they hold) go. */
  static void reset() {
    synchronized (INSTANCE.listeners) {
      INSTANCE.listeners.clear();
    }
  }
}
