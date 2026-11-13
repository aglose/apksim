package dev.apksim.health;

import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.InternalExternalRecordConverter;
import android.os.Parcel;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The records Health Connect holds, in the framework's own internal form ({@link RecordInternal}, what the platform's
 * service receives and returns in a RecordsParcel), so every record type the framework knows is kept as it is: a copy
 * goes in and a copy comes out, made by the record's own writeToParcel and populateUsing, as a binder call would.
 * Plus the change log the change tokens read. Not thread-safe: {@link Health} holds its lock around every use.
 */
final class Store {
  /** A record and whether a launch's --health seed put it there (seeds are replaced at the next launch that has any). */
  static final class Entry {
    final RecordInternal<?> record;
    final boolean seed;

    Entry(RecordInternal<?> record, boolean seed) {
      this.record = record;
      this.seed = seed;
    }
  }

  /** One change: a record of [type] from [pkg] written (upsert) or deleted at [time], numbered [seq]. */
  record Change(long seq, String uuid, int type, String pkg, boolean deleted, long time) {}

  private static final int VERSION = 1;

  /** By UUID, in insertion order. */
  final Map<String, Entry> records = new LinkedHashMap<>();
  final List<Change> changes = new ArrayList<>();
  long seq;

  void clear() {
    records.clear();
    changes.clear();
  }

  /** The change log kept: older entries go, as a device's change tokens expire (after 30 days there). */
  static final int MAX_CHANGES = 20_000;

  void put(RecordInternal<?> r, boolean seed, long now) {
    String id = r.getUuid().toString();
    records.remove(id);  // an update moves it to the end, as a new row would
    records.put(id, new Entry(r, seed));
    log(new Change(++seq, id, r.getRecordType(), r.getPackageName(), false, now));
  }

  private void log(Change c) {
    changes.add(c);
    if (changes.size() > MAX_CHANGES) changes.subList(0, changes.size() - MAX_CHANGES).clear();
  }

  boolean delete(String uuid, long now) {
    Entry e = records.remove(uuid);
    if (e == null) return false;
    log(new Change(++seq, uuid, e.record.getRecordType(), e.record.getPackageName(), true, now));
    return true;
  }

  /** A copy of [r] through its parcel form, as the other end of a binder call would get it. */
  static RecordInternal<?> copy(RecordInternal<?> r) {
    return fromBytes(r.getRecordType(), toBytes(r));
  }

  static byte[] toBytes(RecordInternal<?> r) {
    Parcel p = Parcel.obtain();
    try {
      r.writeToParcel(p);
      return p.marshall();
    } finally {
      p.recycle();
    }
  }

  static RecordInternal<?> fromBytes(int type, byte[] b) {
    Parcel p = Parcel.obtain();
    try {
      p.unmarshall(b, 0, b.length);
      p.setDataPosition(0);
      RecordInternal<?> r = InternalExternalRecordConverter.getInstance().newInternalRecord(type);
      r.populateUsing(p);
      return r;
    } finally {
      p.recycle();
    }
  }

  // ---- the slot's file: records as their parcels, then the change log

  void write(OutputStream os) throws IOException {
    DataOutputStream out = new DataOutputStream(os);
    out.writeInt(VERSION);
    out.writeLong(seq);
    out.writeInt(records.size());
    for (Entry e : records.values()) {
      byte[] b = toBytes(e.record);
      out.writeInt(e.record.getRecordType());
      out.writeBoolean(e.seed);
      out.writeInt(b.length);
      out.write(b);
    }
    out.writeInt(changes.size());
    for (Change c : changes) {
      out.writeLong(c.seq());
      out.writeUTF(c.uuid());
      out.writeInt(c.type());
      out.writeUTF(c.pkg() == null ? "" : c.pkg());
      out.writeBoolean(c.deleted());
      out.writeLong(c.time());
    }
    out.flush();
  }

  /** Reads what [write] wrote; a record the framework can no longer read is dropped with a line on stderr. */
  void read(InputStream is) throws IOException {
    DataInputStream in = new DataInputStream(is);
    if (in.readInt() != VERSION) throw new IOException("unknown health store version");
    clear();
    seq = in.readLong();
    int n = in.readInt();
    int dropped = 0;
    for (int i = 0; i < n; i++) {
      int type = in.readInt();
      boolean seed = in.readBoolean();
      byte[] b = new byte[in.readInt()];
      in.readFully(b);
      try {
        RecordInternal<?> r = fromBytes(type, b);
        records.put(r.getUuid().toString(), new Entry(r, seed));
      } catch (RuntimeException e) {
        dropped++;
      }
    }
    if (dropped > 0) System.err.println("[apksim] health: " + dropped + " stored records could not be read");
    int m = in.readInt();
    for (int i = 0; i < m; i++) {
      long s = in.readLong();
      String uuid = in.readUTF();
      int type = in.readInt();
      String pkg = in.readUTF();
      changes.add(new Change(s, uuid, type, pkg.isEmpty() ? null : pkg, in.readBoolean(), in.readLong()));
    }
  }

  byte[] bytes() throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    write(b);
    return b.toByteArray();
  }
}
