package dev.apksim.composeapp

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * apksim://view-animations: View animations as the framework plays them. "Replace" swaps a fragment with
 * `setCustomAnimations` (300 ms fade in/out, AndroidX Fragment's AnimationEffect) and "Fade" runs a 300 ms
 * [AlphaAnimation] on a label that reports how long it took. The screen is done in ~0.5 s, so `settle` must be too:
 * Robolectric's AnimationRunner stepped by the Choreographer's 1 ms frame delay kept it busy for ~7 s (issue 475).
 */
class ViewAnimationsActivity : FragmentActivity() {
  private var replaced = 1
  private lateinit var fade: TextView

  class Page : Fragment() {
    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?): View =
        TextView(requireContext()).apply { text = "page ${requireArguments().getInt("n")}"; textSize = 24f }
  }

  private fun page(n: Int) = Page().apply { arguments = Bundle().apply { putInt("n", n) } }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val host = FrameLayout(this).apply { id = View.generateViewId() }
    val status = TextView(this).apply { text = "fragment: 1"; textSize = 20f }
    fade = TextView(this).apply { text = "fade: idle"; textSize = 20f }
    val replace = Button(this).apply {
      text = "Replace"
      setOnClickListener {
        replaced++
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.apksim_fade_in, R.anim.apksim_fade_out)
            .replace(host.id, page(replaced)).commit()
        status.text = "fragment: $replaced"
      }
    }
    val fadeButton = Button(this).apply {
      text = "Fade"
      setOnClickListener {
        val start = SystemClock.uptimeMillis()
        fade.startAnimation(AlphaAnimation(0f, 1f).apply {
          duration = 300
          setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(a: Animation) {}
            override fun onAnimationRepeat(a: Animation) {}
            override fun onAnimationEnd(a: Animation) { fade.text = "fade: ended after ${SystemClock.uptimeMillis() - start} ms" }
          })
        })
      }
    }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(status); addView(fade); addView(replace); addView(fadeButton); addView(host)
    })
    if (savedInstanceState == null) supportFragmentManager.beginTransaction().add(host.id, page(1)).commit()
  }
}
