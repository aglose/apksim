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
 * `apksim://video?codec=vp9` plays a 2 s 640x360 VP9 WebM instead (no audio track), and the label then follows the
 * player: "video: vp9 TRACKS, frames N", TRACKS being "selected" or "unsupported" as the player reports its video track.
 */
class VideoActivity : Activity() {
  private var player: ExoPlayer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val vp9 = intent?.data?.getQueryParameter("codec") == "vp9"
    val root = FrameLayout(this)
    root.setBackgroundColor(Color.BLACK)
    val texture = TextureView(this)
    root.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val label = TextView(this)
    root.addView(
      label.apply {
        text = if (vp9) "video: vp9 preparing" else "video: 1080p60 H.264, looping"
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
      setMediaItem(MediaItem.fromUri("android.resource://$packageName/${if (vp9) R.raw.video_vp9 else R.raw.video1080}"))
      if (vp9) {
        var tracks = "no tracks"
        var frames = 0
        addListener(object : Player.Listener {
          override fun onTracksChanged(t: androidx.media3.common.Tracks) {
            val video = t.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            tracks = if (video == null) "no video track" else if (video.isSelected) "selected" else "unsupported"
            label.text = "video: vp9 $tracks, frames $frames"
          }

          override fun onRenderedFirstFrame() {
            frames++
            label.text = "video: vp9 $tracks, frames $frames"
          }
        })
      }
      prepare()
    }
  }

  // Plays only while shown, so a stopped screen left on the stack decodes nothing behind another one.
  override fun onStart() {
    super.onStart()
    player?.play()
  }

  override fun onStop() {
    player?.pause()
    super.onStop()
  }

  override fun onDestroy() {
    player?.release()
    player = null
    super.onDestroy()
  }
}
