package dev.apksim.serve;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a session's log says about why its instance ended, and the JVM's crash files: a short, bounded reason for the
 * tab, the tail of a log as text, and the summary of an `hs_err_pid<N>.log` (its `#` header: the signal and the
 * problematic frame, and the crashing thread's frames; never the registers, memory map or environment), after which the file is deleted. Only log text
 * ever leaves the machine, never a file as such.
 */
final class Crash {
  private static final Pattern EXCEPTION = Pattern.compile("(?:^|\\s)((?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?::\\s.*)?)$");
  private static final Pattern EXIT = Pattern.compile("\\[apksim\\] exit (-?\\d+): (.*)");
  private static final Pattern HS_ERR = Pattern.compile("hs_err_pid(\\d+)\\.log");
  private static final Pattern SIGNAL = Pattern.compile("(SIG[A-Z]+) \\(0x[0-9a-f]+\\)");
  /** Longest line the tab or the log endpoint gets. */
  static final int MAX_LINE = 400;
  /** What `GET /api/sessions/ID/log` returns at most, and what a tab gets when a session ends badly. */
  static final int TAIL_BYTES = 64 * 1024;
  static final int TAIL_LINES = 200;
  static final int TAB_LINES = 50;
  /** How much of a JVM crash report is read, and how many frames of its crashing thread reach the log. */
  static final int REPORT_BYTES = 512 * 1024;
  static final int MAX_NATIVE_FRAMES = 80;
  static final int MAX_JAVA_FRAMES = 60;

  private Crash() {}

  /** Collects the reason while a log is read, line by line. */
  static final class Reason {
    private int fatalLeft;
    private volatile String fatal;
    private volatile String exit;
    private volatile String said;
    private volatile long hsErrPid = -1;
    /** The native loader refused a library and named `--move-shared-data` (ElfLoader), which a tab cannot pass itself. */
    private volatile boolean sharedData;

    void line(String line) {
      if (line.contains("launch with --move-shared-data")) sharedData = true;
      Matcher m = HS_ERR.matcher(line);
      if (m.find()) hsErrPid = Long.parseLong(m.group(1));  // "An error report file ... is saved as: .../hs_err_pid123.log"
      m = EXIT.matcher(line);
      if (m.find() && !m.group(1).equals("0")) exit = "exit " + m.group(1) + ": " + clip(m.group(2).strip());
      if (line.startsWith("[apksim] ") && !line.matches("\\[apksim\\] (jvm-main|control port|data slot|saved|translated|exit|permission).*")) {
        said = clip(line.substring(9).strip());  // e.g. "--activity .X: no activity ... in the manifest"
      }
      if (line.contains("FATAL EXCEPTION")) {
        fatalLeft = 8;
        return;
      }
      if (fatalLeft > 0) {
        fatalLeft--;
        // A qualified class name, then ": message" or the end of the line (any name: R8 renames `a.b`, and a class need not
        // end in Exception); the header's own "Process: pkg, PID: n" and stack frames ("at ...") are not it.
        m = EXCEPTION.matcher(line);
        if (fatal == null && !line.contains("Process:") && !line.contains("\tat ") && m.find()) {
          fatal = clip(m.group(1).strip());
          fatalLeft = 0;
        }
      }
    }

    /** The pid in an hs_err file name the JVM printed (the JVM's own, whatever process we started), or -1. */
    long hsErrPid() {
      return hsErrPid;
    }

    /** The class of the uncaught exception the log shows (as it printed it, R8-renamed or not), or null. */
    String exception() {
      String f = fatal;
      if (f == null) return null;
      int c = f.indexOf(':');
      return (c < 0 ? f : f.substring(0, c)).strip();
    }

    /** "uncaught exception ...", "exit 137: ...", or null. */
    String text() {
      String t = fatal != null ? "uncaught exception " + fatal : exit != null && !exit.endsWith(": ") ? exit : said;
      // The loader's advice names a launch flag: say where a serve operator sets it instead.
      if (t != null && sharedData) {
        t += " (a 4 KB-aligned native library: the server's operator can add --move-shared-data to this app's launch"
            + " options, or start serve with --move-shared-data)";
      }
      return t;
    }
  }

  /**
   * The structured form of why a session ended, sent as `e {...}` just before the plain `x TEXT` (issue 418): `reason`
   * is `crash`, `exit`, `idle`, `busy`, `no-app`, `error` or `other`; `exception` is the uncaught exception's class when
   * the log showed one; `rec` the crash bundle's recording id, when one was saved. A page that does not know `e` ignores it and has `x`.
   */
  static String endJson(String reason, String text, String exception) {
    return endJson(reason, text, exception, null);
  }

  /** With `rec`: the id of the crash bundle the session saved (issue 516), downloadable like a `rec save` recording. */
  static String endJson(String reason, String text, String exception, String rec) {
    return "{\"reason\":" + Http.q(reason) + ",\"text\":" + Http.q(text) + (exception != null ? ",\"exception\":" + Http.q(exception) : "")
        + (rec != null ? ",\"rec\":" + Http.q(rec) : "") + "}";
  }

  static String clip(String s) {
    String t = clean(s);
    return t.length() > MAX_LINE ? t.substring(0, MAX_LINE) + "..." : t;
  }

  /** Control characters out (a log line is shown in a page and sent as one WebSocket text message). */
  static String clean(String s) {
    return s.replaceAll("[\\p{Cntrl}&&[^\t]]", "?");
  }

  /** The last `lines` lines (and at most `bytes`) of a text file, each cleaned and clipped; empty when it cannot be read. */
  static List<String> tail(Path file, int lines, int bytes) {
    List<String> out = new ArrayList<>();
    try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
      long len = f.length();
      long from = Math.max(0, len - bytes);
      byte[] b = new byte[(int) (len - from)];
      f.seek(from);
      f.readFully(b);
      String[] all = new String(b, StandardCharsets.UTF_8).split("\n", -1);
      int start = from > 0 ? 1 : 0;  // the first line may be cut
      int end = all.length > 0 && all[all.length - 1].isEmpty() ? all.length - 1 : all.length;
      for (int i = Math.max(start, end - lines); i < end; i++) out.add(clip(all[i]));
    } catch (IOException | RuntimeException e) {
      // no log
    }
    return out;
  }

  /**
   * The summary ({@link #hsErrLines}) of each `hs_err_pid<pid>.log` in `dir` (the JVM writes it to its working directory, which is the logs
   * folder), as lines for the session's log, and deletes the file. Empty when there is none.
   */
  static List<String> takeHsErr(Path dir, long... pids) {
    List<String> out = new ArrayList<>();
    for (long pid : pids) {
      if (pid <= 0) continue;
      Path f = dir.resolve("hs_err_pid" + pid + ".log");
      if (!Files.isRegularFile(f)) continue;
      try (java.io.InputStream in = Files.newInputStream(f)) {
        out.addAll(hsErrLines(new String(in.readNBytes(REPORT_BYTES), StandardCharsets.ISO_8859_1)));
      } catch (IOException e) {
        out.add("[hs_err] unreadable: " + e.getMessage());
      }
      try {
        Files.deleteIfExists(f);
      } catch (IOException e) {
        // swept later
      }
    }
    return out;
  }

  /**
   * One report's lines for the log: its `#` header (20 lines at most), then the crashing thread (issue 523): its
   * `Current thread`, `Stack:` and `siginfo:` lines first, so a deep stack cannot push the fault address out, then
   * `Native frames:` and `Java frames:`, each cut at its own limit. Under {@link #TAIL_LINES} in all, so the log
   * endpoint's tail still starts at the header. Never the registers, memory map or environment.
   */
  static List<String> hsErrLines(String text) {
    String[] lines = text.split("\n", -1);
    List<String> header = new ArrayList<>();
    List<String> thread = new ArrayList<>();
    List<String> natives = new ArrayList<>();
    List<String> javas = new ArrayList<>();
    int i = 0;
    for (; i < lines.length; i++) {
      String l = lines[i];
      if (l.startsWith("#")) {
        if (header.size() < 20 && !l.equals("#")) header.add("[hs_err] " + clip(l));
      } else if (!header.isEmpty() || !l.isBlank()) {
        break;  // the header ends at the first line that is not one of its comments
      }
    }
    List<String> section = null;
    int limit = 0;
    boolean nativeCut = false;
    boolean javaCut = false;
    for (; i < lines.length; i++) {
      String l = lines[i];
      if (l.startsWith("Native frames:")) {
        section = natives;
        limit = MAX_NATIVE_FRAMES + 1;  // the section's own first line and its frames
      } else if (l.startsWith("Java frames:")) {
        section = javas;
        limit = MAX_JAVA_FRAMES + 1;
      } else if (l.startsWith("siginfo:") || l.startsWith("Current thread") || l.startsWith("Stack:")) {
        section = thread;
        limit = 6;
      } else if (l.isBlank() || l.startsWith("---") || l.startsWith("Registers:") || l.startsWith("Top of Stack")
          || l.startsWith("Instructions:") || l.startsWith("Lock stack")) {
        section = null;
        continue;
      }
      if (section == null) continue;
      if (section.size() < limit) section.add("[hs_err] " + clip(l));
      else if (section == natives) nativeCut = true;
      else if (section == javas) javaCut = true;
    }
    if (nativeCut) natives.add("[hs_err] ... (more native frames cut)");
    if (javaCut) javas.add("[hs_err] ... (more Java frames cut)");
    List<String> out = new ArrayList<>(header);
    out.addAll(thread);
    out.addAll(natives);
    out.addAll(javas);
    return out;
  }

  /**
   * The reason out of {@link #takeHsErr}'s lines, as the launcher's `--crash-summary` words it: "native crash: SIGSEGV in
   * libfoo.so (bar+0x10)" (library and function of a C or VM problematic frame; a Java frame has no library), or null.
   */
  static String hsErrReason(List<String> lines) {
    String signal = null;
    String frame = null;
    for (int i = 0; i < lines.size(); i++) {
      String l = lines.get(i).replaceFirst("^\\[hs_err\\] #\\s*", "");
      Matcher m = SIGNAL.matcher(l);
      if (signal == null && m.find()) signal = m.group(1);
      if (l.startsWith("Problematic frame") && i + 1 < lines.size()) {
        frame = lines.get(i + 1).replaceFirst("^\\[hs_err\\] #\\s*", "").replaceFirst("^[A-Za-z] +", "");
      }
    }
    if (signal == null && frame == null) return null;
    String where = null;
    if (frame != null) {
      Matcher m = Pattern.compile("^\\[([^\\]]*?)(?:\\+0x[0-9a-f]+)?\\]\\s*(.*)$").matcher(frame);
      String fn = frame;
      if (m.find()) {
        fn = m.group(2).replaceAll("\\s+", " ").strip();
        where = m.group(1) + (fn.isEmpty() ? "" : " (" + fn + ")");
      } else {
        where = fn.replaceAll(" @ .*", "").replaceAll("\\s+", " ").strip();
      }
    }
    return "native crash: " + (signal == null ? "fatal error" : signal) + (where == null ? "" : " in " + where);
  }

  /** Crash files left by earlier runs (core dumps, crash reports) older than a day, and logs older than `logDays`. */
  static void sweep(Path logs, int logDays) {
    long dayAgo = System.currentTimeMillis() - 24 * 3_600_000L;
    long logCutoff = System.currentTimeMillis() - logDays * 24 * 3_600_000L;
    try (DirectoryStream<Path> d = Files.newDirectoryStream(logs)) {
      for (Path p : d) {
        String n = p.getFileName().toString();
        boolean crash = n.startsWith("core") || n.startsWith("hs_err_pid");
        boolean log = (n.endsWith(".log") || n.endsWith(".access")) && !n.startsWith("hs_err_pid");
        if (!Files.isRegularFile(p)) continue;
        long m = Files.getLastModifiedTime(p).toMillis();
        if (crash && m < dayAgo || log && m < logCutoff) Files.deleteIfExists(p);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[serve] could not sweep logs: " + e);
    }
  }

  /** Flight recordings tabs saved (`rec-<session>` folders beside the logs, issue 187) older than `days`. */
  static void sweepRecordings(Path logs, int days) {
    long cutoff = System.currentTimeMillis() - days * 24 * 3_600_000L;
    try (DirectoryStream<Path> d = Files.newDirectoryStream(logs, "rec-*")) {
      for (Path dir : d) {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.getLastModifiedTime(dir).toMillis() >= cutoff) continue;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
          for (Path f : files) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(dir);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[serve] could not sweep recordings: " + e);
    }
  }
}
