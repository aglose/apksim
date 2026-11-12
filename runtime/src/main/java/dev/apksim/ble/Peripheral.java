package dev.apksim.ble;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A scripted BLE peripheral (docs/ble.md): what it advertises and its GATT database, read from JSON (`--ble FILE`,
 * `ble add FILE`) or a preset (`heart-rate`, `battery`). Values change as the app writes them, as `ble notify` sets
 * them and as timelines step; everything mutable is guarded by Ble.LOCK.
 */
final class Peripheral {
  static final UUID CCC = uuid("2902");

  String address;
  String name;
  int rssi = -60;
  boolean connectable = true;
  int intervalMs = 1000;  // advertising interval: how often an ALL_MATCHES scan reports it
  Integer txPower;
  int mtu = 517;  // the largest ATT MTU it accepts
  int latencyMs;  // added to each GATT answer, on the uptime clock
  boolean bondable = true;
  final List<UUID> advServiceUuids = new ArrayList<>();
  final Map<Integer, byte[]> manufacturerData = new LinkedHashMap<>();
  final Map<UUID, byte[]> serviceData = new LinkedHashMap<>();
  boolean advName = true;
  final List<Service> services = new ArrayList<>();

  static final class Service {
    UUID uuid;
    boolean primary = true;
    int handle;
    final List<Characteristic> characteristics = new ArrayList<>();
  }

  static final class Characteristic {
    UUID uuid;
    int properties;
    int handle;
    byte[] value = new byte[0];
    final List<Descriptor> descriptors = new ArrayList<>();
    int timelineEveryMs;
    List<byte[]> timeline;
    boolean timelineLoop = true;
    long timelineStart;  // uptime the timeline counts from (the peripheral's arrival)
    long valueAt;  // uptime of the last explicit value (a write, `ble set|notify`): a later timeline step replaces it
    boolean ticking;  // a timeline step is scheduled (only while a link is subscribed to it)
    int writes;
    byte[] lastWritten;
  }

  static final class Descriptor {
    UUID uuid;
    int handle;
    byte[] value = new byte[0];
  }

  /** One peripheral, a list of them, or {"peripherals": [...]}. */
  static List<Peripheral> parse(String json) throws Exception {
    Object top = new org.json.JSONTokener(json).nextValue();
    JSONArray list;
    if (top instanceof JSONArray a) {
      list = a;
    } else {
      JSONObject o = (JSONObject) top;
      list = o.has("peripherals") ? o.getJSONArray("peripherals") : new JSONArray().put(o);
    }
    List<Peripheral> out = new ArrayList<>();
    for (int i = 0; i < list.length(); i++) out.add(from(list.getJSONObject(i)));
    return out;
  }

  /** A file, or a preset name with an optional address after a colon ("heart-rate", "battery:C0:FF:EE:00:00:09"). */
  static List<Peripheral> load(String spec) throws Exception {
    String s = spec.trim();
    Path file = Path.of(System.getProperty("user.dir")).resolve(s);
    if (Files.isRegularFile(file)) return parse(Files.readString(file));  // a file first, whatever its name holds
    String preset = s.contains(":") ? s.substring(0, s.indexOf(':')) : s;
    String addr = preset.equals(s) ? null : s.substring(s.indexOf(':') + 1);
    String json = presetJson(preset);
    if (json == null) throw new java.io.FileNotFoundException(s + " (neither a file nor a preset: " + PRESETS + ")");
    List<Peripheral> ps = parse(json);
    if (addr != null && ps.size() == 1) ps.get(0).address = normalizeAddress(addr);
    return ps;
  }

  static final String PRESETS = "heart-rate, battery";

