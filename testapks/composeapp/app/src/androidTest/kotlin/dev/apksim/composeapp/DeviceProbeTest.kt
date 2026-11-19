package dev.apksim.composeapp

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.audiofx.Visualizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.LocaleList
import android.os.PowerManager
import android.os.StatFs
import android.os.Vibrator
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Display
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device-difference probe (docs/conformance.md "Device-difference probe"): about two hundred simple framework getters
 * an app commonly reads, each normalised to a class of value (`>0`, `non-null`, `1..100`, a boolean) so that two devices
 * agree and the table is about behaviour, not about one emulator. A getter that throws records `EXCEPTION:ClassName`.
 * Prints one `probe.KEY=VALUE` line per observation as instrumentation status and on stdout, as `Target28Test#probe` does,
 * and asserts nothing. `scripts/gen-device-probe.py` makes `device-probe-api36.tsv` from an emulator's run,
 * `scripts/device-probe-check.py` compares apksim's run with it.
 */
@RunWith(AndroidJUnit4::class)
class DeviceProbeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext

  private fun num(n: Number?): String = when {
    n == null -> "null"
    n.toDouble() > 0 -> ">0"
    n.toDouble() < 0 -> "<0"
    else -> "0"
  }
  private fun nn(x: Any?) = if (x == null) "null" else "non-null"
  private fun ne(s: CharSequence?) = if (s == null) "null" else if (s.isEmpty()) "empty" else "non-empty"
  private fun range(v: Int, lo: Int, hi: Int) = if (v in lo..hi) "$lo..$hi" else if (v < lo) "<$lo" else ">$hi"

  @Test
  fun probe() {
    val out = Bundle()
    fun p(k: String, f: () -> Any?) {
      val v = try { f().toString() } catch (e: Throwable) { "EXCEPTION:" + e.javaClass.simpleName }
      out.putString("probe.$k", v)
    }
    val pm = context.packageManager
    val pkg = context.packageName

    // WifiManager
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
    p("wifi.manager") { nn(wifi) }
    p("wifi.state") { wifi!!.wifiState }
    p("wifi.enabled") { wifi!!.isWifiEnabled }
    p("wifi.maxSignalLevel") { num(wifi!!.maxSignalLevel) }
    p("wifi.maxSignalLevel.value") { wifi!!.maxSignalLevel }
    for (r in listOf(-127, -90, -75, -60, -40)) p("wifi.calcLevel.$r") { wifi!!.calculateSignalLevel(r) }
    p("wifi.connectionInfo") { nn(wifi!!.connectionInfo) }
    p("wifi.connectionInfo.rssi") { range(wifi!!.connectionInfo.rssi, -126, -1) }
    p("wifi.connectionInfo.linkSpeed") { num(wifi!!.connectionInfo.linkSpeed) }
    p("wifi.connectionInfo.frequency") { num(wifi!!.connectionInfo.frequency) }
    p("wifi.connectionInfo.ssid") { ne(wifi!!.connectionInfo.ssid) }
    p("wifi.connectionInfo.supplicantState") { wifi!!.connectionInfo.supplicantState }
    p("wifi.is5GHzSupported") { wifi!!.is5GHzBandSupported }
    p("wifi.dhcpInfo") { nn(wifi!!.dhcpInfo) }

    // ConnectivityManager
    val cm = context.getSystemService(ConnectivityManager::class.java)
    p("conn.activeNetwork") { nn(cm.activeNetwork) }
    p("conn.activeNetworkInfo") { nn(cm.activeNetworkInfo) }
    p("conn.activeNetworkInfo.connected") { cm.activeNetworkInfo!!.isConnected }
    p("conn.metered") { cm.isActiveNetworkMetered }
    p("conn.allNetworks") { num(cm.allNetworks.size) }
    p("conn.linkProperties") { nn(cm.getLinkProperties(cm.activeNetwork)) }
    p("conn.linkProperties.dns") { num(cm.getLinkProperties(cm.activeNetwork)!!.dnsServers.size) }
    p("conn.restrictBackground") { cm.restrictBackgroundStatus }
    p("conn.defaultNetworkActive") { cm.isDefaultNetworkActive }
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    p("conn.caps") { nn(caps) }
    for ((n, c) in listOf(
      "INTERNET" to NetworkCapabilities.NET_CAPABILITY_INTERNET, "VALIDATED" to NetworkCapabilities.NET_CAPABILITY_VALIDATED,
      "NOT_METERED" to NetworkCapabilities.NET_CAPABILITY_NOT_METERED, "NOT_RESTRICTED" to NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
      "TRUSTED" to NetworkCapabilities.NET_CAPABILITY_TRUSTED, "NOT_VPN" to NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
      "NOT_ROAMING" to NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, "NOT_SUSPENDED" to NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
    )) p("conn.caps.$n") { caps!!.hasCapability(c) }
    for ((n, t) in listOf(
      "WIFI" to NetworkCapabilities.TRANSPORT_WIFI, "CELLULAR" to NetworkCapabilities.TRANSPORT_CELLULAR,
      "ETHERNET" to NetworkCapabilities.TRANSPORT_ETHERNET, "VPN" to NetworkCapabilities.TRANSPORT_VPN,
    )) p("conn.caps.transport.$n") { caps!!.hasTransport(t) }
    p("conn.caps.downstreamKbps") { num(caps!!.linkDownstreamBandwidthKbps) }

    // PackageManager / PackageInfo of the app itself
    val info = pm.getPackageInfo(pkg, 0)
    p("pkg.firstInstallTime") { num(info.firstInstallTime) }
    p("pkg.lastUpdateTime") { num(info.lastUpdateTime) }
    p("pkg.versionCode") { num(info.longVersionCode) }
    p("pkg.versionName") { ne(info.versionName) }
    p("pkg.installer") { nn(pm.getInstallSourceInfo(pkg).installingPackageName) }
    p("pkg.appInfo.flags.debuggable") { (info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    p("pkg.appInfo.flags.system") { (info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
    p("pkg.appInfo.dataDir") { ne(info.applicationInfo!!.dataDir) }
    p("pkg.appInfo.nativeLibraryDir") { ne(info.applicationInfo!!.nativeLibraryDir) }
    p("pkg.appInfo.sourceDir") { ne(info.applicationInfo!!.sourceDir) }
    p("pkg.appInfo.uid") { num(info.applicationInfo!!.uid) }
    p("pkg.signatures") { num(pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.size) }
    p("pkg.requestedPermissions") { num(pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.size) }
    p("pkg.installedPackages") { num(pm.getInstalledPackages(0).size) }
    p("pkg.launchIntentForSelf") { nn(pm.getLaunchIntentForPackage(pkg)) }
    p("pkg.missingPackage") { try { pm.getPackageInfo("dev.apksim.no.such", 0); "found" } catch (e: PackageManager.NameNotFoundException) { "NameNotFound" } }
    for (f in listOf(
      "android.hardware.touchscreen", "android.hardware.wifi", "android.hardware.bluetooth", "android.hardware.bluetooth_le",
      "android.hardware.camera", "android.hardware.camera.any", "android.hardware.location", "android.hardware.location.gps",
      "android.hardware.telephony", "android.hardware.fingerprint", "android.hardware.sensor.accelerometer",
      "android.hardware.sensor.gyroscope", "android.hardware.microphone", "android.hardware.nfc",
      "android.software.leanback", "android.software.picture_in_picture", "android.software.webview",
      "android.software.device_admin", "android.software.print", "android.hardware.screen.portrait",
    )) p("feature.${f.removePrefix("android.")}") { pm.hasSystemFeature(f) }

    // AudioManager
    val am = context.getSystemService(AudioManager::class.java)
    for ((n, s) in listOf(
      "music" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING, "alarm" to AudioManager.STREAM_ALARM,
      "notification" to AudioManager.STREAM_NOTIFICATION, "voice" to AudioManager.STREAM_VOICE_CALL,
      "system" to AudioManager.STREAM_SYSTEM,
    )) {
      p("audio.maxVolume.$n") { num(am.getStreamMaxVolume(s)) }
      p("audio.minVolume.$n") { am.getStreamMinVolume(s) }
    }
    p("audio.volume.music.inRange") { am.getStreamVolume(AudioManager.STREAM_MUSIC) in am.getStreamMinVolume(AudioManager.STREAM_MUSIC)..am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    p("audio.ringerMode") { am.ringerMode }
    p("audio.mode") { am.mode }
    p("audio.musicActive") { am.isMusicActive }
    p("audio.speakerphone") { am.isSpeakerphoneOn }
    p("audio.microphoneMute") { am.isMicrophoneMute }
    p("audio.bluetoothScoOn") { am.isBluetoothScoOn }
    p("audio.property.outputSampleRate") { num(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()) }
    p("audio.property.outputFramesPerBuffer") { num(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()) }
    p("audio.property.supportMicNearUltrasound") { nn(am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)) }
    p("audio.outputDevices") { num(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).size) }
    p("audio.inputDevices") { num(am.getDevices(AudioManager.GET_DEVICES_INPUTS).size) }
    p("audio.volumeFixed") { am.isVolumeFixed }

    // PowerManager
    val pw = context.getSystemService(PowerManager::class.java)
    p("power.interactive") { pw.isInteractive }
    p("power.powerSave") { pw.isPowerSaveMode }
    p("power.thermalStatus") { pw.currentThermalStatus }
    p("power.ignoringBatteryOptimizations") { pw.isIgnoringBatteryOptimizations(pkg) }
    p("power.deviceIdle") { pw.isDeviceIdleMode }
    p("power.sustainedPerformance") { pw.isSustainedPerformanceModeSupported }
    p("power.locationMode") { pw.locationPowerSaveMode }
    p("power.wakeLock") { nn(pw.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "probe")) }

    // BatteryManager and the sticky battery broadcast
    val bm = context.getSystemService(BatteryManager::class.java)
    p("battery.capacity.range") { range(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), 1, 100) }
    p("battery.charging") { bm.isCharging }
    p("battery.chargeCounter") { num(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)) }
    val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    p("sticky.battery") { nn(bat) }
    p("sticky.battery.level.range") { range(bat!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), 0, 100) }
    p("sticky.battery.scale") { num(bat!!.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) }
    p("sticky.battery.present") { bat!!.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) }
    p("sticky.battery.voltage") { num(bat!!.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)) }
    p("sticky.battery.temperature") { num(bat!!.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)) }
    p("sticky.battery.health") { bat!!.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) }
    p("sticky.battery.technology") { nn(bat!!.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)) }
    p("sticky.wifiState") { nn(context.registerReceiver(null, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))) }
    p("sticky.wifiNetworkState") { nn(context.registerReceiver(null, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))) }
    p("sticky.connectivity") { nn(context.registerReceiver(null, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))) }
    p("sticky.dockEvent") { nn(context.registerReceiver(null, IntentFilter(Intent.ACTION_DOCK_EVENT))) }

    // Display
    val dm = context.getSystemService(DisplayManager::class.java)
    val disp = dm.getDisplay(Display.DEFAULT_DISPLAY)
    p("display.count") { num(dm.displays.size) }
    p("display.state") { disp.state }
    p("display.refreshRate.rounded") { Math.round(disp.refreshRate) >= 30 }
    p("display.supportedModes") { num(disp.supportedModes.size) }
    p("display.hdrCaps") { nn(disp.hdrCapabilities) }
    p("display.hdrTypes") { disp.hdrCapabilities!!.supportedHdrTypes.size >= 0 }
    p("display.wideColorGamut") { disp.isWideColorGamut }
    p("display.rotation") { disp.rotation }
    p("display.flags.secure") { (disp.flags and Display.FLAG_SECURE) != 0 }
    p("display.isValid") { disp.isValid }
    p("display.density") { num(context.resources.displayMetrics.density) }
    p("display.densityDpi") { num(context.resources.displayMetrics.densityDpi) }
    p("display.cutout") { nn(disp.cutout) }
    p("display.mode.physicalWidth") { num(disp.mode.physicalWidth) }
    p("display.name") { ne(disp.name) }

    // TelephonyManager
    val tm = context.getSystemService(TelephonyManager::class.java)
    p("tel.manager") { nn(tm) }
    p("tel.phoneType") { tm.phoneType }
    p("tel.simState") { tm.simState }
    p("tel.networkType") { tm.dataNetworkType == TelephonyManager.NETWORK_TYPE_UNKNOWN }
    p("tel.dataState") { tm.dataState }
    p("tel.callState") { tm.callState }
    p("tel.networkOperatorName") { nn(tm.networkOperatorName) }
    p("tel.simOperator") { nn(tm.simOperator) }
    p("tel.networkCountryIso") { nn(tm.networkCountryIso) }
    p("tel.isNetworkRoaming") { tm.isNetworkRoaming }
    p("tel.hasIccCard") { tm.hasIccCard() }
    p("tel.isVoiceCapable") { tm.isVoiceCapable }
    p("tel.isSmsCapable") { tm.isSmsCapable }

    // Visualizer statics
    p("visualizer.captureSizeRange") { nn(Visualizer.getCaptureSizeRange()) }
    p("visualizer.captureSizeRange.valid") { Visualizer.getCaptureSizeRange().let { it[0] > 0 && it[1] >= it[0] } }
    p("visualizer.maxCaptureRate") { num(Visualizer.getMaxCaptureRate()) }

    // MediaCodecList
    val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    fun has(enc: Boolean, mime: String) = codecs.any { it.isEncoder == enc && it.supportedTypes.any { t -> t.equals(mime, true) } }
    p("codec.count") { num(codecs.size) }
    p("codec.h264.decoder") { has(false, "video/avc") }
    p("codec.h264.encoder") { has(true, "video/avc") }
    p("codec.hevc.decoder") { has(false, "video/hevc") }
    p("codec.aac.decoder") { has(false, "audio/mp4a-latm") }
    p("codec.aac.encoder") { has(true, "audio/mp4a-latm") }
    p("codec.vp9.decoder") { has(false, "video/x-vnd.on2.vp9") }
    p("codec.opus.decoder") { has(false, "audio/opus") }

    // Vibrator, InputMethodManager, KeyguardManager, ActivityManager, UiModeManager
    val vib = context.getSystemService(Vibrator::class.java)
    p("vibrator.has") { vib.hasVibrator() }
    p("vibrator.amplitudeControl") { vib.hasAmplitudeControl() }
    val imm = context.getSystemService(InputMethodManager::class.java)
    p("ime.enabledCount") { num(imm.enabledInputMethodList.size) }
    p("ime.listCount") { num(imm.inputMethodList.size) }
    p("ime.isActive") { imm.isActive }
    p("ime.acceptingText") { imm.isAcceptingText }
    val kg = context.getSystemService(KeyguardManager::class.java)
    p("keyguard.deviceLocked") { kg.isDeviceLocked }
    p("keyguard.deviceSecure") { kg.isDeviceSecure }
    p("keyguard.keyguardSecure") { kg.isKeyguardSecure }
    p("keyguard.keyguardLocked") { kg.isKeyguardLocked }
    val act = context.getSystemService(ActivityManager::class.java)
    p("activity.lowRam") { act.isLowRamDevice }
    p("activity.memoryClass") { num(act.memoryClass) }
    p("activity.largeMemoryClass") { num(act.largeMemoryClass) }
    p("activity.userAMonkey") { ActivityManager.isUserAMonkey() }
    p("activity.runningProcesses") { num(act.runningAppProcesses?.size) }
    p("activity.memoryInfo.totalMem") { num(ActivityManager.MemoryInfo().also { act.getMemoryInfo(it) }.totalMem) }
    p("activity.memoryInfo.availMem") { num(ActivityManager.MemoryInfo().also { act.getMemoryInfo(it) }.availMem) }
    p("activity.backgroundRestricted") { act.isBackgroundRestricted }
    p("activity.launcherLargeIconSize") { num(act.launcherLargeIconSize) }
    p("activity.deviceConfigurationInfo") { num(act.deviceConfigurationInfo.reqGlEsVersion) }
    val ui = context.getSystemService(UiModeManager::class.java)
    p("uimode.currentModeType") { ui.currentModeType }
    p("uimode.nightMode") { ui.nightMode }
    p("uimode.configNightMask") { context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK }

    // Locale, accessibility, notifications, clipboard
    p("locale.default.nonEmpty") { !LocaleList.getDefault().isEmpty }
    p("locale.default.language") { ne(LocaleList.getDefault()[0].language) }
    p("locale.config") { !context.resources.configuration.locales.isEmpty }
    val a11y = context.getSystemService(AccessibilityManager::class.java)
    p("a11y.enabled") { a11y.isEnabled }
    p("a11y.touchExploration") { a11y.isTouchExplorationEnabled }
    p("a11y.enabledServices") { num(a11y.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).size) }
    val nm = context.getSystemService(NotificationManager::class.java)
    p("notif.enabled") { nm.areNotificationsEnabled() }
    p("notif.importance") { nm.importance }
    p("notif.interruptionFilter") { nm.currentInterruptionFilter }
    p("notif.channels") { num(nm.notificationChannels.size) }
    p("notif.bubbles") { nm.areBubblesAllowed() }
    p("notif.policyAccess") { nm.isNotificationPolicyAccessGranted }
    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
    p("clipboard.hasPrimaryClip") { clip.hasPrimaryClip() }

    // Storage
    val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
    p("storage.manager") { nn(sm) }
    p("storage.internalFree") { num(StatFs(context.filesDir.path).availableBytes) }
    p("storage.internalTotal") { num(StatFs(context.filesDir.path).totalBytes) }
    p("storage.cacheQuota") { num(sm.getCacheQuotaBytes(sm.getUuidForPath(context.cacheDir))) }
    p("storage.externalState") { Environment.getExternalStorageState() }
    p("storage.externalEmulated") { Environment.isExternalStorageEmulated() }
    p("storage.externalRemovable") { Environment.isExternalStorageRemovable() }
    p("storage.externalManager") { Environment.isExternalStorageManager() }
    p("storage.externalFilesDir") { nn(context.getExternalFilesDir(null)) }
    p("storage.externalFree") { num(StatFs(Environment.getExternalStorageDirectory().path).availableBytes) }
    p("storage.volumes") { num(sm.storageVolumes.size) }
    p("storage.primaryVolumeState") { sm.primaryStorageVolume.state }

    // Build: shape only
    fun shape(s: String?) = when {
      s == null -> "null"
      s.isEmpty() -> "empty"
      s.equals("unknown", true) -> "unknown"
      s.equals("robolectric", true) -> "robolectric"
      else -> "ok"
    }
    p("build.MANUFACTURER") { shape(Build.MANUFACTURER) }
    p("build.MODEL") { shape(Build.MODEL) }
    p("build.BRAND") { shape(Build.BRAND) }
    p("build.DEVICE") { shape(Build.DEVICE) }
    p("build.PRODUCT") { shape(Build.PRODUCT) }
    p("build.BOARD") { shape(Build.BOARD) }
    p("build.HARDWARE") { shape(Build.HARDWARE) }
    p("build.FINGERPRINT") { shape(Build.FINGERPRINT) }
    p("build.ID") { shape(Build.ID) }
    p("build.DISPLAY") { shape(Build.DISPLAY) }
    p("build.HOST") { shape(Build.HOST) }
    p("build.USER") { shape(Build.USER) }
    p("build.TAGS") { shape(Build.TAGS) }
    p("build.TYPE") { shape(Build.TYPE) }
    p("build.BOOTLOADER") { shape(Build.BOOTLOADER) }
    p("build.RADIO") { shape(Build.getRadioVersion()) }
    p("build.SOC_MODEL") { shape(Build.SOC_MODEL) }
    p("build.SKU") { shape(Build.SKU) }
    p("build.SUPPORTED_ABIS") { num(Build.SUPPORTED_ABIS.size) }
    p("build.SUPPORTED_64_BIT_ABIS") { num(Build.SUPPORTED_64_BIT_ABIS.size) }
    p("build.TIME") { num(Build.TIME) }
    p("build.version.SDK_INT") { Build.VERSION.SDK_INT }
    p("build.version.RELEASE") { shape(Build.VERSION.RELEASE) }
    p("build.version.CODENAME") { Build.VERSION.CODENAME }
    p("build.version.INCREMENTAL") { shape(Build.VERSION.INCREMENTAL) }
    p("build.version.SECURITY_PATCH") { shape(Build.VERSION.SECURITY_PATCH) }
    p("build.version.BASE_OS") { nn(Build.VERSION.BASE_OS) }
    p("build.version.PREVIEW_SDK_INT") { Build.VERSION.PREVIEW_SDK_INT }
    p("build.serial") { shape(Build.getSerial()) }

    // Settings: presence of common keys
    val cr = context.contentResolver
    for (k in listOf(
      "airplane_mode_on", "adb_enabled", "development_settings_enabled", "device_name", "wifi_on", "bluetooth_on",
      "auto_time", "auto_time_zone", "stay_on_while_plugged_in", "animator_duration_scale", "window_animation_scale",
      "transition_animation_scale", "always_finish_activities", "mobile_data", "data_roaming", "boot_count",
      "device_provisioned", "install_non_market_apps", "usb_mass_storage_enabled", "zen_mode", "low_power",
    )) p("settings.global.$k") { nn(Settings.Global.getString(cr, k)) }
    for (k in listOf(
      "android_id", "location_mode", "default_input_method", "enabled_accessibility_services", "accessibility_enabled",
      "bluetooth_name", "bluetooth_address", "install_non_market_apps", "location_providers_allowed", "long_press_timeout",
      "show_ime_with_hard_keyboard", "user_setup_complete", "tts_default_synth", "ui_night_mode", "immersive_mode_confirmations",
    )) p("settings.secure.$k") { nn(Settings.Secure.getString(cr, k)) }
    for (k in listOf(
      "screen_brightness", "screen_off_timeout", "font_scale", "time_12_24", "accelerometer_rotation", "user_rotation",
      "haptic_feedback_enabled", "sound_effects_enabled", "volume_music", "ringtone", "notification_sound",
      "alarm_alert", "screen_brightness_mode", "vibrate_when_ringing", "date_format", "show_touches",
    )) p("settings.system.$k") { nn(Settings.System.getString(cr, k)) }
    p("settings.canDrawOverlays") { Settings.canDrawOverlays(context) }
    p("settings.canWrite") { Settings.System.canWrite(context) }

    instrumentation.sendStatus(0, out)
    // A test run on apksim has no watcher for the status bundle: the lines are printed as well (Target28Test#probe does too).
    for (k in out.keySet().sorted()) println("$k=${out.getString(k)}")
  }
}
