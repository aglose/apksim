package dev.apksim.composeapp;

import android.util.SparseIntArray;

/**
 * The shape of play-services' GoogleApiAvailabilityCache, which apksim's DEX translator once miscompiled: a lookup
 * that returns an int from outside a synchronized block whose catch-all range D8 ends after that return. The return
 * then shared a register with the lock object the handler unlocks, and every int that reached it was typed as a
 * reference (the loop always called keyAt(0) and threw on an empty array).
 */
public final class SyncCache {
  private final SparseIntArray cache = new SparseIntArray();
  private int computed;

  public int lookup(int minVersion) {
    int result = cached(minVersion);
    if (result == -1) {
      synchronized (cache) {
        int found = -1;
        for (int i = 0; i < cache.size(); i++) {
          int key = cache.keyAt(i);
          if (key > minVersion && cache.get(key) == 0) {
            found = 0;
            break;
          }
        }
        if (found == -1) found = compute(minVersion);
        cache.put(minVersion, found);
        result = found;
      }
    }
    return result;
  }

  private int cached(int minVersion) {
    synchronized (cache) {
      return cache.get(minVersion, -1);
    }
  }

  private int compute(int minVersion) {
    computed++;
    return minVersion % 2;
  }

  public int computed() {
    return computed;
  }
}
