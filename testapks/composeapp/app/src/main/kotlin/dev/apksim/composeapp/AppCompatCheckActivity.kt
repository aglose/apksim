package dev.apksim.composeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView

/**
 * apksim://appcompat: a colour and a vector drawable that differ in night mode (blue by day, yellow by night), read
 * through AppCompatResources, whose caches are per context, with the activity's context and with the application's.
 * The activity is recreated on configuration changes, so after `appearance dark` every value must be the night one:
 * "appcompat: activity color=#ffffff00 vector=#ffffff00, app color=... vector=..., instances N". The application's
 * context keeps the configuration it has on a device too; the line shows what it gives.
 */
class AppCompatCheckActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instances++
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(32, 160, 32, 32)
    }
    val image = AppCompatImageView(this)
    image.setImageResource(R.drawable.appcompat_check_square)
    column.addView(image, LinearLayout.LayoutParams(96, 96))
    val text = TextView(this)
    text.textSize = 16f
    text.text = "appcompat: activity ${read(this)}, app ${read(applicationContext)}, instances $instances"
    column.addView(text)
    setContentView(column)
    // Kept, as an app's image cache or a static would keep them: a cached drawable is found again only while alive.
    kept.add(AppCompatResources.getDrawable(applicationContext, R.drawable.appcompat_check_square)!!)
    kept.add(image.drawable)
  }

  private fun read(context: Context): String {
    val color = AppCompatResources.getColorStateList(context, R.color.appcompat_check_mode).defaultColor
    val drawable = AppCompatResources.getDrawable(context, R.drawable.appcompat_check_square)!!
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, 8, 8)
    drawable.draw(Canvas(bitmap))
    return "color=#%08x vector=#%08x".format(color, bitmap.getPixel(4, 4))
  }

  private companion object {
    var instances = 0
    val kept = ArrayList<Any>()
  }
}
