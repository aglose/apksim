package com.google.android.wearable;

/** apksim's stand-in for the Wear shared library's version entry point, which androidx.wear's SharedLibraryVersion reads (API 25+). */
public final class WearableSharedLib {
  private WearableSharedLib() {}

  /** The capability level this stand-in supports. */
  public static int version() {
    return 20;
  }
}
