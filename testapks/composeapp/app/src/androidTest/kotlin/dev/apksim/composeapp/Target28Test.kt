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
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an app targeting SDK 28 sees, for the three-way comparison of docs/conformance.md: the API 30 emulator
 * (ApksimCmp30), the API 36 one and apksim. Build the pair with `-Papksim.targetSdk=28`; with the default build
 * (targetSdk 36) every test here is skipped by an assumption, except [probe], which only reports.
 *
 * [probe] prints one `key=value` line per observation as instrumentation status (`am instrument -r` shows it as
 * INSTRUMENTATION_STATUS), asserting nothing: scripts/conformance.py's table of the three is made from it. The other
 * tests assert what both emulators agree on, so a difference on apksim fails them.
 */
@RunWith(AndroidJUnit4::class)
class Target28Test {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val target get() = context.applicationInfo.targetSdkVersion

  private fun at28() = assumeTrue("needs -Papksim.targetSdk=28", target == 28)

  @Before
  fun clean() {
    context.getSystemService(NotificationManager::class.java).cancelAll()
  }

  private fun grant(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  private fun <T> seen(f: () -> T): String = try { f().toString() } catch (e: Throwable) { "threw ${e.javaClass.simpleName}" }

  @Test
  fun probe() {
    assumeTrue("observes an older target only (posts a notification, scans)", target < 33)
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

  @Test
  fun targetsTwentyEight() {
    at28()
    assertEquals(28, target)
  }

  /**
   * A target-28 app's notifications are listed once posted. API 33+ blocks a new install's until the user accepts the
   * permission prompt that creating a channel raises (areNotificationsEnabled false, probe: notifEnabled), where API 30
   * and apksim's Notifications.allowed() (targetSdkVersion < 33) let them through; so this grants it first where it exists.
   */
  @Test
  fun notificationsPostWithTheRuntimePermissionWhereOneExists() {
    at28()
    if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    val nm = context.getSystemService(NotificationManager::class.java)
    assertTrue(nm.areNotificationsEnabled())
    nm.createNotificationChannel(NotificationChannel("t28", "t28", NotificationManager.IMPORTANCE_DEFAULT))
    nm.notify(7, Notification.Builder(context, "t28").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("hello").build())
    val posted = nm.activeNotifications.filter { it.id == 7 }
    assertEquals(1, posted.size)
    assertEquals("hello", posted[0].notification.extras.getString(Notification.EXTRA_TITLE))
  }

  /**
   * Legacy Bluetooth permissions (ble/Ble.java: BLUETOOTH_ADMIN scans and BLUETOOTH names below target 31): the target-28
   * manifest asks for them with no maxSdkVersion and not for the API 31 ones, so a scan and a name read are allowed
   * on API 30, and on API 36 through the split permissions. A scan raises no SecurityException.
   */
  @Test
  fun legacyBluetoothPermissionsAllowScansAndNames() {
    at28()
    assertTrue(grant(Manifest.permission.BLUETOOTH))
    assertTrue(grant(Manifest.permission.BLUETOOTH_ADMIN))
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    assertTrue(adapter.name != null)
    val scanner = adapter.bluetoothLeScanner
    val cb = object : android.bluetooth.le.ScanCallback() {}
    scanner.startScan(cb)
    scanner.stopScan(cb)
  }

  @Test
  fun runtimePermissionsStayDeniedUntilGranted() {
    at28()
    assumeTrue(InstrumentationRegistry.getArguments().getString("permissions") != "grant")
    assertEquals(false, grant(Manifest.permission.CAMERA))
    assertEquals(false, grant(Manifest.permission.ACCESS_FINE_LOCATION))
  }

  /** Target 28 may make a mutable-by-default PendingIntent (the flag requirement starts at 31). */
  @Test
  fun pendingIntentNeedsNoMutabilityFlag() {
    at28()
    PendingIntent.getBroadcast(context, 0, Intent("t28"), 0)
  }
}
