package dev.apksim;

/** What a --device value means, for tools that run on a plain JVM (`apksim skin`): its kind, dp size, density and pixels. */
public record DeviceSpec(String kind, int widthDp, int heightDp, float density, int widthPx, int heightPx) {
  /** Resolves a preset name, WxH[@density] or WxHpx@Ndpi; throws IllegalArgumentException with the reason. */
  public static DeviceSpec resolve(String name) {
    Device d = Device.named(name);
    return new DeviceSpec(d.kind(), d.widthDp(), d.heightDp(), d.density(), d.widthPx(), d.heightPx());
  }
}
