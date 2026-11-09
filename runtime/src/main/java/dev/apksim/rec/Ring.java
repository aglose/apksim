package dev.apksim.rec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * What one flight-recorder channel keeps: items stamped with the wall clock, oldest first. Items older than the
 * recorder's cutoff ({@link Rec#keepFromWallMs}) go as new ones arrive, and the oldest go too while the ring holds more
 * than its byte budget, so a chatty channel can never take the heap.
 */
public final class Ring<T> {
  /** One item: when it happened (System.currentTimeMillis), roughly what it costs to keep, and the value. */
  public record Item<T>(long wallMs, long bytes, T value) {}

  private final ArrayDeque<Item<T>> items = new ArrayDeque<>();
  private final long maxBytes;
  private final LongSupplier keepFrom;
  private long bytes;
  private long dropped;  // items let go for the byte budget (not for age): a saved bundle says so

  public Ring(long maxBytes, LongSupplier keepFrom) {
    this.maxBytes = maxBytes;
    this.keepFrom = keepFrom;
  }

  public synchronized void add(long wallMs, long bytes, T value) {
    items.addLast(new Item<>(wallMs, bytes, value));
    this.bytes += bytes;
    trim();
  }

  /**
   * Drops what is older than the cutoff, but the newest of those stays: what held at the cutoff (the screen as it was
   * when a still stretch began). Items normally arrive in time order, but not strictly (a thread stamps an item before
   * it gets the ring's lock), so this only trims the front; {@link #since} looks at every item.
   */
  private void trim() {
    long from = keepFrom.getAsLong();
    while (items.size() > 1) {
      Iterator<Item<T>> it = items.iterator();
      it.next();
      if (it.next().wallMs() > from) break;
      bytes -= items.removeFirst().bytes();
    }
    while (bytes > maxBytes && !items.isEmpty()) {  // an item bigger than the whole budget goes too
      bytes -= items.removeFirst().bytes();
      dropped++;
    }
  }

  /** Items from wallMs `from` on (all of them for Long.MIN_VALUE), in time order. */
  public synchronized List<Item<T>> since(long from) {
    trim();
    List<Item<T>> out = new ArrayList<>();
    for (Item<T> i : items) if (i.wallMs() >= from) out.add(i);
    out.sort(java.util.Comparator.comparingLong(Item::wallMs));  // stable: equal stamps keep their order
    return out;
  }

  /** The newest item at or before wallMs `at`, or null. */
  public synchronized Item<T> atOrBefore(long at) {
    Item<T> best = null;
    for (Item<T> i : items) if (i.wallMs() <= at && (best == null || i.wallMs() >= best.wallMs())) best = i;
    return best;
  }

  public synchronized void clear() {
    items.clear();
    bytes = 0;
    dropped = 0;
  }

  public synchronized int size() {
    return items.size();
  }

  public synchronized long bytes() {
    return bytes;
  }

  public synchronized long dropped() {
    return dropped;
  }
}
