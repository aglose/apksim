package dev.apksim.composeapp

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout

/** apksim://edittext: a View EditText with focus, whose caret blinks (Editor$Blink); `settle` must still answer (issue 479). */
class EditTextActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val field = EditText(this).apply { hint = "Type here"; requestFocus() }
    setContentView(LinearLayout(this).apply { addView(field) })
  }
}
