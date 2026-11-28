package dev.apksim.composeapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// The Settings tab, in its own file so its code loads only when the tab opens (launch benchmarks stay as they were).
@Composable
fun SettingsScreen() {
  Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Section("Preferences") {
      var on by remember { mutableStateOf(true) }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Notifications", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(on, { on = it })
      }
    }
    DeviceFeatures()
    Section("This device") {
      Text("android.os.Build.VERSION.SDK_INT = ${android.os.Build.VERSION.SDK_INT}")
      // The wall clock, in the locale's format: fixed in screenshot tests (apksim `clock ISO`, docs/snapshots.md).
      Text("Now: " + java.time.LocalDateTime.now().format(
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)))
    }
    // Two-finger gestures (apksim: Option-drag, trackpad pinch, `pinch` command).
    var zoom by remember { mutableFloatStateOf(1f) }
    var angle by remember { mutableFloatStateOf(0f) }
    val gestures = rememberTransformableState { z, _, r -> zoom *= z; angle += r }
    Box(
      Modifier.fillMaxWidth().height(200.dp).background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.extraLargeIncreased).transformable(gestures),
      contentAlignment = Alignment.Center,
    ) {
      Text("Pinch: zoom %.2fx, rotation %.0f°".format(zoom, angle), color = MaterialTheme.colorScheme.onTertiaryContainer,
          style = MaterialTheme.typography.titleMediumEmphasized)
    }
    BackgroundAndKeys()
    Section("Media") { ClipPlayer() }
  }
}

/** A device feature's row: the button that uses it, and what came of it. */
@Composable
private fun FeatureRow(label: String, status: String?, onClick: () -> Unit, more: @Composable RowScope.() -> Unit = {}) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    FilledTonalButton(onClick) { Text(label) }
    more()
    if (status != null) Text(status, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// Things apksim's simulator features act on: a permission prompt, a notification, the clipboard, location.
@Composable
fun DeviceFeatures() {
  val context = LocalContext.current
  var camera by remember { mutableStateOf("not asked") }
  var location by remember { mutableStateOf("no fix") }
  // Once granted, on a device with a camera (apksim: --camera fake), the CameraX preview screen.
  val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    camera = if (it) "granted" else "denied"
    if (it && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) {
      context.startActivity(android.content.Intent(context, CameraActivity::class.java))
    }
  }
  val lm = context.getSystemService(LocationManager::class.java)
  val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    if (ok) {
      lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f) { l -> location = "%.4f, %.4f".format(l.latitude, l.longitude) }
    } else {
      location = "denied"
    }
  }
  var unlock by remember { mutableStateOf("locked") }
  var picked by remember { mutableStateOf("none") }
  val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    picked = if (uri == null) "cancelled" else context.contentResolver.query(uri, null, null, null, null)?.use { c ->
      c.moveToFirst()
      val name = c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
      val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size }
      "$name, $bytes bytes"
    } ?: "unreadable"
  }
  var trip by remember { mutableStateOf("none") }
  val chooseTrip = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    trip = if (r.resultCode == android.app.Activity.RESULT_OK) r.data?.getStringExtra("trip") ?: "?" else "cancelled"
  }
  Section("Device features") {
    FeatureRow("Camera", "camera: $camera", { askCamera.launch(Manifest.permission.CAMERA) })
    FeatureRow("Locate", "location: $location", { askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) })
    FeatureRow("Notify", null, {
      val nm = context.getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(NotificationChannel("trips", "Trips", NotificationManager.IMPORTANCE_DEFAULT))
      nm.notify(1, Notification.Builder(context, "trips").setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Trip reminder").setContentText("Italian vacation starts tomorrow").setAutoCancel(true)
        .setContentIntent(android.app.PendingIntent.getActivity(context, 0,
          android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("apksim://composeapp/trips")).setPackage(context.packageName),
          android.app.PendingIntent.FLAG_IMMUTABLE)).build())
    }) {
      FilledTonalButton({
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("trip", "Copied from ComposeApp"))
      }, Modifier.padding(start = 8.dp)) { Text("Copy") }
    }
    FeatureRow("Unlock", "biometrics: $unlock", {
      val prompt = android.hardware.biometrics.BiometricPrompt.Builder(context).setTitle("Unlock trips")
        .setNegativeButton("Cancel", context.mainExecutor) { _, _ -> unlock = "cancelled" }.build()
      prompt.authenticate(android.os.CancellationSignal(), context.mainExecutor,
        object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { unlock = "unlocked" }
          override fun onAuthenticationFailed() { unlock = "not recognized" }
          override fun onAuthenticationError(code: Int, message: CharSequence) { unlock = "error: $message" }
        })
    })
    FeatureRow("Pick file", "file: $picked", { pickFile.launch("*/*") })
    FeatureRow("Choose trip", "trip: $trip", { chooseTrip.launch(android.content.Intent(context, TripChooserActivity::class.java)) })
    FeatureRow("Share", null, {
      val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, "Italian vacation, June 3-17")
      context.startActivity(android.content.Intent.createChooser(send, "Share trip"))
    })
  }
}

