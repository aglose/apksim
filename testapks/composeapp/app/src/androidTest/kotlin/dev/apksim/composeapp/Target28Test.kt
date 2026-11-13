package dev.apksim.composeapp

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an app targeting an older SDK sees, for the three-way comparison of docs/conformance.md: the API 30 emulator
 * (ApksimCmp30), the API 36 one and apksim. Build the pair with `-Papksim.targetSdk=28`; its manifest overlay
 * `-e expectTarget 28` says which target the run expects, which is what gates these tests, independent of the
 * targetSdk under test (an app-level <meta-data> in the overlay did that too, but made eight WorkManager tests fail on
 * apksim only: docs/conformance.md). Without it every test here is skipped by an assumption.
 *
 * [probe] prints one `t28.KEY=VALUE` line per observation as instrumentation status (`am instrument -r` shows it as
 * INSTRUMENTATION_STATUS) and asserts nothing; the probe table in docs/conformance.md is made from it, run alone on a
 * fresh install of each side. The other tests assert what the emulators agree on. The notification gate
 * (`Notifications.allowed()`) lives in a running instance, not in a test run: `scripts/target-gate-check.sh`.
 */
@RunWith(AndroidJUnit4::class)
class Target28Test {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val target get() = context.applicationInfo.targetSdkVersion
  private val expected: Int? get() = InstrumentationRegistry.getArguments().getString("expectTarget")?.toInt()

  private fun at(sdk: Int) = assumeTrue("needs -Papksim.targetSdk=$sdk", expected == sdk)

  @After
  fun clean() {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.cancelAll()
    nm.deleteNotificationChannel("t28")
    if (Build.VERSION.SDK_INT >= 33 && Build.FINGERPRINT != "robolectric") {  // a device: apksim has no prompt (and no dumpsys window)
      try { instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS) } catch (_: Throwable) {}
      // Creating a channel as a target-28 app raises the permission prompt on API 33+, which would hold the focus
      // against every later test of the run.
      // It appears a moment after the channel exists: look for it for a few seconds, and close it.
      val end = SystemClock.uptimeMillis() + 5_000
      while (SystemClock.uptimeMillis() < end) {
        val focus = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys window")).use { it.readBytes().decodeToString() }
        if (focus.lines().any { it.contains("mCurrentFocus") && it.contains("GrantPermissionsActivity") }) {
          instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
          Thread.sleep(500)
          break
        }
        Thread.sleep(250)
      }
    }
  }

  private fun grant(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  private fun <T> seen(f: () -> T): String = try { f().toString() } catch (e: Throwable) { "threw ${e.javaClass.simpleName}" }

  @Test
  fun probe() {
    assumeTrue("observes an older target only (posts a notification, scans)", expected != null)
    val out = Bundle()
    fun put(k: String, v: Any?) = out.putString("t28.$k", v.toString())
    put("targetSdk", target)
    put("sdkInt", Build.VERSION.SDK_INT)
    for (p in listOf(
      Manifest.permission.CAMERA, Manifest.permission.VIBRATE, Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
      Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.READ_EXTERNAL_STORAGE,
    )) put("perm.${p.substringAfterLast('.')}", grant(p))
    val nm = context.getSystemService(NotificationManager::class.java)
    put("notifEnabled", nm.areNotificationsEnabled())
    put("notifPost", seen {
      nm.createNotificationChannel(NotificationChannel("t28", "t28", NotificationManager.IMPORTANCE_DEFAULT))
      nm.notify(1, Notification.Builder(context, "t28").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("t28").build())
      // A device lists it asynchronously: wait a few seconds for it.
      val end = SystemClock.uptimeMillis() + 4_000
      while (nm.activeNotifications.isEmpty() && SystemClock.uptimeMillis() < end) Thread.sleep(100)
      "active=${nm.activeNotifications.size}"
    })
    put("cleartext", seen { NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted })
    put("externalStorageLegacy", seen { Environment.isExternalStorageLegacy() })
    put("exactAlarm", seen { if (Build.VERSION.SDK_INT >= 31) context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else "n/a" })
    put("pendingIntentNoMutability", seen {
      PendingIntent.getBroadcast(context, 0, Intent("t28"), 0)
      "ok"
    })
    put("packageVisibility", seen { context.packageManager.getInstalledPackages(0).size > 10 })
    put("settingsVisible", seen { context.packageManager.getPackageInfo("com.android.settings", 0).packageName })
    put("btAdapter", seen { context.getSystemService(BluetoothManager::class.java).adapter != null })
    put("btName", seen { context.getSystemService(BluetoothManager::class.java).adapter.name != null })
    put("btScan", seen {
      val s = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
      val cb = object : android.bluetooth.le.ScanCallback() {}
      s.startScan(cb)
      s.stopScan(cb)
      "ok"
    })
    put("fineLocation", seen {
      context.getSystemService(android.location.LocationManager::class.java).getLastKnownLocation("gps")
      "ok"
    })
    instrumentation.sendStatus(0, out)
    for (k in out.keySet().sorted()) println("$k=${out.getString(k)}")
  }

  /** The APK's targetSdk is the one the build property asked for (compared with the manifest's own record of it). */
  @Test
  fun theApkTargetsWhatTheBuildAskedFor() {
    assumeTrue("needs -Papksim.targetSdk", expected != null)
    assertEquals(expected, target)
  }

  /**
   * Legacy Bluetooth (ble/Ble.java: BLUETOOTH_ADMIN scans below target 31): the target-28 manifest asks for the legacy
   * permissions, not the API 31 ones, so a scan is allowed on API 30 and on API 36 (the split permissions) and raises no
   * SecurityException. The refusal without the permission is BleTest's e_a/e_c, apksim only (an emulator cannot revoke it).
   */
  @Test
  fun legacyBluetoothPermissionsAllowAScan() {
    at(28)
    assertTrue(grant(Manifest.permission.BLUETOOTH_ADMIN))
    val scanner = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
    val cb = object : android.bluetooth.le.ScanCallback() {}
    scanner.startScan(cb)
    scanner.stopScan(cb)
  }
}
