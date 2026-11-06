package dev.apksim.composeapp

import android.app.Activity
import android.os.Bundle

/** An activity another one starts (StartActivityTest): it records that it came up, with the intent's extra. */
class SecondActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    createdWith = intent.getStringExtra("from")
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    current = this
  }

  companion object {
    @Volatile var createdWith: String? = null
    @Volatile var resumed = false
    @Volatile var current: SecondActivity? = null
  }
}
