package dev.apksim.uiserver;

import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.runner.MonitoringInstrumentation;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A screen-reading server run with `am instrument -w dev.apksim.uiserver/.UiServer &`, as the Android CLI runs its
 * layout server: it reports status 200 once listening on the local socket {@value #SOCKET}, answers an ordered
 * broadcast {@value #GET_INFO} with its version and socket, polls the socket before each accept, and only takes
 * connections from the shell user (adb). One request per line:
 * <ul>
 *   <li>{@code texts}: every text and content description in the windows, one per line, then an empty line;</li>
 *   <li>{@code tap X Y}: a tap at screen pixels X,Y through UiAutomation, then {@code ok};</li>
 *   <li>{@code finish}: {@code ok}, and the instrumentation finishes.</li>
 * </ul>
 */
public class UiServer extends MonitoringInstrumentation {
  static final String SOCKET = "dev.apksim.uiserver";
  static final String GET_INFO = "dev.apksim.uiserver.GET_INFO";
  private volatile boolean finished;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    super.onStart();
    try {
      String version;
      try (InputStream in = getContext().getAssets().open("VERSION")) {
        version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      }
      LocalServerSocket server = new LocalServerSocket(SOCKET);
      getTargetContext().registerReceiver(new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          setResultCode(1);
          setResultData("version=" + version + " socket=" + SOCKET);
        }
      }, new IntentFilter(GET_INFO), Context.RECEIVER_EXPORTED);
      sendStatus(200, new Bundle());
      while (!finished) {
        StructPollfd p = new StructPollfd();
        p.fd = server.getFileDescriptor();
        p.events = (short) OsConstants.POLLIN;
        if (Os.poll(new StructPollfd[] {p}, 1000) == 0) continue;
        LocalSocket client = server.accept();
        int uid = client.getPeerCredentials().getUid();
        if (uid != 2000 && uid != 0 && uid != android.os.Process.myUid()) {
          client.close();
          continue;
        }
        Thread t = new Thread(() -> serve(client), "uiserver-client");
        t.setDaemon(true);
        t.start();
      }
      server.close();
    } catch (Exception e) {
      Bundle b = new Bundle();
      b.putString("error", String.valueOf(e));
      finish(1, b);
    }
  }

  private void serve(LocalSocket client) {
    try (client) {
      BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
      OutputStream raw = client.getOutputStream();
      PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(raw, StandardCharsets.UTF_8), true);
      String line;
      while ((line = in.readLine()) != null) {
        String[] a = line.trim().split(" +");
        switch (a[0]) {
          case "texts" -> {
            for (String s : texts()) out.println(s);
            out.println();
          }
          case "tap" -> {
            tap(Float.parseFloat(a[1]), Float.parseFloat(a[2]));
            out.println("ok");
          }
          case "finish" -> {
            out.println("ok");
            finished = true;
            finish(0, new Bundle());
            return;
          }
          default -> out.println("error: unknown request " + a[0]);
        }
      }
    } catch (Exception e) {
      // The client went away.
    }
  }

  private List<String> texts() {
    UiAutomation ua = getUiAutomation();
    try {
      ua.waitForIdle(100, 2000);
    } catch (java.util.concurrent.TimeoutException e) {
      // Still changing (an animation): read it as it is.
    }
    List<String> out = new ArrayList<>();
    for (AccessibilityWindowInfo w : ua.getWindows()) collect(w.getRoot(), out);
    return out;
  }

  private static void collect(AccessibilityNodeInfo n, List<String> out) {
    if (n == null) return;
    if (n.getText() != null) out.add(n.getText().toString());
    if (n.getContentDescription() != null) out.add(n.getContentDescription().toString());
    for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
  }

  private void tap(float x, float y) {
    UiAutomation ua = getUiAutomation();
    long down = SystemClock.uptimeMillis();
    MotionEvent e = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
    e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    ua.injectInputEvent(e, true);
    e = MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0);
    e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    ua.injectInputEvent(e, true);
  }
}
