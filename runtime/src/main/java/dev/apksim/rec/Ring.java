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
   * when a still stretch began).
   */
  private void trim() {
    long from = keepFrom.getAsLong();
    while (items.size() > 1) {
      Iterator<Item<T>> it = items.iterator();
      it.next();
      if (it.next().wallMs() > from) break;
      bytes -= items.removeFirst().bytes();
    }
    while (bytes > maxBytes && items.size() > 1) {
      bytes -= items.removeFirst().bytes();
      dropped++;
    }
  }

  /** Items from wallMs `from` on (all of them for Long.MIN_VALUE), oldest first. */
  public synchronized List<Item<T>> since(long from) {
    trim();
    List<Item<T>> out = new ArrayList<>();
    for (Iterator<Item<T>> it = items.descendingIterator(); it.hasNext(); ) {
      Item<T> i = it.next();
      if (i.wallMs() < from) break;
      out.add(i);
    }
    java.util.Collections.reverse(out);
    return out;
  }

  /** The newest item at or before wallMs `at`, or null. */
  public synchronized Item<T> atOrBefore(long at) {
    for (Iterator<Item<T>> it = items.descendingIterator(); it.hasNext(); ) {
      Item<T> i = it.next();
      if (i.wallMs() <= at) return i;
    }
    return null;
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
