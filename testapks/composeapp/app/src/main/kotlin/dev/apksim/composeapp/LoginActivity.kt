package dev.apksim.composeapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * apksim://login: an app behind a username and password form, for `crawl.py --autofill` (scripts/crawl-check.py's
 * sign-in walks, docs/crawl.md "Apps behind a sign-in"). Signed out it shows the form ("Username", "Password", "Sign
 * in"); the test values below sign in, which is kept in the app's preferences, and the screen then shows "Signed in
 * as tester" with an "Account" button that opens [LoginAccountActivity]. A walk that never signs in finds only the
 * form and its error. Plain views; the values are this test app's own, not anyone's.
 */
class LoginActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    show()
  }

  private fun show() {
    val user = user(this)
    val page = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 160, 48, 48)
    }
    if (user == null) {
      page.addView(TextView(this).apply { text = "Sign in to continue"; textSize = 24f })
      val name = EditText(this).apply { hint = "Username"; inputType = InputType.TYPE_CLASS_TEXT }
      val password = EditText(this).apply {
        hint = "Password"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      }
      val error = TextView(this)
      page.addView(name)
      page.addView(password)
      page.addView(Button(this).apply {
        text = "Sign in"
        setOnClickListener {
          if (name.text.toString() == TEST_USER && password.text.toString() == TEST_PASSWORD) {
            prefs(this@LoginActivity).edit().putString("user", TEST_USER).commit()
            show()
          } else {
            error.text = "Wrong username or password"
          }
        }
      })
      page.addView(error)
    } else {
      page.addView(TextView(this).apply { text = "Signed in as $user"; textSize = 24f })
      page.addView(Button(this).apply {
        text = "Account"
        setOnClickListener { startActivity(Intent(this@LoginActivity, LoginAccountActivity::class.java)) }
      })
      // The crawler's skip list keeps it from tapping this.
      page.addView(Button(this).apply {
        text = "Sign out"
        setOnClickListener {
          prefs(this@LoginActivity).edit().remove("user").commit()
          show()
        }
      })
    }
    setContentView(page)
  }

  companion object {
    const val TEST_USER = "tester"
    const val TEST_PASSWORD = "apksim-test"

    fun prefs(c: Context) = c.getSharedPreferences("login", Context.MODE_PRIVATE)

    fun user(c: Context): String? = prefs(c).getString("user", null)
  }
}

/** Behind [LoginActivity]'s sign-in: a screen only a signed-in walk finds. */
class LoginAccountActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(48, 160, 48, 48)
      addView(TextView(this@LoginAccountActivity).apply {
        text = "Account: ${LoginActivity.user(this@LoginAccountActivity) ?: "signed out"}"
        textSize = 24f
      })
      addView(TextView(this@LoginAccountActivity).apply { text = "Plan: free" })
    })
  }
}
