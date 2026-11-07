package dev.apksim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Integer values from an APK's resources.arsc, for attributes a manifest takes from a resource (an intent-filter
 * priority is often `@integer/launcher_priority`, which Axml prints as `@0x7f...`). Reads only what that needs: the
 * package and type chunks, and the integer entries of each type.
 */
final class Arsc {
  private static final int TYPE_PACKAGE = 0x0200;
  private static final int TYPE_RESOURCE = 0x0201;
  private static final int NO_ENTRY = -1;
  private static final int FLAG_COMPLEX = 0x0001;
  private static final int DATA_REFERENCE = 0x01;
  private static final int DATA_INT_DEC = 0x10;
  private static final int DATA_INT_HEX = 0x11;
  private static final int DATA_INT_BOOLEAN = 0x12;
  private static final int MAX_REFERENCE_DEPTH = 8;

  private Arsc() {}

  /** The integer resource {@code resId} (as printed, `0x7f0a0001`), or null when it is not an integer. */
  static Long intValue(byte[] arsc, int resId) {
    return value(ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN), resId, 0);
  }

  private static Long value(ByteBuffer b, int resId, int depth) {
    if (depth > MAX_REFERENCE_DEPTH || resId == 0) return null;
    int pkgId = resId >>> 24;
    int typeId = (resId >>> 16) & 0xff;
    int entryIndex = resId & 0xffff;
    // The table's children are its packages; the one with this id holds the type chunks.
    for (int p = headerSize(b, 0); p + 8 <= chunkEnd(b, 0); p += chunkSize(b, p)) {
      if (chunkType(b, p) != TYPE_PACKAGE || b.getInt(p + 8) != pkgId) continue;
      Long fallback = null;
      for (int t = p + headerSize(b, p); t + 8 <= chunkEnd(b, p); t += chunkSize(b, t)) {
        if (chunkType(b, t) != TYPE_RESOURCE || (b.get(t + 8) & 0xff) != typeId) continue;
        int entryCount = b.getInt(t + 12);
        int entriesStart = b.getInt(t + 16);
        if (entryIndex >= entryCount) continue;
        int offset = b.getInt(t + headerSize(b, t) + entryIndex * 4);
        if (offset == NO_ENTRY) continue;
        Long v = entryValue(b, t + entriesStart + offset, depth);
        if (v == null) continue;
        // A type chunk's config says which device it is for: the default one (all zero) wins over the others.
        if (isDefaultConfig(b, t)) return v;
        if (fallback == null) fallback = v;
      }
      return fallback;
    }
    return null;
  }

  /** An entry's value when it is an integer (or a reference to one), else null (a bag, a string, a file...). */
  private static Long entryValue(ByteBuffer b, int entry, int depth) {
    if ((b.getShort(entry + 2) & FLAG_COMPLEX) != 0) return null;
    int valueAt = entry + (b.getShort(entry) & 0xffff);
    int dataType = b.get(valueAt + 3) & 0xff;
    int data = b.getInt(valueAt + 4);
    if (dataType == DATA_INT_DEC || dataType == DATA_INT_HEX || dataType == DATA_INT_BOOLEAN) {
      return data & 0xffffffffL;
    }
    if (dataType == DATA_REFERENCE) return value(b, data, depth + 1);
    return null;
  }

  /** The ResTable_config after a type chunk's header is all zero: the configuration matching every device. */
  private static boolean isDefaultConfig(ByteBuffer b, int type) {
    int configAt = type + 20;  // ResTable_typeSpec's fields before the config: 20 bytes
    int configSize = b.getInt(configAt);
    for (int i = configAt + 4; i < configAt + configSize; i++) {
      if (b.get(i) != 0) return false;
    }
    return true;
  }

  private static int chunkType(ByteBuffer b, int at) {
    return b.getShort(at) & 0xffff;
  }

  private static int headerSize(ByteBuffer b, int at) {
    return b.getShort(at + 2) & 0xffff;
  }

  private static int chunkSize(ByteBuffer b, int at) {
    return b.getInt(at + 4);
  }

  private static int chunkEnd(ByteBuffer b, int at) {
    return at + chunkSize(b, at);
  }
}
