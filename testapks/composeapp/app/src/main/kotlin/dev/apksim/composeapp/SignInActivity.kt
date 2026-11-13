package dev.apksim.composeapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * apksim://signin: a browser sign-in as OAuth SDKs do it (Auth0's AuthenticationActivity, AppAuth's
 * AuthorizationManagementActivity; issue 336). "Sign in" opens an authorize page outside the app, a VIEW of a web link
 * with `redirect_uri=apksim://signin-callback`; the provider's redirect comes back to [SignInRedirectActivity], which
 * hands it to this activity (singleTask) with CLEAR_TOP | SINGLE_TOP, so it arrives in onNewIntent, and the next
 * onResume reads it: "signin: code=abc (instances 1, new intents 1)". A second copy of this activity, where the
 * running one should have got the intent, shows "signin: not started" and a higher instance count.
 * `apksim://signin?authorize=URL` sets the authorize page (a local fixture).
 */
class SignInActivity : Activity() {
  private val status by lazy { TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER } }
  private var launched = false
  private var newIntents = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instances++
    val authorize = intent.data?.getQueryParameter("authorize") ?: "https://example.com/authorize"
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      addView(status)
      addView(Button(context).apply {
        text = "Sign in"
        setOnClickListener {
          launched = true
          intent = Intent()  // what came before the launch is not the answer
          val url = Uri.parse(authorize).buildUpon()
              .appendQueryParameter("client_id", "apksim")
              .appendQueryParameter("redirect_uri", "apksim://signin-callback")
              .build()
          show("waiting for the browser")
          startActivity(Intent(Intent.ACTION_VIEW, url))
        }
      })
    })
    show("not started")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    newIntents++
    setIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    val back = intent.data
    if (launched && back != null && back.host == "signin-callback") {
      launched = false
      show("code=" + back.getQueryParameter("code"))
    }
  }

  private fun show(what: String) {
    status.text = "signin: $what (instances $instances, new intents $newIntents)"
  }

  companion object {
    var instances = 0
  }
}

/** The redirect's receiver, as an OAuth SDK's: passes the callback to the waiting [SignInActivity] and finishes. */
class SignInRedirectActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    startActivity(Intent(this, SignInActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .setData(intent.data))
    finish()
  }
}
