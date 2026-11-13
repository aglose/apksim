package androidx.window.extensions.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WindowLayoutInfo {
  private final List<DisplayFeature> features;

  public WindowLayoutInfo(List<DisplayFeature> features) {
    this.features = Collections.unmodifiableList(new ArrayList<>(features));
  }

  public List<DisplayFeature> getDisplayFeatures() {
    return features;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof WindowLayoutInfo i && i.features.equals(features);
  }

  @Override
  public int hashCode() {
    return features.hashCode();
  }

  @Override
  public String toString() {
    return "WindowLayoutInfo" + features;
  }
}
