package dev.apksim.composeapp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * apksim://recreate: Activity.recreate() called more than once (issue 410: an in-app theme switch that recreates
 * twice ended the app with a NullPointerException in Robolectric's ActivityController, as the second recreate ran
 * inside the first). "Recreate twice" calls recreate() twice in a row, which a device relaunches once; "Recreate from
 * onCreate" recreates once, and the new activity recreates again from its own onCreate, so two relaunches one after
 * the other. The status line counts activities made and how many are alive: "recreate: instances 2, live 1".
 */
class RecreateActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instances++
    live++
    val status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      addView(status)
      addView(Button(context).apply {
        text = "Recreate twice"
        setOnClickListener {
          recreate()
          recreate()
        }
      })
      addView(Button(context).apply {
        text = "Recreate from onCreate"
        setOnClickListener {
          againInOnCreate = true
          recreate()
        }
      })
    })
    status.text = "recreate: instances $instances, live $live"
    if (againInOnCreate) {
      againInOnCreate = false
      recreate()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    live--
  }

  companion object {
    var instances = 0
    var live = 0
    var againInOnCreate = false
  }
}
