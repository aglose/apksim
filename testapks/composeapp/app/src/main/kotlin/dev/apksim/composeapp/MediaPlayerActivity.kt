package dev.apksim.composeapp

import android.media.MediaPlayer
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * apksim://mediaplayer?src=NAME (a sound of assets/media, mp3.mp3 by default): the way an app plays a sound effect,
 * the framework MediaPlayer over an asset's descriptor, prepareAsync, start in onPrepared (issue 488). apksim answers
 * a source nobody registered with the file's own duration and plays silence, so the screen reads
 * "mediaplayer: mp3.mp3 duration 1000 ms, completed after 1010 ms".
 */
class MediaPlayerActivity : androidx.activity.ComponentActivity() {
  private val status = mutableStateOf("mediaplayer: starting")
  private var player: MediaPlayer? = null

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    val src = intent.data?.getQueryParameter("src") ?: "mp3.mp3"
    val started = SystemClock.elapsedRealtime()
    var duration = -1
    try {
      val mp = MediaPlayer()
      player = mp
      assets.openFd("media/$src").use { afd -> mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
      mp.setOnPreparedListener {
        duration = it.duration
        status.value = "mediaplayer: $src prepared, duration $duration ms"
        it.start()
      }
      mp.setOnCompletionListener {
        status.value = "mediaplayer: $src duration $duration ms, completed after ${SystemClock.elapsedRealtime() - started} ms"
      }
      mp.setOnErrorListener { _, what, extra ->
        status.value = "mediaplayer: $src error $what/$extra"
        true
      }
      mp.prepareAsync()
    } catch (e: Exception) {
      status.value = "mediaplayer: $src failed: $e"
    }
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("MediaPlayer", style = MaterialTheme.typography.titleLarge)
            Text(status.value, Modifier.padding(top = 12.dp))
          }
        }
      }
    }
  }

  override fun onDestroy() {
    player?.release()
    super.onDestroy()
  }
}
