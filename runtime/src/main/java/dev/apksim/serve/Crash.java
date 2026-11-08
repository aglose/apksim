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
 * problematic frame, never the registers, memory map or environment), after which the file is deleted. Only log text
 * ever leaves the machine, never a file as such.
 */
final class Crash {
  private static final Pattern EXCEPTION = Pattern.compile("((?:[a-z_][\\w]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable)\\b.*)");
  private static final Pattern EXIT = Pattern.compile("\\[apksim\\] exit (\\d+): (.*)");
  private static final Pattern SIGNAL = Pattern.compile("(SIG[A-Z]+) \\(0x[0-9a-f]+\\)");
  /** Longest line the tab or the log endpoint gets. */
  static final int MAX_LINE = 400;
  /** What `GET /api/sessions/ID/log` returns at most, and what a tab gets when a session ends badly. */
  static final int TAIL_BYTES = 64 * 1024;
  static final int TAIL_LINES = 200;
  static final int TAB_LINES = 50;

  private Crash() {}

  /** Collects the reason while a log is read, line by line. */
  static final class Reason {
    private int fatalLeft;
    private volatile String fatal;
    private volatile String exit;
    private volatile String said;

    void line(String line) {
      Matcher m = EXIT.matcher(line);
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
        m = EXCEPTION.matcher(line);
        if (fatal == null && m.find()) {
          fatal = clip(m.group(1).strip());
          fatalLeft = 0;
        }
      }
    }

    /** "uncaught exception ...", "exit 137: ...", or null. */
    String text() {
      if (fatal != null) return "uncaught exception " + fatal;
      if (exit != null && !exit.endsWith(": ")) return exit;
      return said;
    }
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
   * The header of each `hs_err_pid<pid>.log` in `dir` (the JVM writes it to its working directory, which is the logs
   * folder), as lines for the session's log, and deletes the file. Empty when there is none.
   */
  static List<String> takeHsErr(Path dir, long... pids) {
    List<String> out = new ArrayList<>();
    for (long pid : pids) {
      if (pid <= 0) continue;
      Path f = dir.resolve("hs_err_pid" + pid + ".log");
      if (!Files.isRegularFile(f)) continue;
      try {
        for (String l : Files.readAllLines(f, StandardCharsets.ISO_8859_1)) {
          if (l.startsWith("#")) {
            if (out.size() < 20 && !l.equals("#")) out.add("[hs_err] " + clip(l));
          } else if (!out.isEmpty()) {
            break;  // the header ends at the first line that is not one of its comments
          }
        }
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

  /** The signal and frame out of {@link #takeHsErr}'s lines, e.g. "SIGILL (0x4) in C [libz.so+0x1234]", or null. */
  static String hsErrReason(List<String> lines) {
    String signal = null;
    String frame = null;
    for (int i = 0; i < lines.size(); i++) {
      String l = lines.get(i).replaceFirst("^\\[hs_err\\] #\\s*", "");
      Matcher m = SIGNAL.matcher(l);
      if (signal == null && m.find()) signal = m.group();
      if (l.startsWith("Problematic frame") && i + 1 < lines.size()) {
        frame = lines.get(i + 1).replaceFirst("^\\[hs_err\\] #\\s*", "").replaceFirst("^[A-Za-z] +", "");
      }
    }
    if (signal == null && frame == null) return null;
    return (signal == null ? "crash" : signal) + (frame == null ? "" : " in " + frame.replaceAll("\\s+", " "));
  }

  /** Crash files left by earlier runs (core dumps, crash reports) older than a day, and logs older than `logDays`. */
  static void sweep(Path logs, int logDays) {
    long dayAgo = System.currentTimeMillis() - 24 * 3_600_000L;
    long logCutoff = System.currentTimeMillis() - logDays * 24 * 3_600_000L;
    try (DirectoryStream<Path> d = Files.newDirectoryStream(logs)) {
      for (Path p : d) {
        String n = p.getFileName().toString();
        boolean crash = n.startsWith("core") || n.startsWith("hs_err_pid");
        boolean log = n.endsWith(".log") && !n.startsWith("hs_err_pid");
        if (!Files.isRegularFile(p)) continue;
        long m = Files.getLastModifiedTime(p).toMillis();
        if (crash && m < dayAgo || log && m < logCutoff) Files.deleteIfExists(p);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[serve] could not sweep logs: " + e);
    }
  }
}
