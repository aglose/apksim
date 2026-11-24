package dev.apksim.serve;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A real device as an apksim instance (issue 626, docs/beam.md "Device sessions"): a plain JVM the agent starts in
 * place of the launcher for a device session. It runs scrcpy's server on the device (pushed to /data/local/tmp,
 * started with app_process, its video and control sockets reached through `adb forward`) and serves the instance's
 * own protocol on a loopback control port, printed as `[apksim] control port N` as Main prints it: a `stream` line
 * gets the device's H.264 in FrameStream's frames, and input commands become scrcpy's control messages. So
 * {@link Session} relays it to the tab as it relays a simulated screen, acks, pacing, viewers and all.
 *
 * <p>Wire details read from scrcpy 5.0.1's server (checked against a Pixel on Android 17): with tunnel_forward the
 * first socket gets a dummy byte, then the device's name (64 bytes, NUL padded) and the codec (4 bytes, "h264"); every
 * packet has a 12-byte header, a session packet (top bit set: the next 4+4 bytes are the width and height, after a
 * start, a reset or a rotation) or a media one (int64: bit 62 config, bit 61 keyframe, the rest the time; then the
 * payload size). The
 * SPS and PPS come as a config packet of their own: they are put in front of the next keyframe, as the page wants
 * them (one access unit per frame). The server deletes its jar from the device when it exits, so it is pushed for each
 * start, under a name of this session's own (a scrcpy the device's owner runs keeps its own file).
 *
 * <p>The device is left as it was: the app stays installed, the server and its jar go with the session (a device
 * dropped mid-session keeps the jar until the next session's start removes it), and after a rotation asked for here
 * the rotation settings are put back as the session found them. The app is started with `am start` (Monkey, which
 * `beam to-device` uses, turns auto-rotate on).
 */
public final class DeviceBridge {
  private final String serial, pkg, activity;
  private final DeviceTarget.Server server;
  private final String scid;
  private final String jarOnDevice;
  private volatile int forwardPort;
  private volatile Process serverProcess;
  private Socket video, control;
  private DataOutputStream controlOut;
  private volatile int width, height;
  private volatile byte[] config = new byte[0];
  private volatile Subscriber subscriber;
  private volatile boolean ending;
  private volatile Process logcat;
  /** The rotation settings (auto-rotate, user rotation) as the session found them, and whether it changed them. */
  private volatile boolean rotated;
  private volatile String[] rotationWas;

  private DeviceBridge(String serial, String pkg, String activity, DeviceTarget.Server server) {
    this.serial = serial;
    this.pkg = pkg;
    this.activity = activity;
    this.server = server;
    // scrcpy's session id: 31 bits in hex. Names the socket and this session's jar, and finds its process at the end.
    this.scid = String.format("%08x", new java.security.SecureRandom().nextInt() & 0x7fffffff);
    this.jarOnDevice = "/data/local/tmp/apksim-scrcpy-" + scid + ".jar";
  }

  /** `DeviceBridge --serial S --package P [--activity A]`: scrcpy's server is found as {@link DeviceTarget#server}. */
  public static void main(String[] args) {
    String serial = null, pkg = null, activity = null;
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--serial" -> serial = args[i + 1];
        case "--package" -> pkg = args[i + 1];
        case "--activity" -> activity = args[i + 1];
        default -> {
          System.out.println("[apksim] exit 2: device bridge: unknown option " + args[i]);
          System.exit(2);
        }
      }
    }
    if (serial == null || !serial.matches(DeviceHandoff.SERIAL) || pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        || activity != null && !activity.matches("[A-Za-z0-9_.$]{1,300}")) {
      System.out.println("[apksim] exit 2: device bridge: usage: --serial SERIAL --package PACKAGE [--activity CLASS]");
      System.exit(2);
    }
    DeviceTarget.Server s = DeviceTarget.server();
    if (s == null) die(DeviceTarget.SERVER_MISSING);
    DeviceBridge b = new DeviceBridge(serial, pkg, activity, s);
    Runtime.getRuntime().addShutdownHook(new Thread(b::cleanup, "apksim-device-cleanup"));
    try {
      b.run();
    } catch (IOException | RuntimeException e) {
      die("device " + serial + ": " + e.getMessage());
    }
  }

  private static void die(String why) {
    System.out.println("[apksim] exit 1: " + why);
    System.out.flush();
    System.exit(1);
  }

  private void run() throws IOException {
    String state = DeviceTarget.state(serial);
    String refused = DeviceTarget.refusal(serial, state);
    if (refused != null) die(refused);
    // The rotation settings as found, put back at the end if this session rotated the display.
    rotationWas = new String[] {
        DeviceTarget.shell(serial, "settings get system accelerometer_rotation"),
        DeviceTarget.shell(serial, "settings get system user_rotation")};
    // Jars of earlier sessions that lost their device before they could clean up (no server of theirs runs).
    DeviceTarget.shell(serial, "for f in /data/local/tmp/apksim-scrcpy-*.jar; do [ -e \"$f\" ] || continue; s=${f##*-}; s=${s%.jar};"
        + " pgrep -f \"scid[=]$s \" >/dev/null || rm -f \"$f\"; done");
    say("starting scrcpy " + server.version() + "'s server on " + serial);
    DeviceTarget.Result push = DeviceTarget.run(serial, List.of("push", server.jar().toString(), jarOnDevice), 60);
    if (push.code() != 0) throw new IOException("could not push scrcpy's server: " + push.out().strip());
    DeviceTarget.Result fwd = DeviceTarget.run(serial, List.of("forward", "tcp:0", "localabstract:scrcpy_" + scid), 15);
    try {
      forwardPort = Integer.parseInt(fwd.out().strip());
    } catch (NumberFormatException e) {
      throw new IOException("adb forward failed: " + fwd.out().strip());
    }
    List<String> cmd = new ArrayList<>(List.of(DeviceTarget.adb(), "-s", serial, "shell",
        "CLASSPATH=" + jarOnDevice + " app_process / com.genymobile.scrcpy.Server " + server.version()
            + " scid=" + scid + " log_level=info audio=false control=true tunnel_forward=true video_codec=h264"
            + " clipboard_autosync=false cleanup=false"));
    serverProcess = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
    Process sp = serverProcess;
    Thread.ofVirtual().name("device-server-log").start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(sp.getInputStream(), StandardCharsets.UTF_8))) {
        for (String l; (l = r.readLine()) != null; ) say("scrcpy: " + l);
      } catch (IOException e) {
        // ended
      }
    });
    // The forward accepts at once and closes when nothing listens yet on the device: a dummy byte says the server is up.
    long deadline = System.currentTimeMillis() + 15_000;
    while (true) {
      if (!sp.isAlive()) throw new IOException("scrcpy's server ended before it started (see the log)");
      Socket v = new Socket(InetAddress.getLoopbackAddress(), forwardPort);
      v.setTcpNoDelay(true);
      int dummy = v.getInputStream().read();
      if (dummy >= 0) {
        video = v;
        break;
      }
      v.close();
      if (System.currentTimeMillis() > deadline) throw new IOException("scrcpy's server did not answer in 15 s");
      sleep(100);
    }
    control = new Socket(InetAddress.getLoopbackAddress(), forwardPort);
    control.setTcpNoDelay(true);
    controlOut = new DataOutputStream(new java.io.BufferedOutputStream(control.getOutputStream()));
    DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(video.getInputStream(), 1 << 16));
    byte[] name = new byte[64];
    in.readFully(name);
    byte[] codec = new byte[4];
    in.readFully(codec);
    String model = new String(name, StandardCharsets.UTF_8).replace("\0", "").strip();
    if (!new String(codec, StandardCharsets.US_ASCII).equals("h264")) throw new IOException("the device sends " + new String(codec, StandardCharsets.US_ASCII) + ", not H.264");
    say("mirroring " + model + " (" + serial + ")");
    // Drained from the start: scrcpy writes device messages (clipboard, acks) there, and a full socket would stall it.
    InputStream ci = control.getInputStream();
    Thread.ofVirtual().name("device-control-drain").start(() -> {
      try {
        ci.transferTo(OutputStream.nullOutputStream());
      } catch (IOException e) {
        // ended
      }
    });
    launchApp();
    ServerSocket listen = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    System.out.println("[apksim] control port " + listen.getLocalPort());
    System.out.flush();
    Thread.ofPlatform().daemon().name("device-accept").start(() -> accept(listen));
    Thread.ofPlatform().daemon().name("device-watch").start(this::watch);
    readVideo(in);
  }

  private void launchApp() {
    // A screen asleep or showing its screensaver shows nothing of the app: wake it, as scrcpy's own client does. A
    // secure lock screen stays locked (the device's owner unlocks it on the device or through the stream).
    DeviceTarget.shell(serial, "input keyevent KEYCODE_WAKEUP");
    // The launcher activity resolved and started with am, not `monkey -p PKG 1`: Monkey turns auto-rotate on as it starts.
    String component = activity != null ? pkg + "/" + activity : null;
    if (component == null) {
      String[] r = DeviceTarget.shell(serial, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
          + DeviceHandoff.q(pkg)).split("\n");
      String last = r[r.length - 1].strip();
      if (last.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) component = last;
    }
    if (component == null) {
      say("could not start " + pkg + ": it has no launcher activity");
      return;
    }
    String out = DeviceTarget.shell(serial, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n " + DeviceHandoff.q(component));
    if (out.contains("Error") || out.contains("No activities found")) say("could not start " + pkg + ": " + out.replace('\n', ' '));
    else say("started " + pkg);
  }

  private static void say(String line) {
    System.out.println("[device] " + line);
    System.out.flush();
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------- the screen

  /** The one `stream` connection (the session's): frames from its first keyframe on. */
  private static final class Subscriber {
    final DataOutputStream out;
    boolean waitKey = true;

    Subscriber(OutputStream o) {
      out = new DataOutputStream(new java.io.BufferedOutputStream(o, 1 << 16));
    }
  }

  /** A packet header's flags in scrcpy 5's video stream: a session (size) packet, codec config, a keyframe. */
  private static final long SESSION = 1L << 63, CONFIG = 1L << 62, KEY = 1L << 61;

  private void readVideo(DataInputStream in) {
    try {
      while (!ending) {
        long head = in.readLong();
        int size = in.readInt();
        if ((head & SESSION) != 0) {
          // A session packet: the 4+4 bytes after its flag byte are the new width and height (the size field is the height).
          width = (int) (head & 0xffffffffL);
          height = size;
          continue;
        }
        if (size < 0 || size > 64 << 20) throw new IOException("a packet of " + size + " bytes");
        byte[] payload = new byte[size];
        in.readFully(payload);
        boolean isConfig = (head & CONFIG) != 0, key = (head & KEY) != 0;
        if (isConfig) {
          config = payload;
          continue;
        }
        Subscriber s = subscriber;
        if (s == null) continue;
        byte[] data = payload;
        if (key) {
          byte[] c = config;
          data = new byte[c.length + payload.length];
          System.arraycopy(c, 0, data, 0, c.length);
          System.arraycopy(payload, 0, data, c.length, payload.length);
        }
        synchronized (s) {
          if (s.waitKey && !key) continue;
          s.waitKey = false;
          try {
            long now = System.currentTimeMillis() * 1000;
            s.out.writeInt(4 + 4 + 8 + 4 + 1 + data.length);
            s.out.writeInt(width);
            s.out.writeInt(height);
            s.out.writeLong(now);
            s.out.writeInt(0);
            s.out.writeByte(key ? 1 : 0);
            s.out.write(data);
            s.out.flush();
          } catch (IOException e) {
            if (subscriber == s) subscriber = null;
          }
        }
      }
    } catch (IOException e) {
      if (!ending) gone();
    }
  }

  /** The video ended without the session asking: the device went, or its server did. */
  private void gone() {
    String state = DeviceTarget.state(serial);
    if (!state.equals("device")) die("the device " + serial + " was disconnected (" + (state.equals("missing") ? "no longer attached" : state) + ")");
    die("scrcpy's server on " + serial + " ended (see the log)");
  }

  /** Every 2 s: adb still has the device (a cable pulled can leave the forwarded socket open for a while). */
  private void watch() {
    while (!ending) {
      sleep(2000);
      if (ending) return;
      String state = DeviceTarget.state(serial);
      if (!state.equals("device") && !ending) die("the device " + serial + " was disconnected (" + (state.equals("missing") ? "no longer attached" : state) + ")");
    }
  }

  private void askKey() {
    sendControl(new byte[] {TYPE_RESET_VIDEO});
  }

  // ---------------------------------------------------------------- the control port

  private void accept(ServerSocket listen) {
    while (!ending) {
      try {
        Socket s = listen.accept();
        Thread.ofVirtual().name("device-conn").start(() -> serve(s));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket s) {
    try (s) {
      InputStream in = s.getInputStream();
      OutputStream out = s.getOutputStream();
      String first = readLine(in);
      if (first == null) return;
      if (first.startsWith("stream")) {
        boolean h264 = first.contains(" h264");
        if (!h264) {
          // The device's own encoder sends H.264 only; a tab that cannot decode it gets a reason, not a broken stream.
          out.write("error: a device session needs H.264 (this browser's WebCodecs): JPEG is not offered\n".getBytes(StandardCharsets.US_ASCII));
          out.flush();
          return;
        }
        out.write("ok stream h264\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Subscriber sub = new Subscriber(out);
        subscriber = sub;
        askKey();
        // Its later lines: `key` (a keyframe, please), `pace MS` and `viewport W H` (no use here: the device's encoder
        // keeps its own pace and size).
        for (String l; (l = readLine(in)) != null; ) {
          if (l.equals("key")) {
            synchronized (sub) {
              sub.waitKey = true;
            }
            askKey();
          }
        }
        if (subscriber == sub) subscriber = null;
        return;
      }
      PrintLines w = new PrintLines(out);
      for (String l = first; l != null; l = readLine(in)) {
        String a = answer(l.strip());
        w.line(a);
        if (l.strip().equals("quit")) {
          ending = true;
          Thread.ofVirtual().start(() -> {
            sleep(100);
            System.exit(0);
          });
          return;
        }
        // The instance's other watch connections (hostwatch, audiowatch, netwatch...) end here: none apply to a device.
        if (l.strip().endsWith("watch")) return;
      }
    } catch (IOException e) {
      // the session went away
    }
  }

  private static final class PrintLines {
    final OutputStream out;

    PrintLines(OutputStream out) {
      this.out = out;
    }

    void line(String s) throws IOException {
      out.write((s.replace("\n", "; ") + "\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  private static String readLine(InputStream in) throws IOException {
    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
    for (int c; (c = in.read()) != '\n'; ) {
      if (c < 0) return b.size() == 0 ? null : b.toString(StandardCharsets.UTF_8);
      if (b.size() > 64 * 1024) throw new IOException("a line over 64 KB");
      b.write(c);
    }
    String s = b.toString(StandardCharsets.UTF_8);
    return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
  }

  /** One command's one-line answer, as the instance's Control gives it. */
  String answer(String line) {
    String[] a = line.split(" +");
    if (a.length == 0 || a[0].isEmpty()) return "error: empty command";
    try {
      switch (a[0]) {
        case "auth" -> {
          return "ok";
        }
        case "ping" -> {
          return "pong";
        }
        case "quit" -> {
          return "ok";
        }
        case "stats" -> {
          return "pid=" + ProcessHandle.current().pid() + " heapUsedMb=0 device=" + serial + " width=" + width + " height=" + height;
        }
        case "touch" -> {
          if (a.length != 4) return "error: usage: touch down|move|up|cancel X Y";
          int action = switch (a[1]) {
            case "down" -> 0;
            case "up" -> 1;
            case "move" -> 2;
            case "cancel" -> 3;
            default -> -1;
          };
          if (action < 0) return "error: usage: touch down|move|up|cancel X Y";
          touch(action, Float.parseFloat(a[2]), Float.parseFloat(a[3]));
          return "ok";
        }
        case "tap" -> {
          if (a.length != 3) return "error: usage: tap X Y";
          float x = Float.parseFloat(a[1]), y = Float.parseFloat(a[2]);
          touch(0, x, y);
          touch(1, x, y);
          return "ok";
        }
        case "wheel" -> {
          if (a.length != 4) return "error: usage: wheel X Y AMOUNT";
          scroll(Float.parseFloat(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]));
          return "ok";
        }
        case "key" -> {
          return key(a);
        }
        case "type" -> {
          String text = line.length() > 5 ? line.substring(5) : "";
          if (text.isEmpty()) return "error: usage: type TEXT";
          typeText(text);
          return "ok";
        }
        case "back" -> {
          sendControl(new byte[] {TYPE_BACK_OR_SCREEN_ON, 0});
          sendControl(new byte[] {TYPE_BACK_OR_SCREEN_ON, 1});
          return "ok";
        }
        case "home" -> {
          keycode(0, 3, 0);
          keycode(1, 3, 0);
          return "ok";
        }
        case "resume" -> {
          launchApp();
          return "ok";
        }
        case "rotate" -> {
          return a.length == 2 ? rotate(a[1]) : "error: usage: rotate left|right|portrait|landscape";
        }
        case "open" -> {
          if (a.length != 2) return "error: usage: open URL";
          String out = DeviceTarget.shell(serial, "am start -a android.intent.action.VIEW -d " + DeviceHandoff.q(a[1]));
          return out.contains("Error") ? "error: " + out.replace('\n', ' ') : "ok";
        }
        case "logcat" -> {
          return a.length == 2 ? logcat(a[1]) : "error: usage: logcat on|off";
        }
        case "tabshell" -> {
          return tabshell(a);
        }
        // The tab's pace and visibility: the device's encoder keeps its own (scrcpy sends only changed frames).
        case "vsync", "maxfps", "redraw" -> {
          return "ok";
        }
        default -> {
          return "error: " + a[0] + ": not on a device session";
        }
      }
    } catch (NumberFormatException e) {
      return "error: " + a[0] + ": bad number";
    } catch (IOException e) {
      return "error: " + a[0] + ": " + e.getMessage();
    }
  }

  // ---------------------------------------------------------------- scrcpy's control messages

  private static final byte TYPE_INJECT_KEYCODE = 0, TYPE_INJECT_TEXT = 1, TYPE_INJECT_TOUCH = 2, TYPE_INJECT_SCROLL = 3,
      TYPE_BACK_OR_SCREEN_ON = 4, TYPE_RESET_VIDEO = 17;
  /** scrcpy's pointer id for a finger (its "generic finger": a touch, not a mouse). */
  private static final long FINGER = -2;

  private synchronized void sendControl(byte[] msg) {
    try {
      controlOut.write(msg);
      controlOut.flush();
    } catch (IOException e) {
      // the device is going: the video's end says so
    }
  }

  private void touch(int action, float x, float y) {
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(32);
    b.put(TYPE_INJECT_TOUCH).put((byte) action).putLong(FINGER).putInt(Math.round(x)).putInt(Math.round(y))
        .putShort((short) width).putShort((short) height).putShort((short) (action == 1 ? 0 : 0xffff))
        .putInt(0).putInt(0);
    sendControl(b.array());
  }

  /** apksim's wheel amount is AXIS_VSCROLL's (notches, up positive); scrcpy's is a 16-bit fraction of 16 notches. */
  private void scroll(float x, float y, float amount) {
    float v = Math.max(-16, Math.min(16, amount)) / 16f;
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(21);
    b.put(TYPE_INJECT_SCROLL).putInt(Math.round(x)).putInt(Math.round(y)).putShort((short) width).putShort((short) height)
        .putShort((short) 0).putShort((short) Math.max(-0x8000, Math.min(0x7fff, Math.round(v * 0x8000)))).putInt(0);
    sendControl(b.array());
  }

  private void keycode(int action, int code, int meta) {
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(14);
    b.put(TYPE_INJECT_KEYCODE).put((byte) action).putInt(code).putInt(0).putInt(meta);
    sendControl(b.array());
  }

  private void typeText(String text) {
    // scrcpy takes at most 300 bytes of text a message: whole code points per message.
    StringBuilder chunk = new StringBuilder();
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      String c = new String(Character.toChars(cp));
      if (chunk.toString().getBytes(StandardCharsets.UTF_8).length + c.getBytes(StandardCharsets.UTF_8).length > 300) {
        sendText(chunk.toString());
        chunk.setLength(0);
      }
      chunk.append(c);
      i += Character.charCount(cp);
    }
    if (!chunk.isEmpty()) sendText(chunk.toString());
  }

  private void sendText(String s) {
    byte[] t = s.getBytes(StandardCharsets.UTF_8);
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(5 + t.length);
    b.put(TYPE_INJECT_TEXT).putInt(t.length).put(t);
    sendControl(b.array());
  }

  /** Android's key codes for the names apksim's `key` takes (KeyEvent.KEYCODE_ less its prefix). */
  static final Map<String, Integer> KEYS = keys();

  private static Map<String, Integer> keys() {
    Map<String, Integer> m = new java.util.HashMap<>();
    for (char c = 'A'; c <= 'Z'; c++) m.put(String.valueOf(c), 29 + (c - 'A'));
    for (char c = '0'; c <= '9'; c++) m.put(String.valueOf(c), 7 + (c - '0'));
    Object[][] named = {{"HOME", 3}, {"BACK", 4}, {"CALL", 5}, {"ENDCALL", 6}, {"STAR", 17}, {"POUND", 18}, {"DPAD_UP", 19},
        {"DPAD_DOWN", 20}, {"DPAD_LEFT", 21}, {"DPAD_RIGHT", 22}, {"DPAD_CENTER", 23}, {"VOLUME_UP", 24}, {"VOLUME_DOWN", 25},
        {"POWER", 26}, {"CAMERA", 27}, {"CLEAR", 28}, {"COMMA", 55}, {"PERIOD", 56}, {"ALT_LEFT", 57}, {"ALT_RIGHT", 58},
        {"SHIFT_LEFT", 59}, {"SHIFT_RIGHT", 60}, {"TAB", 61}, {"SPACE", 62}, {"SYM", 63}, {"EXPLORER", 64}, {"ENVELOPE", 65},
        {"ENTER", 66}, {"DEL", 67}, {"GRAVE", 68}, {"MINUS", 69}, {"EQUALS", 70}, {"LEFT_BRACKET", 71}, {"RIGHT_BRACKET", 72},
        {"BACKSLASH", 73}, {"SEMICOLON", 74}, {"APOSTROPHE", 75}, {"SLASH", 76}, {"AT", 77}, {"PLUS", 81}, {"MENU", 82},
        {"SEARCH", 84}, {"MEDIA_PLAY_PAUSE", 85}, {"MEDIA_STOP", 86}, {"MEDIA_NEXT", 87}, {"MEDIA_PREVIOUS", 88},
        {"PAGE_UP", 92}, {"PAGE_DOWN", 93}, {"ESCAPE", 111}, {"FORWARD_DEL", 112}, {"CTRL_LEFT", 113}, {"CTRL_RIGHT", 114},
        {"MOVE_HOME", 122}, {"MOVE_END", 123}, {"INSERT", 124}, {"VOLUME_MUTE", 164}, {"APP_SWITCH", 187}, {"SETTINGS", 176},
        {"SLEEP", 223}, {"WAKEUP", 224}};
    for (Object[] n : named) m.put((String) n[0], (Integer) n[1]);
    return Map.copyOf(m);
  }

  private String key(String[] a) {
    if (a.length < 2 || !a[1].matches("[A-Z0-9_]{1,24}")) return "error: usage: key NAME [COUNT] [ctrl|shift|alt|meta]...";
    int count = 1, meta = 0;
    for (int i = 2; i < a.length; i++) {
      switch (a[i]) {
        case "ctrl" -> meta |= 0x1000 | 0x2000;
        case "shift" -> meta |= 0x1 | 0x40;
        case "alt" -> meta |= 0x2 | 0x10;
        case "meta" -> meta |= 0x10000 | 0x20000;
        default -> {
          if (i == 2 && a[i].matches("[1-9][0-9]?")) count = Integer.parseInt(a[i]);
          else return "error: key: " + a[i];
        }
      }
    }
    Integer code = KEYS.get(a[1]);
    if (code == null) {
      // A name not in the table: the device's own `input` knows every KeyEvent name (no modifiers there).
      for (int i = 0; i < count; i++) DeviceTarget.shell(serial, "input keyevent KEYCODE_" + a[1]);
      return "ok";
    }
    for (int i = 0; i < count; i++) {
      keycode(0, code, meta);
      keycode(1, code, meta);
    }
    return "ok";
  }

  // ---------------------------------------------------------------- rotation, logcat, shell

  /**
   * Turns the display through its rotation settings, as a person locking the rotation would: auto-rotate off and the
   * user rotation set (0 portrait, 1 the device's top on the left, 3 on the right). An app that fixes its own
   * orientation stays as it is, as on the device in a hand. The settings are put back when the session ends.
   */
  private synchronized String rotate(String how) {
    int now = currentRotation();
    int to = switch (how) {
      case "portrait" -> 0;
      case "landscape" -> 1;
      case "left" -> (now + 1) % 4;
      case "right" -> (now + 3) % 4;
      default -> -1;
    };
    if (to < 0) return "error: usage: rotate left|right|portrait|landscape";
    rotated = true;
    DeviceTarget.shell(serial, "settings put system accelerometer_rotation 0; settings put system user_rotation " + to);
    return "ok";
  }

  /** The display's rotation now: the window manager's, else the user rotation setting, else guessed from the frame. */
  private int currentRotation() {
    String d = DeviceTarget.shell(serial, "dumpsys window displays | grep -oE 'mRotation=[0-3]' | head -1");
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("mRotation=([0-3])").matcher(d);
    if (m.find()) return m.group(1).charAt(0) - '0';
    String u = DeviceTarget.shell(serial, "settings get system user_rotation");
    if (u.matches("[0-3]") && DeviceTarget.shell(serial, "settings get system accelerometer_rotation").equals("0")) return u.charAt(0) - '0';
    return width > height ? 1 : 0;
  }

  private synchronized String logcat(String how) throws IOException {
    if (how.equals("off")) {
      Process p = logcat;
      logcat = null;
      if (p != null) p.destroy();
      return "ok";
    }
    if (!how.equals("on")) return "error: usage: logcat on|off";
    if (logcat != null && logcat.isAlive()) return "ok";
    String pid = DeviceTarget.shell(serial, "pidof " + DeviceHandoff.q(pkg)).split("\\s+")[0];
    if (!pid.matches("[0-9]{1,8}")) return "error: logcat: " + pkg + " is not running on the device";
    Process p = logcat = new ProcessBuilder(DeviceTarget.adb(), "-s", serial, "logcat", "-v", "threadtime", "-T", "1", "--pid=" + pid)
        .redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
    Thread.ofVirtual().name("device-logcat").start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        for (String l; (l = r.readLine()) != null; ) {
          System.out.println(l);
        }
      } catch (IOException e) {
        // stopped
      }
    });
    return "ok";
  }

  /**
   * `tabshell limited|full BASE64` (Session.shellCommand), run as `adb shell` on the device. Limited: the lines a
   * simulated session's limited shell takes (am start, am broadcast for the app, settings get|put, cat of a file in
   * the app's own folders), checked by the same rules, and nothing the device's shell would read as more than one
   * command; full: the line as it is, as `adb shell` would run it.
   */
  private String tabshell(String[] a) throws IOException {
    if (a.length != 3 || !(a[1].equals("limited") || a[1].equals("full"))) return "error: usage: tabshell limited|full BASE64";
    String line;
    try {
      line = new String(Base64.getDecoder().decode(a[2]), StandardCharsets.UTF_8).strip();
    } catch (IllegalArgumentException e) {
      return "error: tabshell: bad base64";
    }
    if (a[1].equals("limited")) {
      if (!line.matches("[A-Za-z0-9 ._/:=,+@%-]{1,4000}")) {
        return "ok 126 " + b64("refused: a limited shell takes one plain command (no quotes, pipes, ; & $ or redirections)\n");
      }
      List<String> w = List.of(line.split(" +"));
      String why = dev.apksim.TabShell.refusalFor(w, pkg);
      if (why != null) return "ok 126 " + b64("refused: " + why + "\n");
      if (w.get(0).equals("cat")) {
        String p = w.get(1);
        String own = null;
        for (String root : List.of("/data/data/" + pkg + "/", "/data/user/0/" + pkg + "/")) if (p.startsWith(root)) own = p.substring(root.length());
        if (own == null || own.isEmpty() || own.contains("..") || own.startsWith("/")) {
          return "ok 126 " + b64("refused: cat reads a file in the app's own folders (/data/data/" + pkg + "/...)\n");
        }
        line = "run-as " + pkg + " cat " + own;
      }
    }
    DeviceTarget.Result r = DeviceTarget.run(serial, List.of("shell", line), 60);
    byte[] out = r.out().getBytes(StandardCharsets.UTF_8);
    if (out.length > 256 * 1024) out = java.util.Arrays.copyOf(out, 256 * 1024);
    return "ok " + r.code() + " " + Base64.getEncoder().encodeToString(out);
  }

  private static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------- the end

  /** The server, its forward and jar, logcat, and the rotation settings as they were: the device as it was found. */
  private void cleanup() {
    ending = true;
    Process lc = logcat;
    if (lc != null) lc.destroy();
    try {
      if (control != null) control.close();
      if (video != null) video.close();
    } catch (IOException e) {
      // closing anyway
    }
    if (serverProcess != null) serverProcess.destroy();
    String[] was = rotationWas;
    try {
      // Only this session's server (its scid is on its command line), never a scrcpy the device's owner runs.
      // The pattern's [=] keeps this shell's own command line (which holds it) from matching.
      DeviceTarget.run(serial, List.of("shell", "pkill -f 'scid[=]" + scid + " '; rm -f " + jarOnDevice), 10);
      if (rotated && was != null && was[0].matches("[01]") && was[1].matches("[0-3]")) {
        DeviceTarget.run(serial, List.of("shell", "settings put system user_rotation " + was[1]
            + "; settings put system accelerometer_rotation " + was[0]), 10);
      }
      if (forwardPort > 0) DeviceTarget.run(serial, List.of("forward", "--remove", "tcp:" + forwardPort), 10);
    } catch (IOException e) {
      // the device is gone: nothing of this session's runs on it any more
    }
  }

}