  static String presetJson(String name) {
    return switch (name) {
      case "heart-rate", "hr", "heartrate" -> """
          {"address": "C0:FF:EE:00:18:0D", "name": "apksim HR", "rssi": -58,
           "advertisement": {"serviceUuids": ["180D"]},
           "services": [
            {"uuid": "180D", "characteristics": [
              {"uuid": "2A37", "properties": ["notify"], "value": "0048",
               "timeline": {"everyMs": 1000, "values": ["0048", "004A", "004C", "004E", "0050", "004E", "004C", "004A"]}},
              {"uuid": "2A38", "properties": ["read"], "value": "01"},
              {"uuid": "2A39", "properties": ["write"], "value": ""}]},
            {"uuid": "180F", "characteristics": [
              {"uuid": "2A19", "properties": ["read", "notify"], "value": "64"}]}]}
          """;
      case "battery" -> """
          {"address": "C0:FF:EE:00:18:0F", "name": "apksim Battery", "rssi": -70,
           "advertisement": {"serviceUuids": ["180F"]},
           "services": [
            {"uuid": "180F", "characteristics": [
              {"uuid": "2A19", "properties": ["read", "notify"], "value": "64",
               "timeline": {"everyMs": 60000, "values": ["63", "62", "61", "60"], "loop": false}}]}]}
          """;
      default -> null;
    };
  }

