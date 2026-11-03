package dev.apksim;

/** Screen shapes selectable with --device. */
record Device(String name, int widthDp, int heightDp, float density) {
  private static final Device[] ALL = {
    new Device("phone", 411, 891, 2f),
    new Device("foldable", 841, 701, 2f),
    new Device("tablet", 1280, 800, 2f),
    new Device("desktop", 1440, 900, 2f),
  };

  static Device named(String name) {
    for (Device d : ALL) {
      if (d.name.equals(name)) return d;
    }
    throw new IllegalArgumentException("unknown device: " + name);
  }

  int widthPx() {
    return (int) (widthDp * density);
  }

  int heightPx() {
    return (int) (heightDp * density);
  }

  String qualifiers() {
    String orientation = widthDp > heightDp ? "land" : "port";
    return "w" + widthDp + "dp-h" + heightDp + "dp-" + orientation + "-xhdpi";
  }
}
