package dev.apksim;

import android.util.Log;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLog;

/**
 * `Log.isLoggable` from the `log.tag` properties a `setprop` set (issue 554), as a device's liblog reads them: `log.tag.TAG`,
 * then `persist.log.tag.TAG`, then `log.tag` and `persist.log.tag` (a level for every tag), the first whose value starts with
 * a level letter (V D I W E F A, S for silent) deciding; a value that starts with anything else is passed over, as liblog does,
 * and with none the answer is Robolectric's (INFO and up, or a level a test set with `ShadowLog.setLoggable`).
 * Only `isLoggable` changes: the lines an app writes are all kept, as a device's logd keeps them. Robolectric's own
 * `ShadowLog.setLoggable` also filters the lines it writes, so this never calls it. Native liblog's
 * `__android_log_is_loggable` does not read these properties.
 */
public final class LogTags {
  private LogTags() {}

  /** The priority a property names, or -1 when it is unset or its value names none. */
  static int level(String value) {
    if (value == null || value.isEmpty()) return -1;
    return switch (Character.toUpperCase(value.charAt(0))) {
      case 'V' -> Log.VERBOSE;
      case 'D' -> Log.DEBUG;
      case 'I' -> Log.INFO;
      case 'W' -> Log.WARN;
      case 'E' -> Log.ERROR;
      case 'F', 'A' -> Log.ASSERT;
      case 'S' -> Integer.MAX_VALUE;
      default -> -1;
    };
  }

  /** The level the properties give a tag, or -1 when none does. */
  static int levelFor(String tag) {
    for (String name : new String[] {"log.tag." + tag, "persist.log.tag." + tag, "log.tag", "persist.log.tag"}) {
      int l = level(AdbShell.setProp(name));
      if (l >= 0) return l;
    }
    return -1;
  }

  @Implements(Log.class)
  public static class ShadowSimLog extends ShadowLog {
    @Implementation
    protected static boolean isLoggable(String tag, int level) {
      int l = levelFor(tag);
      return l >= 0 ? level >= l : ShadowLog.isLoggable(tag, level);
    }
  }
}
