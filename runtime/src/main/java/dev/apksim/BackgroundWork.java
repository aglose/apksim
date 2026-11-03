package dev.apksim;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowAlarmManager;

/**
 * Work that runs outside activities, which Robolectric records and a device's system server would run: started
 * services (startService, startForegroundService), JobScheduler jobs (and so WorkManager's SystemJobService), and
 * alarms (fired by Robolectric itself once {@link #enableAlarms} is on, as the clock reaches them). Jobs run when their
 * delay has passed; network and battery constraints count as met, charging and idle ones wait for `jobs run ID`.
 */
final class BackgroundWork {
  private final Application app;
  private final Map<String, ServiceController<? extends Service>> services = new HashMap<>();
  private final Map<Integer, Long> firstSeen = new HashMap<>();  // job id -> uptime it was first seen
  private final Map<Integer, Long> lastRun = new HashMap<>();
  private final Map<Integer, ServiceController<? extends Service>> runningJobs = new HashMap<>();
  private int startId;

  BackgroundWork(Application app) {
    this.app = app;
  }

  /** Before the app runs: Robolectric then fires alarms itself when the clock reaches them. */
  static void enableAlarms() {
    ShadowAlarmManager.setAutoSchedule(true);
  }

  private final Map<String, ServiceController<? extends Service>> bound = new HashMap<>();

  /**
   * Makes bindService(intent) connect to this app service, created now and kept: Robolectric connects a binding only
   * to a binder registered for it. In-process, the binder is the service's own object, as with a local service.
   */
  void bindable(Intent intent, ComponentName c) {
    if (bound.containsKey(c.getClassName())) return;
    try {
      Class<? extends Service> cls = Class.forName(c.getClassName(), true, app.getClassLoader()).asSubclass(Service.class);
      ServiceController<? extends Service> s = Robolectric.buildService(cls, intent).create();
      android.os.IBinder binder = s.get().onBind(intent);
      shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(intent, c, binder);
      bound.put(c.getClassName(), s);
    } catch (ReflectiveOperationException | RuntimeException e) {
      System.err.println("[apksim] cannot bind " + c.getShortClassName() + ": " + e);
    }
  }

  void tick() {
    startServices();
    runJobs(-1);
  }

  private void startServices() {
    Intent si;
    while ((si = shadowOf(app).getNextStartedService()) != null) {
      ComponentName c = si.getComponent();
      if (c == null) {
        ResolveInfo r = app.getPackageManager().resolveService(si, 0);
        if (r == null || r.serviceInfo == null) {
          System.err.println("[apksim] no service for " + si);
          continue;
        }
        c = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
      }
      if (!c.getPackageName().equals(app.getPackageName())) {
        System.err.println("[apksim] service outside the app, ignored: " + c.flattenToShortString());
        continue;
      }
      try {
        ServiceController<? extends Service> s = services.get(c.getClassName());
        if (s == null) {
          Class<? extends Service> cls = Class.forName(c.getClassName(), true, app.getClassLoader()).asSubclass(Service.class);
          s = Robolectric.buildService(cls, si).create();
          services.put(c.getClassName(), s);
          System.err.println("[apksim] service started: " + c.getShortClassName());
        }
        s.withIntent(si).startCommand(0, ++startId);
      } catch (ReflectiveOperationException | RuntimeException e) {
        System.err.println("[apksim] service " + c.getShortClassName() + " failed to start: " + e);
      }
    }
    services.entrySet().removeIf(e -> {
      if (!shadowOf(e.getValue().get()).isStoppedBySelf()) return false;
      e.getValue().destroy();
      System.err.println("[apksim] service stopped: " + e.getKey());
      return true;
    });
  }

