package dev.apksim;

/** SPIKE (docs/api-levels.md): the framework's API level, 36 unless -Dapksim.api says otherwise. */
public final class Api {
  public static final int LEVEL = Integer.getInteger("apksim.api", 36);
  /** Android 15+ frameworks bring their own libandroid_runtime; older ones use Robolectric's compat runtime. */
  public static final boolean OWN_RUNTIME = LEVEL >= 35;
  private Api() {}
}
