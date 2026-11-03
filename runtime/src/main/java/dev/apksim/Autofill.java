package dev.apksim;

import android.graphics.Rect;
import android.view.View;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Signs an app in on launch from values its owner saved, like a password manager's autofill or HomeSafe's own
 * debug-build "fill test credentials" button, so that restarting an instance does not need a person at the
 * keyboard. The owner writes work/autofill/PACKAGE.properties (scripts/signin.sh --save does it from 1Password):
 *
 * <pre>
 * Server\ URL=https://example:8971
 * Username=...
 * Password=...
 * submit=Connect
 * </pre>
 *
 * Each key names a field by the text a person would read for it: its placeholder or hint, or the label just above
 * it. Fields are found through the accessibility tree (framework API, so minified apps work too), filled with
 * ACTION_SET_TEXT, and then the button whose text is `submit` is pressed. It signs in at most once per launch, only
 * when every named field is on screen, and logs which fields it filled, never their values.
 */
final class Autofill {
  private final Map<String, String> fields = new LinkedHashMap<>();
  private String submit;
  private boolean done;
  private long nextLookNanos;
  private long seenSinceNanos;  // When every field was first on screen, unchanged since; 0 when not.
  private String lastTexts;
  private int fills;
  private Map<String, Nodes.Node> filled;  // The nodes filled last, found again by identity: a filled field loses its placeholder.
  private Nodes.Node filledButton;

  private Autofill() {}

  /** The autofill for this package, or null when its owner saved none. */
  static Autofill load(Path cacheRoot, String pkg) {
    Path file = cacheRoot.getParent().resolve("autofill").resolve(pkg + ".properties");
    if (!Files.isReadable(file)) return null;
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(file)) {
      p.load(r);
    } catch (Exception e) {
      System.err.println("[apksim] autofill: could not read " + file.getFileName());
      return null;
    }
    Autofill a = new Autofill();
    for (String key : p.stringPropertyNames()) {
      if (key.equals("submit")) a.submit = p.getProperty(key);
      else a.fields.put(key, p.getProperty(key));
    }
    if (a.fields.isEmpty()) return null;
    System.err.println("[apksim] autofill ready for " + pkg + ": " + String.join(", ", a.fields.keySet())
        + (a.submit != null ? ", then " + a.submit : ""));
    return a;
  }

  /**
   * Called from the main loop; looks at the screen twice a second until it has signed in once. Apps often fill
   * their own form a moment after it appears (a remembered server, say), so it waits until the fields have sat
   * unchanged for 1.5 s, fills them, checks on the next look that the values stuck (filling again up to three
   * times), and only then presses the button.
   */
  void tick(List<View> roots) {
    long now = System.nanoTime();
    if (done || now < nextLookNanos) return;
    nextLookNanos = now + 500_000_000L;
    if (fills > 0) {
      verify();
      return;
    }
    List<Nodes.Node> all = new ArrayList<>();
    Nodes.walk(roots, all::add);
    Map<String, Nodes.Node> found = new LinkedHashMap<>();
    StringBuilder texts = new StringBuilder();
    for (String key : fields.keySet()) {
      Nodes.Node field = fieldFor(key, all);
      if (field == null || found.containsValue(field)) {
        seenSinceNanos = 0;  // Not this screen, or not yet.
        return;
      }
      found.put(key, field);
      texts.append(field.text()).append('\0');
    }
    Nodes.Node button = submit == null ? null : buttonFor(submit, all);
    if (submit != null && button == null) return;
    if (seenSinceNanos == 0 || !texts.toString().equals(lastTexts)) {
      seenSinceNanos = now;
      lastTexts = texts.toString();
      return;
    }
    if (now - seenSinceNanos < 1_500_000_000L) return;
    filled = found;
    filledButton = button;
    fill();
  }

  private void fill() {
    fills++;
    for (Map.Entry<String, Nodes.Node> e : filled.entrySet()) e.getValue().setText(fields.get(e.getKey()));
  }

  /** Filled last time: did it stick? Password fields may not report their text, so only the others are checked. */
  private void verify() {
    boolean stuck = true;
    for (Map.Entry<String, Nodes.Node> e : filled.entrySet()) {
      Nodes.Node now = e.getValue().refresh();
      if (now == null) {
        done = true;
        System.err.println("[apksim] autofill: the form went away before it could be checked");
        return;
      }
      if (!now.info.isPassword() && !now.text().equals(fields.get(e.getKey()))) stuck = false;
    }
    if (stuck) {
      done = true;
      boolean pressed = filledButton != null && filledButton.click();
      System.err.println("[apksim] autofill: filled " + String.join(", ", filled.keySet())
          + (filledButton == null ? "" : pressed ? "; pressed " + submit : "; could not press " + submit));
    } else if (fills == 3) {
      done = true;
      System.err.println("[apksim] autofill: the app kept replacing the values; gave up without pressing " + submit);
    } else {
      fill();
    }
  }

  private static String norm(String s) {
    return s.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean says(Nodes.Node n, String key) {
    String k = norm(key);
    return norm(n.text()).equals(k) || norm(n.hint()).equals(k) || norm(n.description()).equals(k);
  }

  /** An editable node that says [key] itself, holds a placeholder saying it, or sits just below a label saying it. */
  private static Nodes.Node fieldFor(String key, List<Nodes.Node> all) {
    Rect fb = new Rect();
    Rect tb = new Rect();
    for (Nodes.Node f : all) {
      if (!f.info.isEditable()) continue;
      if (says(f, key)) return f;
      f.info.getBoundsInScreen(fb);
      for (Nodes.Node t : all) {
        if (t == f || t.info.isEditable() || !says(t, key)) continue;
        t.info.getBoundsInScreen(tb);
        if (fb.contains(tb)) return f;  // Placeholder inside the field.
      }
    }
    Nodes.Node best = null;
    int bestGap = Integer.MAX_VALUE;
    for (Nodes.Node label : all) {
      if (label.info.isEditable() || !says(label, key)) continue;
      label.info.getBoundsInScreen(tb);
      for (Nodes.Node f : all) {
        if (!f.info.isEditable()) continue;
        f.info.getBoundsInScreen(fb);
        int gap = fb.top - tb.bottom;
        boolean below = gap >= -4 && gap < 120 && fb.left < tb.right && fb.right > tb.left;
        if (below && gap < bestGap) {
          best = f;
          bestGap = gap;
        }
      }
    }
    return best;
  }

  /** A clickable node that says [text], or contains a node that does. */
  private static Nodes.Node buttonFor(String text, List<Nodes.Node> all) {
    Rect cb = new Rect();
    Rect tb = new Rect();
    for (Nodes.Node c : all) {
      if (!c.info.isClickable() || c.info.isEditable()) continue;
      if (says(c, text)) return c;
      c.info.getBoundsInScreen(cb);
      for (Nodes.Node t : all) {
        if (t == c || !says(t, text)) continue;
        t.info.getBoundsInScreen(tb);
        if (cb.contains(tb)) return c;
      }
    }
    return null;
  }
}