  /** Runs due jobs, or with force >= 0 that job now whatever its constraints. */
  private String runJobs(int force) {
    JobScheduler js = app.getSystemService(JobScheduler.class);
    long now = SystemClock.uptimeMillis();
    String result = force >= 0 ? "no pending job " + force : null;
    runningJobs.entrySet().removeIf(e -> {
      if (!shadowOf((JobService) e.getValue().get()).getIsJobFinished()) return false;
      finished(js, e.getKey(), e.getValue());
      return true;
    });
    for (JobInfo j : js.getAllPendingJobs()) {
      if (runningJobs.containsKey(j.getId())) continue;
      long seen = firstSeen.computeIfAbsent(j.getId(), id -> now);
      Long last = lastRun.get(j.getId());
      boolean due = j.isPeriodic() ? last == null || now - last >= j.getIntervalMillis() : last == null && now - seen >= j.getMinLatencyMillis();
      boolean waits = j.isRequireCharging() || j.isRequireDeviceIdle();
      if (j.getId() == force || (due && !waits)) result = run(js, j);
    }
    return result;
  }

  private String run(JobScheduler js, JobInfo j) {
    try {
      Class<? extends Service> cls = Class.forName(j.getService().getClassName(), true, app.getClassLoader()).asSubclass(Service.class);
      ServiceController<? extends Service> c = Robolectric.buildService(cls).create();
      JobParameters p = new JobParameters(null, null, j.getId(), j.getExtras(), j.getTransientExtras(), j.getClipData(), 0, false,
          j.isExpedited(), j.isUserInitiated(), null, null, null);
      lastRun.put(j.getId(), SystemClock.uptimeMillis());
      System.err.println("[apksim] job " + j.getId() + " runs in " + j.getService().getShortClassName());
      boolean more = ((JobService) c.get()).onStartJob(p);
      if (more) {
        runningJobs.put(j.getId(), c);
      } else {
        finished(js, j.getId(), c);
      }
      return "ran job " + j.getId();
    } catch (ReflectiveOperationException | RuntimeException e) {
      System.err.println("[apksim] job " + j.getId() + " failed: " + e);
      return "job " + j.getId() + " failed: " + e;
    }
  }

  private void finished(JobScheduler js, int id, ServiceController<? extends Service> c) {
    c.destroy();
    JobInfo j = js.getPendingJob(id);
    if (j != null && !j.isPeriodic()) {
      js.cancel(id);  // a one-off job is done once it finishes
      firstSeen.remove(id);
      lastRun.remove(id);
    }
    System.err.println("[apksim] job " + id + " finished");
  }

  /** jobs | jobs run ID | services | alarms */
  String run(String[] a) {
    switch (a[0]) {
      case "jobs" -> {
        if (a.length > 2 && a[1].equals("run")) return runJobs(Integer.parseInt(a[2]));
        List<String> out = new ArrayList<>();
        for (JobInfo j : app.getSystemService(JobScheduler.class).getAllPendingJobs()) {
          out.add(j.getId() + ": " + j.getService().getShortClassName() + (j.isPeriodic() ? " every " + j.getIntervalMillis() / 1000 + " s" : "")
              + (j.isRequireCharging() || j.isRequireDeviceIdle() ? " (waits for charging/idle)" : "")
              + (runningJobs.containsKey(j.getId()) ? " running" : ""));
        }
        return out.isEmpty() ? "none" : String.join(" ; ", out);
      }
      case "services" -> {
        return services.isEmpty() ? "none" : String.join(", ", services.keySet());
      }
      default -> {
        List<String> out = new ArrayList<>();
        android.app.AlarmManager am = app.getSystemService(android.app.AlarmManager.class);
        for (ShadowAlarmManager.ScheduledAlarm s : shadowOf(am).getScheduledAlarms()) {
          out.add("type " + s.getType() + " at " + s.getTriggerAtMs() + (s.getIntervalMs() > 0 ? " every " + s.getIntervalMs() + " ms" : ""));
        }
        return out.isEmpty() ? "none" : String.join(" ; ", out);
      }
    }
  }
}
