package dev.apksim.composeapp

import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MimeTypeMap reads the framework's top-level mime.types files (debian/android/vendor.mime.types under res/) as class
 * resources next to DefaultMimeMapFactory (issue #599). Verify both extension->MIME and MIME->extension lookups work
 * at runtime.
 */
@RunWith(AndroidJUnit4::class)
class MimeTypeMapTest {
  @Test
  fun commonExtensionsAndMimeTypesResolve() {
    val map = MimeTypeMap.getSingleton()
    assertEquals("image/png", map.getMimeTypeFromExtension("png"))
    assertEquals("image/jpeg", map.getMimeTypeFromExtension("jpg"))
    assertEquals("image/webp", map.getMimeTypeFromExtension("webp"))
    assertEquals("application/json", map.getMimeTypeFromExtension("json"))
    assertEquals("png", map.getExtensionFromMimeType("image/png"))
    assertTrue("hasExtension(png)", map.hasExtension("png"))
    assertTrue("hasMimeType(image/png)", map.hasMimeType("image/png"))
  }
}
