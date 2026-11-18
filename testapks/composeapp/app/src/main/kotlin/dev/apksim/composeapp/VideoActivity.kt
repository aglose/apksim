package dev.apksim.composeapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
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
 * `surface=1` draws into a SurfaceView instead of a TextureView, and `fit=1` puts the view in a centred 16:9 box, as
 * Media3's PlayerView does. The activity handles its own configuration changes, so a rotation resizes the view under the
 * same codec (issue 492). `ballast=MB` keeps about that many MB of small live objects for the process's life, as a
 * large app's heap, which makes a full collection take about as long as it would there.
 */
class VideoActivity : Activity() {
  private var player: ExoPlayer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val vp9 = intent?.data?.getQueryParameter("codec") == "vp9"
    val surface = intent?.data?.getQueryParameter("surface") == "1"
    val fit = intent?.data?.getQueryParameter("fit") == "1"
    intent?.data?.getQueryParameter("ballast")?.toIntOrNull()?.let { holdBallast(it) }
    val root = FrameLayout(this)
    root.setBackgroundColor(Color.BLACK)
    val texture = TextureView(this)
    val surfaceView = SurfaceView(this)
    val videoView = if (surface) surfaceView else texture
    if (fit) {
      // As Media3's PlayerView lays it out: a 16:9 box fitted to the screen (822x462 portrait, 1462x822 landscape).
      val box = AspectFit(this)
      box.addView(videoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
      root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    } else {
      root.addView(videoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
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
      if (surface) setVideoSurfaceView(surfaceView) else setVideoTextureView(texture)
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

/** Small objects kept alive for good (`ballast=MB`): what makes a full collection slow is how much it has to mark. */
private val ballast = ArrayList<Array<LongArray>>()

private fun holdBallast(mb: Int) {
  if (ballast.isNotEmpty()) return
  // Each LongArray(4) is ~48 bytes on the JVM; 1024 of them and their array are ~50 KB.
  repeat(mb * 20) { ballast.add(Array(1024) { LongArray(4) }) }
}

/** A FrameLayout as large as fits the space given at 16:9, centred in it (Media3's AspectRatioFrameLayout, RESIZE_MODE_FIT). */
private class AspectFit(context: android.content.Context) : FrameLayout(context) {
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val w = View.MeasureSpec.getSize(widthMeasureSpec)
    val h = View.MeasureSpec.getSize(heightMeasureSpec)
    val (bw, bh) = if (w * 9 <= h * 16) w to w * 9 / 16 else h * 16 / 9 to h
    super.onMeasure(
      View.MeasureSpec.makeMeasureSpec(bw, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(bh, View.MeasureSpec.EXACTLY),
    )
  }
}
