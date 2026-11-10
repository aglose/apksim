package com.google.android.wearable;

/** apksim's stand-in for the Wear shared library's version entry point, which androidx.wear's SharedLibraryVersion reads (API 25+). */
public final class WearableSharedLib {
  private WearableSharedLib() {}

  /**
   * The capability level this stand-in supports: 0, the shared library without the optional APIs (hardware button
   * input, ambient offload), so androidx.wear takes its fallbacks for those instead of calling classes missing here.
   */
  public static int version() {
    return 0;
  }
}
