package dev.apksim;

/**
 * Checks CompatTable on a plain JVM against the committed table: an id enabled since 30 is off for an app targeting 28
 * and on for 30 and 36; one since 36 is off at 35; one since 37 is off at 36 and on at 37; a disabled one is off at
 * every target; an id the table lacks, and any id with no target, is UNKNOWN (the caller's old answer); a launch's
 * configure() forgets the last launch's target and counts; `compat` lists names, answers and counts; and a lookup
 * allocates nothing. Exits 1 on any failure. scripts/check-compat-table.sh runs it (CI).
 */
public final class CompatTableCheck {
  private CompatTableCheck() {}

  private static int failures;

  private static void check(boolean ok, String what) {
    if (!ok) {
      failures++;
      System.err.println("FAIL: " + what);
    }
  }

  private static String name(int a) {
    return a == CompatTable.ON ? "ON" : a == CompatTable.OFF ? "OFF" : "UNKNOWN";
  }

  private static void expect(long id, int target, int want) {
    int got = CompatTable.answer(id, target, true);
    check(got == want, "change " + id + " at target " + target + ": " + name(got) + ", wanted " + name(want));
  }

  public static void main(String[] args) throws Exception {
    final long since33 = 183155436L;
    final long since30 = 132742131L;  // RESOURCES_ARSC_COMPRESSED
    final long since36 = 364980165L;  // ENABLE_FILE_SYSTEM_ACCESS
    final long since37 = 297019750L;  // RENDERSCRIPT_SCRIPTC_DEPRECATION_CHANGE_ID
    final long disabled = 189969744L;  // DOWNSCALE_65
    final long absent = 1L;
    final long since0 = 149924527L;  // DEFAULT_SCOPED_STORAGE
    final long logging = 135920175L;  // CHANGE_RESTRICT_SAW_INTENT, loggingOnly
    final long nextRelease = 216114297L;  // enabled since 10000
    final long resultBeforeResume = CompatTable.CALL_ACTIVITY_RESULT_BEFORE_RESUME;
    final long notInTable = 330902016L;  // ENABLE_CHECKING_TELEPHONY_FEATURES_FOR_VCN: Robolectric's own map has it
    check(CompatTable.size() > 300, "table has " + CompatTable.size() + " changes");

    CompatTable.configure("p.q", 28);
    expect(since30, 28, CompatTable.OFF);
    expect(since33, 28, CompatTable.OFF);
    expect(since30, 30, CompatTable.ON);
    expect(since33, 32, CompatTable.OFF);
    expect(since33, 33, CompatTable.ON);
    expect(since36, 35, CompatTable.OFF);
    expect(since36, 36, CompatTable.ON);
    expect(since37, 36, CompatTable.OFF);
    expect(since37, 37, CompatTable.OFF);  // clamped to the platform's 36, as a device: off for every app
    expect(since37, 10000, CompatTable.OFF);
    expect(since36, 37, CompatTable.ON);
    expect(since0, 1, CompatTable.ON);
    expect(logging, 28, CompatTable.ON);
    expect(nextRelease, 36, CompatTable.OFF);
    expect(nextRelease, 10000, CompatTable.OFF);
    expect(resultBeforeResume, 36, CompatTable.OFF);  // since 32, but Robolectric's one stays off
    for (int t : new int[] {1, 28, 36, 37, 10000}) expect(disabled, t, CompatTable.OFF);
    expect(absent, 36, CompatTable.UNKNOWN);
    expect(since36, 0, CompatTable.UNKNOWN);
    expect(since36, -1, CompatTable.UNKNOWN);
    check(CompatTable.answer(since30) == CompatTable.OFF, "answer(id) follows the configured target 28");

    // A new launch's target replaces the last one, and its counts start again.
    CompatTable.configure("p.q", 36);
    check(CompatTable.targetSdk() == 36 && CompatTable.active(), "target after configure");
    check(CompatTable.answer(since30) == CompatTable.ON, "answer(id) follows the new target 36");
    String rep = CompatTable.report(false);
    check(rep.contains("RESOURCES_ARSC_COMPRESSED (132742131): on x1 [since 30]"), "report lists the asked change once: " + rep);
    check(!rep.contains("(183155436)"), "report leaves out changes not asked about this launch: " + rep);
    CompatTable.answer(absent);
    CompatTable.answer(absent);
    check(CompatTable.report(false).contains("1 (not in table): x2"), "report counts an id the table lacks");
    String all = CompatTable.report(true);
    check(all.contains("target 36 of p.q") && all.contains("table " + CompatTable.size() + " changes"), "compat all: " + all.lines().findFirst().orElse(""));
    CompatTable.answer(disabled);
    check(CompatTable.report(false).contains("DOWNSCALE_65 (189969744): off x1 [disabled]"), "report marks a disabled change");
    CompatTable.configure("p.q", 28);
    check(!CompatTable.report(false).contains("(132742131)"), "configure forgets the last launch's counts");

    // The report's tally clamps like the answers: a target 37 app has the same changes on as one targeting 36.
    CompatTable.configure("p.q", 37);
    String t37 = CompatTable.report(true).lines().findFirst().orElse("");
    CompatTable.configure("p.q", 36);
    String t36 = CompatTable.report(true).lines().findFirst().orElse("");
    check(t37.substring(t37.indexOf("; table")).equals(t36.substring(t36.indexOf("; table"))), "tally at 37 equals at 36: " + t37 + " / " + t36);
    CompatTable.configure("p.q", 28);
    String t28 = CompatTable.report(true).lines().findFirst().orElse("");
    check(!t28.substring(t28.indexOf("; table")).equals(t36.substring(t36.indexOf("; table"))), "tally at 28 differs: " + t28);
    CompatTable.configure("p.q", 36);
    CompatTable.answer(resultBeforeResume);
    check(CompatTable.report(false).contains("CALL_ACTIVITY_RESULT_BEFORE_RESUME (78294732): off x1") &&
        CompatTable.report(false).contains("[forced off by Robolectric]"), "forced-off change counts as off");

    // The shadows' decisions, with a stand-in for Robolectric's own answer.
    int[] fell = new int[1];
    java.util.function.LongPredicate old = id -> { fell[0]++; return true; };
    check(CompatShadows.decide(since36, old) && fell[0] == 0, "decide: a table id never asks Robolectric");
    check(!CompatShadows.decide(disabled, old) && !CompatShadows.decide(since37, old), "decide: disabled and 37 off");
    check(!CompatShadows.decide(resultBeforeResume, old) && fell[0] == 0, "decide: 78294732 stays off");
    check(CompatShadows.decide(notInTable, old) && fell[0] == 1, "decide: an id the table lacks goes to Robolectric's answer");
    CompatTable.configure("p.q", 28);
    check(!CompatShadows.decide(since30, old), "decide: target 28 turns a since-30 change off");
    check(CompatShadows.decideFor(since0, true, -1) && !CompatShadows.decideFor(since30, true, -1), "decideFor: the running app at 28");
    check(CompatShadows.decideFor(since30, false, 30) && !CompatShadows.decideFor(since30, false, 29), "decideFor: another package by its own target");
    check(CompatShadows.decideFor(since37, false, 36 + 1) == false, "decideFor: another package at 37 is clamped too");
    check(CompatShadows.decideFor(since36, false, -1) && !CompatShadows.decideFor(disabled, false, -1), "decideFor: not installed takes the default");
    check(CompatShadows.decideFor(notInTable, true, -1) && CompatShadows.decideFor(notInTable, false, -1), "decideFor: an id the table lacks is on");
    CompatTable.configure("", -1);
    check(!CompatShadows.decideFor(since0, true, -1) && !CompatShadows.decideFor(since0, false, 30), "decideFor: no target, the old false");
    check(CompatShadows.decide(since36, old), "decide: no target, Robolectric's answer");
    CompatTable.configure("p.q", 36);

    // A malformed table line is an error, not a half-built table.
    try {
      CompatTable.parse(new java.io.StringReader("1\tX\t0\n"));
      check(false, "a short line must not parse");
    } catch (RuntimeException expected) {
    }

    // No target known (a standby before its launch): the table is not consulted.
    CompatTable.configure("", -1);
    check(!CompatTable.active() && CompatTable.answer(since36) == CompatTable.UNKNOWN, "no target: UNKNOWN");
    CompatTable.configure("p.q", 36);

    // The lookup allocates nothing, hit, miss or disabled.
    com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    long me = Thread.currentThread().threadId();
    long[] ids = {since30, since36, since37, disabled, absent};
    int sink = 0;
    for (int i = 0; i < 200_000; i++) sink += CompatTable.answer(ids[i % 5]);  // warm up, the unknown id's slot too
    long before = mx.getThreadAllocatedBytes(me);
    for (int i = 0; i < 1_000_000; i++) sink += CompatTable.answer(ids[i % 5]);
    long used = mx.getThreadAllocatedBytes(me) - before;
    check(used < 4096, "1,000,000 lookups allocated " + used + " bytes");
    if (sink == 42) System.out.println();

    if (failures > 0) {
      System.err.println(failures + " failed");
      System.exit(1);
    }
    System.out.println("compat table: all checks passed (" + CompatTable.size() + " changes)");
  }
}
