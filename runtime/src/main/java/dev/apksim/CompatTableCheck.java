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
    expect(since37, 37, CompatTable.ON);
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