  private static Peripheral from(JSONObject o) throws Exception {
    Peripheral p = new Peripheral();
    p.address = normalizeAddress(o.getString("address"));
    p.name = o.optString("name", null);
    p.rssi = o.optInt("rssi", p.rssi);
    p.connectable = o.optBoolean("connectable", true);
    p.intervalMs = Math.max(20, o.optInt("intervalMs", p.intervalMs));
    if (o.has("txPower")) p.txPower = o.getInt("txPower");
    p.mtu = Math.max(23, Math.min(517, o.optInt("mtu", p.mtu)));
    p.latencyMs = Math.max(0, o.optInt("latencyMs", 0));
    p.bondable = o.optBoolean("bondable", true);
    JSONObject adv = o.optJSONObject("advertisement");
    if (adv != null) {
      p.connectable = adv.optBoolean("connectable", p.connectable);
      p.advName = adv.optBoolean("includeName", true);
      JSONArray su = adv.optJSONArray("serviceUuids");
      if (su != null) for (int i = 0; i < su.length(); i++) p.advServiceUuids.add(uuid(su.getString(i)));
      JSONObject md = adv.optJSONObject("manufacturerData");
      if (md != null) for (String k : md.keySet()) p.manufacturerData.put(Integer.decode(k.startsWith("0x") || k.startsWith("0X") ? k : "0x" + k), hex(md.getString(k)));
      JSONObject sd = adv.optJSONObject("serviceData");
      if (sd != null) for (String k : sd.keySet()) p.serviceData.put(uuid(k), hex(sd.getString(k)));
    }
    JSONArray svcs = o.optJSONArray("services");
    if (svcs != null) {
      for (int i = 0; i < svcs.length(); i++) {
        JSONObject so = svcs.getJSONObject(i);
        Service s = new Service();
        s.uuid = uuid(so.getString("uuid"));
        s.primary = !so.optBoolean("secondary", false);
        JSONArray chars = so.optJSONArray("characteristics");
        if (chars != null) {
          for (int j = 0; j < chars.length(); j++) {
            JSONObject co = chars.getJSONObject(j);
            Characteristic c = new Characteristic();
            c.uuid = uuid(co.getString("uuid"));
            c.properties = properties(co.optJSONArray("properties"));
            c.value = hex(co.optString("value", ""));
            JSONArray ds = co.optJSONArray("descriptors");
            if (ds != null) {
              for (int k = 0; k < ds.length(); k++) {
                JSONObject dobj = ds.getJSONObject(k);
                Descriptor d = new Descriptor();
                d.uuid = uuid(dobj.getString("uuid"));
                d.value = hex(dobj.optString("value", ""));
                c.descriptors.add(d);
              }
            }
            boolean notifies = (c.properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
            if (notifies && c.descriptors.stream().noneMatch(d -> d.uuid.equals(CCC))) {
              Descriptor d = new Descriptor();  // a notifying characteristic has its CCC descriptor, as the spec requires
              d.uuid = CCC;
              d.value = new byte[] {0, 0};
              c.descriptors.add(d);
            }
            JSONObject tl = co.optJSONObject("timeline");
            if (tl != null) {
              c.timelineEveryMs = Math.max(1, tl.optInt("everyMs", 1000));
              c.timelineLoop = tl.optBoolean("loop", true);
              c.timeline = new ArrayList<>();
              JSONArray vs = tl.getJSONArray("values");
              for (int k = 0; k < vs.length(); k++) c.timeline.add(hex(vs.getString(k)));
              if (c.timeline.isEmpty()) c.timeline = null;
            }
            s.characteristics.add(c);
          }
        }
        p.services.add(s);
      }
    }
    p.assignHandles();
    p.advertisingBytes();  // a structure too long for its length byte is refused here, as the file loads
    return p;
  }

  /** Attribute handles as a server lays them out: service, then each characteristic's value, then its descriptors. */
  private void assignHandles() {
    int h = 1;
    for (Service s : services) {
      s.handle = h++;
      for (Characteristic c : s.characteristics) {
        h++;  // the characteristic declaration
        c.handle = h++;
        for (Descriptor d : c.descriptors) d.handle = h++;
      }
    }
  }

  private static int properties(JSONArray a) throws Exception {
    if (a == null) return BluetoothGattCharacteristic.PROPERTY_READ;
    int p = 0;
    for (int i = 0; i < a.length(); i++) {
      p |= switch (a.getString(i).toLowerCase(Locale.ROOT).replace('_', '-')) {
        case "broadcast" -> BluetoothGattCharacteristic.PROPERTY_BROADCAST;
        case "read" -> BluetoothGattCharacteristic.PROPERTY_READ;
        case "write-no-response", "writewithoutresponse", "write-without-response" -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        case "write" -> BluetoothGattCharacteristic.PROPERTY_WRITE;
        case "notify" -> BluetoothGattCharacteristic.PROPERTY_NOTIFY;
        case "indicate" -> BluetoothGattCharacteristic.PROPERTY_INDICATE;
        case "signed-write" -> BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE;
        default -> throw new IllegalArgumentException("unknown characteristic property " + a.getString(i));
      };
    }
    return p;
  }

  /** A fresh copy of the database for one discovery, as a binder parcel gives each client its own objects. */
  List<BluetoothGattService> gattServices() {
    List<BluetoothGattService> out = new ArrayList<>();
    for (Service s : services) {
      BluetoothGattService gs = new BluetoothGattService(s.uuid, s.handle,
          s.primary ? BluetoothGattService.SERVICE_TYPE_PRIMARY : BluetoothGattService.SERVICE_TYPE_SECONDARY);
      for (Characteristic c : s.characteristics) {
        int perms = 0;
        if ((c.properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) perms |= BluetoothGattCharacteristic.PERMISSION_READ;
        if ((c.properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
          perms |= BluetoothGattCharacteristic.PERMISSION_WRITE;
        }
        BluetoothGattCharacteristic gc = new BluetoothGattCharacteristic(c.uuid, c.handle, c.properties, perms);
        for (Descriptor d : c.descriptors) {
          gc.addDescriptor(new BluetoothGattDescriptor(d.uuid, d.handle,
              BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        }
        gs.addCharacteristic(gc);
      }
      out.add(gs);
    }
    return out;
  }

  Characteristic characteristic(int handle) {
    for (Service s : services) for (Characteristic c : s.characteristics) if (c.handle == handle) return c;
    return null;
  }

  Characteristic characteristic(UUID uuid) {
    for (Service s : services) for (Characteristic c : s.characteristics) if (c.uuid.equals(uuid)) return c;
    return null;
  }

  /** The descriptor at [handle] and its characteristic, or null. */
  Object[] descriptor(int handle) {
    for (Service s : services) {
      for (Characteristic c : s.characteristics) for (Descriptor d : c.descriptors) if (d.handle == handle) return new Object[] {c, d};
    }
    return null;
  }

  /**
   * The advertising data as it goes over the air (AD structures: flags, name, service UUIDs, TX power, service data,
   * manufacturer data), which ScanRecord.parseFromBytes then reads for the app, so getBytes() and every getter agree.
   * Not limited to 31 bytes: an extended advertisement may carry more.
   */
  byte[] advertisingBytes() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    ad(b, 0x01, new byte[] {(byte) (connectable ? 0x06 : 0x04)});  // LE General Discoverable, BR/EDR not supported
    if (advName && name != null) ad(b, 0x09, name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ByteArrayOutputStream u16 = new ByteArrayOutputStream();
    ByteArrayOutputStream u128 = new ByteArrayOutputStream();
    for (UUID u : advServiceUuids) {
      Integer s = shortOf(u);
      if (s != null) {
        u16.write(s & 0xff);
        u16.write((s >> 8) & 0xff);
      } else {
        u128.writeBytes(littleEndian(u));
      }
    }
    if (u16.size() > 0) ad(b, 0x03, u16.toByteArray());
    if (u128.size() > 0) ad(b, 0x07, u128.toByteArray());
    if (txPower != null) ad(b, 0x0A, new byte[] {(byte) (int) txPower});
    for (Map.Entry<UUID, byte[]> e : serviceData.entrySet()) {
      Integer s = shortOf(e.getKey());
      ByteArrayOutputStream d = new ByteArrayOutputStream();
      if (s != null) {
        d.write(s & 0xff);
        d.write((s >> 8) & 0xff);
      } else {
        d.writeBytes(littleEndian(e.getKey()));
      }
      d.writeBytes(e.getValue());
      ad(b, s != null ? 0x16 : 0x21, d.toByteArray());
    }
    for (Map.Entry<Integer, byte[]> e : manufacturerData.entrySet()) {
      ByteArrayOutputStream d = new ByteArrayOutputStream();
      d.write(e.getKey() & 0xff);
      d.write((e.getKey() >> 8) & 0xff);
      d.writeBytes(e.getValue());
      ad(b, 0xFF, d.toByteArray());
    }
    return b.toByteArray();
  }

  private static void ad(ByteArrayOutputStream b, int type, byte[] data) {
    // An AD structure's length is one byte, covering the type too.
    if (data.length > 254) throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
        "advertising data of type 0x%02X is %d bytes; an AD structure holds at most 254", type, data.length));
    b.write(data.length + 1);
    b.write(type);
    b.writeBytes(data);
  }

  private static final long BASE_LSB = 0x800000805F9B34FBL;

  static UUID uuid(String s) {
    String t = s.trim();
    if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
    if (t.length() == 4 || t.length() == 8) return new UUID((Long.parseLong(t, 16) << 32) | 0x1000L, BASE_LSB);
    return UUID.fromString(t);
  }

  static Integer shortOf(UUID u) {
    if (u.getLeastSignificantBits() != BASE_LSB || (u.getMostSignificantBits() & 0xffffffffL) != 0x1000L) return null;
    long v = u.getMostSignificantBits() >>> 32;
    return v <= 0xffff ? (int) v : null;
  }

  static String shortName(UUID u) {
    Integer s = shortOf(u);
    return s != null ? String.format(Locale.ROOT, "%04X", s) : u.toString();
  }

  private static byte[] littleEndian(UUID u) {
    byte[] out = new byte[16];
    long lsb = u.getLeastSignificantBits(), msb = u.getMostSignificantBits();
    for (int i = 0; i < 8; i++) out[i] = (byte) (lsb >>> (8 * i));
    for (int i = 0; i < 8; i++) out[8 + i] = (byte) (msb >>> (8 * i));
    return out;
  }

  static byte[] hex(String s) {
    String t = s.replaceAll("[\\s:-]", "");
    if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
    if (t.length() % 2 != 0) throw new IllegalArgumentException("odd number of hex digits: " + s);
    byte[] out = new byte[t.length() / 2];
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
    return out;
  }

  static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format(Locale.ROOT, "%02X", x & 0xff));
    return sb.toString();
  }

  static String normalizeAddress(String a) {
    String t = a.trim().toUpperCase(Locale.ROOT);
    if (!t.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) throw new IllegalArgumentException("not a Bluetooth address: " + a);
    return t;
  }
}
