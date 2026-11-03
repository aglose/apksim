package dev.apksim;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowKeyCharacterMap;

/**
 * Keys pressed with Ctrl or Meta produce no character, as with Android's key maps. Robolectric's returns the
 * plain letter, so Compose took Ctrl+A for typing "a" rather than Select all.
 */
@Implements(KeyCharacterMap.class)
public class ShadowDeviceKeyCharacterMap extends ShadowKeyCharacterMap {
  @Implementation
  protected static char nativeGetCharacter(long ptr, int keyCode, int metaState) {
    if ((metaState & (KeyEvent.META_CTRL_MASK | KeyEvent.META_META_MASK)) != 0) return 0;
    return ShadowKeyCharacterMap.nativeGetCharacter(ptr, keyCode, metaState);
  }
}
