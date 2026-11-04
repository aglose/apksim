package fixture;

import java.util.HashMap;

/**
 * Modelled on play-services' GoogleApiAvailabilityCache: an int returned after a synchronized block, with the return
 * inside the catch-all range. There a translator that counted the return as throwing typed the int as a reference
 * (the loop ran on an empty map and threw). Here R8 and D8 keep the result out of the lock's register, so this passes
 * either way; [Shapes.putIfAbsent] is the shape that fails.
 */
final class SyncCache {
  private final HashMap<Integer, Integer> cache = new HashMap<>();
  private final int[] keys = new int[16];
  private int size;
  private int computed;

  int lookup(int minVersion) {
    int result = cached(minVersion);
    if (result == -1) {
      synchronized (cache) {
        int found = -1;
        for (int i = 0; i < size; i++) {
          int key = keys[i];
          if (key > minVersion && cache.get(key) == 0) {
            found = 0;
            break;
          }
        }
        if (found == -1) found = compute(minVersion);
        cache.put(minVersion, found);
        keys[size++] = minVersion;
        result = found;
      }
    }
    return result;
  }

  private int cached(int minVersion) {
    synchronized (cache) {
      Integer v = cache.get(minVersion);
      return v == null ? -1 : v;
    }
  }

  private int compute(int minVersion) {
    computed++;
    return minVersion % 2;
  }

  int computed() {
    return computed;
  }
}
