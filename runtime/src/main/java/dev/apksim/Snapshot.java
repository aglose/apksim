package dev.apksim;

import static org.robolectric.util.reflector.Reflector.reflector;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

/**
 * `--snapshot` (-Dapksim.snapshot=true): the same pixels for the same input, for screenshot tests (docs/snapshots.md).
 * Layer effects, AGSL paint shaders and scaled bitmaps stay on CPU Skia (Metal's float math differs from Skia's in the
 * last bits, and Linux has none), and text carets do not blink: a caret is drawn whenever it would be, whatever the
 * time since the last keystroke. Read once at startup; snapshot launches are cold, so a standby never has it.
 */
public final class Snapshot {
  private Snapshot() {}

  public static final boolean ON = Boolean.getBoolean("apksim.snapshot");

  /**
   * Compose's caret: the translator passes CursorAnimationState's constructor argument (animate) through here.
   * With it false the caret is shown and stays, as in Compose's own tests (LocalCursorBlinkEnabled). Only where the
   * class keeps its name (debug builds; R8 renames it).
   */
  public static boolean cursorBlink(boolean animate) {
    return animate && !ON;
  }

  /** A View's caret (android.widget.Editor) rendered whenever it is visible, not only in the blink's "on" half. */
  @Implements(className = "android.widget.Editor", isInAndroidSdk = false)
  public static class ShadowSteadyCaret {
    @RealObject private Object real;

    @Implementation
    protected boolean shouldRenderCursor() {
      return reflector(EditorReflector.class, real).isCursorVisible();
    }

    @ForType(className = "android.widget.Editor")
    interface EditorReflector {
      @Direct
      boolean isCursorVisible();
    }
  }
}
