package dev.apksim.composeapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * apksim://video: a 2 s 1080p 60 fps H.264 clip looping full-screen in a TextureView with ExoPlayer, muted. On a display
 * larger than the clip (`--device tablet`) every picture is scaled up to the view, a new one each frame.
 */
class VideoActivity : Activity() {
  private var player: ExoPlayer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this)
    root.setBackgroundColor(Color.BLACK)
    val texture = TextureView(this)
    root.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    root.addView(
      TextView(this).apply {
        text = "video: 1080p60 H.264, looping"
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(32, 96, 32, 32)
      },
      FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT),
    )
    setContentView(root)
    player = ExoPlayer.Builder(this).build().apply {
      setVideoTextureView(texture)
      volume = 0f
      repeatMode = Player.REPEAT_MODE_ALL
      setMediaItem(MediaItem.fromUri("android.resource://$packageName/${R.raw.video1080}"))
      prepare()
      play()
    }
  }

  override fun onDestroy() {
    player?.release()
    player = null
    super.onDestroy()
  }
}
