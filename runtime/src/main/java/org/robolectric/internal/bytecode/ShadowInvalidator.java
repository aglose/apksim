package org.robolectric.internal.bytecode;

import java.lang.invoke.SwitchPoint;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * apksim's copy of Robolectric 4.17's ShadowInvalidator, ahead of it on the class path. The same, plus one step: the
 * classes it invalidates also have their directly bound call sites reset (InvokeDynamicSupport.invalidated), since
 * those carry no SwitchPoint guard.
 */
public class ShadowInvalidator {
  private static final SwitchPoint DUMMY = new SwitchPoint();

  static {
    SwitchPoint.invalidateAll(new SwitchPoint[] {DUMMY});
  }

  private final Map<String, SwitchPoint> switchPoints = new HashMap<>();

  public ShadowInvalidator() {}

  public SwitchPoint getSwitchPoint(Class<?> caller) {
    return getSwitchPoint(caller.getName());
  }

  public synchronized SwitchPoint getSwitchPoint(String className) {
    SwitchPoint switchPoint = switchPoints.get(className);
    if (switchPoint == null) switchPoints.put(className, switchPoint = new SwitchPoint());
    return switchPoint;
  }

  public synchronized void invalidateClasses(Collection<String> classNames) {
    if (classNames.isEmpty()) return;
    SwitchPoint[] points = new SwitchPoint[classNames.size()];
    int i = 0;
    for (String name : classNames) {
      SwitchPoint switchPoint = switchPoints.put(name, null);
      if (switchPoint == null) switchPoint = DUMMY;
      points[i++] = switchPoint;
    }
    SwitchPoint.invalidateAll(points);
    InvokeDynamicSupport.invalidated(classNames);
  }
}
