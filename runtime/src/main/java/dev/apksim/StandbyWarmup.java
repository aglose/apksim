package dev.apksim;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.apksim.gpu.GpuCanvas;
import dev.apksim.gpu.GpuRenderNode;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * What a {@link Standby} runs before it waits: one test with no APK (Robolectric's default application). It builds the
 * sandbox {@link Host} will reuse, loads the native runtime, sets up an application, and then goes through what every
 * app's first frame goes through in the framework: an activity, its window, views, text, drawing, and HWUI rendering.
 * Robolectric's framework calls the shadows through invokedynamic call sites that are linked on first use, and
 * classes load on first use; both stay done for the launch. Robolectric then resets its state, as between any two
 * tests. No app code is involved. The annotations must match Host's, or Host gets a sandbox of its own.
 */
@RunWith(SimRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class StandbyWarmup {
  private static final int ROUNDS = Integer.getInteger("apksim.standbyWarmRounds", 3);

  @Test
  public void warm() {
    android.app.Application app = RuntimeEnvironment.getApplication();
    Timing.detail("warm-app");
    try {
      // What Host sets up for every app (registrations are reset after this test; classes and libraries stay).
      ClassCache.runtimeHash();
      InstallState.warm(app);
      conscrypt();
      dev.apksim.video.ShadowSimMediaCodec.install();
      javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
          "<manifest package=\"a\"><application><activity android:name=\".A\"/></application></manifest>".getBytes()))
          .getElementsByTagName("activity");
    } catch (Exception e) {
      System.err.println("[apksim] standby: warm-up step failed: " + e);
    }
    for (int round = 0; round < ROUNDS; round++) {
      try {
        activityRound(round);
      } catch (Throwable t) {
        // A warm-up that cannot run fully only makes the launch less warm.
        System.err.println("[apksim] standby: warm-up round " + round + " stopped: " + t);
        t.printStackTrace();
        return;
      }
      Timing.detail("warm-round-" + round);
    }
  }

  /**
   * Conscrypt's native library (Robolectric's conscrypt-openjdk), loaded once per process: OkHttp's Android platform
   * asks Conscrypt.isAvailable() when it starts, which extracts the library from its jar and loads it (~125 ms). Since
   * running instances create the app's content providers before Application.onCreate, as a device does, that is AndroidX
   * Startup's OkHttp initializer in a launch's first 0.3 s. Only checked: no security provider is installed.
   */
  private static void conscrypt() {
    try {
      Class.forName("org.conscrypt.Conscrypt", true, StandbyWarmup.class.getClassLoader()).getMethod("isAvailable").invoke(null);
    } catch (ReflectiveOperationException | LinkageError e) {
      System.err.println("[apksim] standby: conscrypt: " + e);
    }
  }

  private static void activityRound(int round) {
    ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
    Activity activity = controller.get();
    activity.setContentView(content(activity));
    View decor = activity.getWindow().getDecorView();
    int w = 822;
    int h = 1782;
    HwRenderer hw = new HwRenderer(activity, w, h);
    try {
      for (int frame = 0; frame < 6; frame++) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
        hw.draw(List.of(decor), v -> new int[] {0, 0});
        touch(decor, frame);
        decor.invalidate();
      }
      hw.finish();
    } finally {
      hw.close();
    }
    controller.pause().stop().destroy();
    ShadowLooper.idleMainLooper();
    if (round == 0) Timing.detail("warm-first-activity");
  }

  private static void touch(View root, int frame) {
    long t = android.os.SystemClock.uptimeMillis();
    float x = 100 + frame * 10;
    float y = 300 + frame * 20;
    MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent move = MotionEvent.obtain(t, t + 8, MotionEvent.ACTION_MOVE, x, y + 30, 0);
    MotionEvent up = MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_UP, x, y + 30, 0);
    root.dispatchTouchEvent(down);
    root.dispatchTouchEvent(move);
    root.dispatchTouchEvent(up);
    down.recycle();
    move.recycle();
    up.recycle();
  }

  private static View content(Activity a) {
    ScrollView scroll = new ScrollView(a);
    LinearLayout col = new LinearLayout(a);
    col.setOrientation(LinearLayout.VERTICAL);
    col.setPadding(32, 32, 32, 32);
    scroll.addView(col);
    TextView title = new TextView(a);
    title.setText("Warm-up");
    title.setTextSize(28);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    col.addView(title);
    SpannableString span = new SpannableString("Styled text with colour, bold and wrapping across a few lines of the screen's width");
    span.setSpan(new ForegroundColorSpan(Color.RED), 0, 6, 0);
    span.setSpan(new StyleSpan(Typeface.BOLD), 12, 16, 0);
    TextView body = new TextView(a);
    body.setText(span);
    body.setTextSize(16);
    col.addView(body);
    EditText edit = new EditText(a);
    edit.setHint("Hint");
    edit.setText("Edit");
    col.addView(edit);
    edit.requestFocus();
    Button button = new Button(a);
    button.setText("Button");
    button.setOnClickListener(v -> v.setAlpha(0.9f));
    col.addView(button);
    CheckBox check = new CheckBox(a);
    check.setText("Check");
    col.addView(check);
    ImageView image = new ImageView(a);
    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {Color.BLUE, Color.CYAN});
    g.setCornerRadius(24);
    image.setImageDrawable(g);
    col.addView(image, new LinearLayout.LayoutParams(200, 120));
    FrameLayout layer = new FrameLayout(a);
    layer.setElevation(12);
    layer.setClipToOutline(true);
    layer.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.WHITE);
    bg.setCornerRadius(32);
    layer.setBackground(bg);
    layer.addView(new Drawing(a), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600, Gravity.TOP));
    col.addView(layer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600));
    return scroll;
  }

  /** Draws as Compose does: into RenderNodes (graphicsLayer), with the canvas calls the translator rewrites. */
  private static final class Drawing extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
    private final RenderNode node = new GpuRenderNode("warm");

    Drawing(Activity a) {
      super(a);
      text.setTextSize(40);
      new Canvas(bitmap).drawColor(Color.MAGENTA);
    }

    @Override
    protected void onDraw(Canvas c) {
      int w = Math.max(1, getWidth());
      node.setPosition(0, 0, w, 300);
      node.setAlpha(0.95f);
      node.setTranslationY(4);
      node.setElevation(6);
      Outline outline = new Outline();
      outline.setRoundRect(0, 0, w, 300, 24);
      node.setOutline(outline);
      node.setClipToOutline(true);
      Canvas rc = node.beginRecording(w, 300);
      try {
        paint.setShader(new LinearGradient(0, 0, w, 0, Color.YELLOW, Color.GREEN, Shader.TileMode.CLAMP));
        GpuCanvas.drawRect(rc, 0, 0, w, 300, paint);
        paint.setShader(null);
        paint.setColor(Color.DKGRAY);
        GpuCanvas.drawRoundRect(rc, 10, 10, 200, 120, 16, 16, paint);
        GpuCanvas.drawCircle(rc, 260, 70, 50, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        path.reset();
        path.moveTo(10, 200);
        path.cubicTo(100, 100, 200, 300, 300, 200);
        GpuCanvas.drawPath(rc, path, paint);
        paint.setStyle(Paint.Style.FILL);
        rc.drawBitmap(bitmap, new Rect(0, 0, 64, 64), new RectF(320, 20, 448, 148), paint);
        rc.save();
        rc.clipRect(0, 0, w / 2f, 300);
        rc.rotate(5);
        StaticLayout layout = StaticLayout.Builder.obtain("Text laid out by StaticLayout, as Compose's text does", 0, 52, text, w - 40).build();
        rc.translate(20, 160);
        layout.draw(rc);
        rc.restore();
        text.measureText("measure");
        text.getFontMetrics();
      } finally {
        node.endRecording();
      }
      c.drawRenderNode(node);
      c.drawText("Canvas text", 20, 360, text);
    }
  }
}
