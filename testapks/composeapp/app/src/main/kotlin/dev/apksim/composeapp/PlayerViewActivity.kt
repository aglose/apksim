package dev.apksim.composeapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * apksim://player: Media3's PlayerView (Views, no controller) playing the 1080p60 clip looping and muted, FIT in the
 * view (822x462 on the phone in portrait, 1462x822 in landscape). `surface=texture` uses a TextureView instead of the
 * default SurfaceView (the surface type is a layout attribute, so each has its own layout file). The activity declares
 * no configChanges, so a rotation recreates it. By default its player is released in onDestroy and a new one made
 * (a new codec per rotation); with `keep=1` the ExoPlayer survives the recreation through
 * onRetainNonConfigurationInstance and gets the new PlayerView, as an app holding its player in a ViewModel does.
 * The label reads "player: surface|texture, keep|new, instances N" (N: activity instances so far).
 */
class PlayerViewActivity : Activity() {
  private var player: ExoPlayer? = null
  private var playerView: PlayerView? = null

  @Suppress("DEPRECATION")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instances++
    val texture = intent?.data?.getQueryParameter("surface") == "texture"
    val keep = intent?.data?.getQueryParameter("keep") == "1"
    val root = FrameLayout(this)
    root.setBackgroundColor(Color.BLACK)
    val view = layoutInflater.inflate(if (texture) R.layout.player_view_texture else R.layout.player_view_surface, root, false) as PlayerView
    root.addView(view)
    val label = TextView(this)
    label.text = "player: ${if (texture) "texture" else "surface"}, ${if (keep) "keep" else "new"}, instances $instances"
    label.setTextColor(Color.WHITE)
    label.textSize = 18f
    label.setPadding(32, 96, 32, 32)
    root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    setContentView(root)
    playerView = view
    player = (lastNonConfigurationInstance as? ExoPlayer)?.takeIf { keep } ?: ExoPlayer.Builder(this).build().apply {
      volume = 0f
      repeatMode = Player.REPEAT_MODE_ALL
      setMediaItem(MediaItem.fromUri("android.resource://$packageName/${R.raw.video1080}"))
      prepare()
    }
    view.player = player
  }

  // The player outlives a configuration change only with keep=1: it is handed to the next instance, which then owns it.
  override fun onRetainNonConfigurationInstance(): Any? {
    val keep = intent?.data?.getQueryParameter("keep") == "1"
    if (!keep) return null
    val kept = player
    playerView?.player = null // detach from this activity's view and surface before the new view takes it
    player = null
    return kept
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
    playerView?.player = null
    player?.release()
    player = null
    super.onDestroy()
  }

  private companion object {
    var instances = 0
  }
}
