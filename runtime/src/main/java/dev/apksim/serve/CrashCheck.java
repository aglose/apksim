package dev.apksim.serve;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks {@link Crash#hsErrLines} and {@link Crash#takeHsErr} on synthetic JDK 24-shaped crash reports
 * (scripts/check-crash.sh, CI; issue 523). Exits 1 if any check fails.
 */
public final class CrashCheck {
  private static int failures;

  private CrashCheck() {}

  public static void main(String[] args) throws Exception {
    List<String> l = Crash.hsErrLines(report(3, 2));
    check("header first", l.get(0).equals("[hs_err] # A fatal error has been detected by the Java Runtime Environment:"));
    check("thread", has(l, "[hs_err] Current thread (0x1):  JavaThread \"main\""));
    check("stack", has(l, "[hs_err] Stack: [0x1,0x2],  sp=0x3"));
    check("siginfo", has(l, "[hs_err] siginfo: si_signo: 11 (SIGSEGV), si_addr: 0x0"));
    check("native frames", has(l, "[hs_err] C  [libn.dylib+0x2]  n2+0x1") && !has(l, "[hs_err] C  [libn.dylib+0x3]  n3+0x1"));
    check("java frames", has(l, "[hs_err] j  A.m1()V+1") && !has(l, "[hs_err] j  A.m2()V+1"));
    check("no cut mark", l.stream().noneMatch(x -> x.contains("cut)")));
    check("siginfo before frames", l.indexOf("[hs_err] siginfo: si_signo: 11 (SIGSEGV), si_addr: 0x0") < l.indexOf("[hs_err] Native frames: (J=compiled Java code)"));
    check("nothing else", l.stream().noneMatch(x -> x.contains("secret") || x.contains("HOME=") || x.contains("x0=")));
    check("reason", "native crash: SIGSEGV in libsystem_platform.dylib (_platform_memmove+0xbc)".equals(Crash.hsErrReason(l)));

    List<String> deep = Crash.hsErrLines(report(500, 500));
    check("deep: siginfo kept", has(deep, "[hs_err] siginfo: si_signo: 11 (SIGSEGV), si_addr: 0x0"));
    check("deep: native limit", has(deep, "[hs_err] C  [libn.dylib+0x" + Integer.toHexString(Crash.MAX_NATIVE_FRAMES - 1) + "]  n" + (Crash.MAX_NATIVE_FRAMES - 1) + "+0x1")
        && !has(deep, "[hs_err] C  [libn.dylib+0x" + Integer.toHexString(Crash.MAX_NATIVE_FRAMES) + "]  n" + Crash.MAX_NATIVE_FRAMES + "+0x1"));
    check("deep: java limit", has(deep, "[hs_err] j  A.m" + (Crash.MAX_JAVA_FRAMES - 1) + "()V+1") && !has(deep, "[hs_err] j  A.m" + Crash.MAX_JAVA_FRAMES + "()V+1"));
    check("deep: cut marks", has(deep, "[hs_err] ... (more native frames cut)") && has(deep, "[hs_err] ... (more Java frames cut)"));
    check("deep: under the log's tail, " + deep.size(), deep.size() + 2 < Crash.TAIL_LINES);
    check("deep: reason", "native crash: SIGSEGV in libsystem_platform.dylib (_platform_memmove+0xbc)".equals(Crash.hsErrReason(deep)));

    check("no header", Crash.hsErrLines("not a report\n# a comment later\nHOME=/x\n").isEmpty());

    Path dir = Files.createTempDirectory("apksim-crash-check");
    Files.writeString(dir.resolve("hs_err_pid7.log"), report(1, 1));
    Files.writeString(dir.resolve("hs_err_pid8.log"), report(1, 1));
    List<String> two = Crash.takeHsErr(dir, 7, -1, 8, 9);
    check("two files, each with its header", two.stream().filter(x -> x.startsWith("[hs_err] # Problematic frame")).count() == 2);
    check("files deleted", !Files.exists(dir.resolve("hs_err_pid7.log")) && !Files.exists(dir.resolve("hs_err_pid8.log")));
    Files.delete(dir);

    if (failures > 0) {
      System.out.println(failures + " crash check(s) failed");
      System.exit(1);
    }
    System.out.println("crash checks passed");
  }

  /** A report shaped as JDK 24 writes it, with `natives` native and `javas` Java frames. */
  private static String report(int natives, int javas) {
    StringBuilder b = new StringBuilder();
    b.append("#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n");
    b.append("#  SIGSEGV (0xb) at pc=0x00000001887b341c, pid=7, tid=9\n#\n# Problematic frame:\n");
    b.append("# C  [libsystem_platform.dylib+0x341c]  _platform_memmove+0xbc\n#\n\n");
    b.append("---------------  S U M M A R Y ------------\n\nCommand Line: -Dsecret=1 Main\n\n");
    b.append("---------------  T H R E A D  ---------------\n\n");
    b.append("Current thread (0x1):  JavaThread \"main\"\n\n");
    b.append("Stack: [0x1,0x2],  sp=0x3\n");
    b.append("Native frames: (J=compiled Java code)\n");
    for (int i = 0; i < natives; i++) b.append("C  [libn.dylib+0x").append(Integer.toHexString(i)).append("]  n").append(i).append("+0x1\n");
    b.append("\nJava frames: (J=compiled Java code)\n");
    for (int i = 0; i < javas; i++) b.append("j  A.m").append(i).append("()V+1\n");
    b.append("\nsiginfo: si_signo: 11 (SIGSEGV), si_addr: 0x0\n\n");
    b.append("Registers:\n x0=0x0000000000000001 secret\n\nTop of Stack: (sp=0x3)\n0x3: secret\n\nInstructions: (pc=0x1)\n0x1: secret\n\n");
    b.append("---------------  P R O C E S S  ---------------\n\nEnvironment Variables:\nHOME=/Users/secret\n\n");
    return b.toString();
  }

  private static boolean has(List<String> lines, String line) {
    return lines.contains(line);
  }

  private static void check(String what, boolean ok) {
    if (ok) return;
    failures++;
    System.out.println("FAILED: " + what);
  }
}