/** Background work, the keystore and a service binding: each button records what happened beside it. */
@Composable
fun BackgroundAndKeys() {
  val context = LocalContext.current
  // Background work: a job, an exact alarm to a manifest receiver, a started service. Each records when it ran.
  val prefs = context.getSharedPreferences("background", android.content.Context.MODE_PRIVATE)
  var ran by remember { mutableStateOf("") }
  LaunchedEffect(Unit) {
    while (true) {
      ran = listOf("job", "alarm", "service").joinToString(", ") { "$it " + (if (prefs.getBoolean(it, false)) "ran" else "-") }
      kotlinx.coroutines.delay(300)
    }
  }
  var keystore by remember { mutableStateOf("-") }
  var binding by remember { mutableStateOf("-") }
  Section("Background and keys") {
    FeatureRow("Background", null, {
      prefs.edit().clear().apply()
      context.getSystemService(android.app.job.JobScheduler::class.java).schedule(
        android.app.job.JobInfo.Builder(7, android.content.ComponentName(context, TripSyncJob::class.java)).setMinimumLatency(1000).build())
      context.getSystemService(android.app.AlarmManager::class.java).setExact(android.app.AlarmManager.ELAPSED_REALTIME,
        android.os.SystemClock.elapsedRealtime() + 1500,
        android.app.PendingIntent.getBroadcast(context, 0, android.content.Intent(context, ReminderReceiver::class.java),
          android.app.PendingIntent.FLAG_IMMUTABLE))
      context.startService(android.content.Intent(context, UploadService::class.java))
    })
    Text("background: $ran", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FeatureRow("Keystore", "keystore: $keystore", { keystore = runCatching { keystoreCheck() }.getOrElse { "failed: $it" } })
    // Binding a service whose onBind returns null: a device reports it with onNullBinding, then the unbind is clean.
    FeatureRow("Bind", "bind: $binding", {
      val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
          binding = "connected"
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) {
          binding = "disconnected"
        }
        override fun onNullBinding(name: android.content.ComponentName) {
          binding = "null binding to ${name.shortClassName}"
          context.unbindService(this)
        }
      }
      binding = "binding"
      context.bindService(android.content.Intent(context, NullBindingService::class.java), connection, android.content.Context.BIND_AUTO_CREATE)
    })
  }
}

/** A 3 s HEVC + AAC clip played by ExoPlayer into a TextureView: MediaCodec, AudioTrack and the audio clock together. */
@Composable
fun ClipPlayer() {
  val context = LocalContext.current
  var playing by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("-") }
  FeatureRow("Play clip", "clip: $status", { playing = true })
  if (!playing) return
  val player = remember { androidx.media3.exoplayer.ExoPlayer.Builder(context).build() }
  val texture = remember { android.view.TextureView(context) }
  DisposableEffect(Unit) {
    val started = System.nanoTime()
    player.setVideoTextureView(texture)
    player.addListener(object : androidx.media3.common.Player.Listener {
      override fun onPlaybackStateChanged(state: Int) {
        if (state != androidx.media3.common.Player.STATE_ENDED) return
        status = "ended at %.1f s after %.1f s, video %s, audio %s".format(player.currentPosition / 1000.0,
          (System.nanoTime() - started) / 1e9, player.videoFormat?.sampleMimeType, player.audioFormat?.sampleMimeType)
      }

      override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        status = "error: ${error.errorCodeName}"
      }
    })
    player.setMediaItem(androidx.media3.common.MediaItem.fromUri("android.resource://${context.packageName}/${R.raw.clip}"))
    player.prepare()
    player.play()
    onDispose { player.release() }
  }
  androidx.compose.ui.viewinterop.AndroidView({ texture }, Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large))
}

/** Returns a trip name to whoever started it for a result. */
class TripChooserActivity : androidx.activity.ComponentActivity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      dev.apksim.composeapp.ui.theme.ComposeAppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.padding(24.dp)) {
            Text("Choose a trip", style = MaterialTheme.typography.headlineMediumEmphasized)
            listOf("Italian vacation", "Tokyo weekend", "Alps ski trip").forEach { t ->
              val height = ButtonDefaults.MediumContainerHeight
              FilledTonalButton({ setResult(RESULT_OK, android.content.Intent().putExtra("trip", t)); finish() },
                  modifier = Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(height), shapes = ButtonDefaults.shapesFor(height)) {
                Text(t, style = ButtonDefaults.textStyleFor(height))
              }
            }
          }
        }
      }
    }
  }
}

class TripSyncJob : android.app.job.JobService() {
  override fun onStartJob(p: android.app.job.JobParameters): Boolean {
    getSharedPreferences("background", MODE_PRIVATE).edit().putBoolean("job", true).apply()
    return false
  }
  override fun onStopJob(p: android.app.job.JobParameters) = false
}

class ReminderReceiver : android.content.BroadcastReceiver() {
  override fun onReceive(c: android.content.Context, i: android.content.Intent) {
    c.getSharedPreferences("background", android.content.Context.MODE_PRIVATE).edit().putBoolean("alarm", true).apply()
  }
}

class UploadService : android.app.Service() {
  override fun onBind(i: android.content.Intent?) = null
  override fun onStartCommand(i: android.content.Intent?, flags: Int, id: Int): Int {
    getSharedPreferences("background", MODE_PRIVATE).edit().putBoolean("service", true).apply()
    stopSelf(id)
    return START_NOT_STICKY
  }
}

/** AES-GCM with a key that lives in AndroidKeyStore, and an EC signature: what apps sealing tokens do. */
fun keystoreCheck(): String {
  val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  val existed = ks.containsAlias("trip_key")
  val key = (ks.getKey("trip_key", null) as? javax.crypto.SecretKey) ?: javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
    init(android.security.keystore.KeyGenParameterSpec.Builder("trip_key",
      android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes("GCM").setEncryptionPaddings("NoPadding").setKeySize(256).build())
  }.generateKey()
  val enc = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key) }
  val sealed = enc.doFinal("secret trip".toByteArray())
  val dec = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
    init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, enc.iv))
  }
  val back = String(dec.doFinal(sealed))
  if (!ks.containsAlias("sign_key")) {
    java.security.KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
      initialize(android.security.keystore.KeyGenParameterSpec.Builder("sign_key", android.security.keystore.KeyProperties.PURPOSE_SIGN)
        .setDigests("SHA-256").build())
    }.generateKeyPair()
  }
  val priv = ks.getKey("sign_key", null) as java.security.PrivateKey
  val sig = java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(sealed) }.sign()
  val ok = java.security.Signature.getInstance("SHA256withECDSA").apply { initVerify(ks.getCertificate("sign_key").publicKey); update(sealed) }.verify(sig)
  return "$back, signature ${if (ok) "verified" else "BAD"}, key ${if (existed) "existed" else "new"}"
}
