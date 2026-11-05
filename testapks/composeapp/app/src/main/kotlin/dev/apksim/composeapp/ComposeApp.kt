package dev.apksim.composeapp

import android.app.Application
import android.net.ConnectivityManager

/**
 * The app's Application. It only notes the network ConnectivityManager reports as it starts (NetworkActivity shows
 * it: apksim's --network-profile offline must hold from before onCreate); the androidTest runner swaps in a subclass
 * (HarnessRunner).
 */
open class ComposeApp : Application() {
  override fun onCreate() {
    super.onCreate()
    networkAtStart = GreetingClient.connectivity(getSystemService(ConnectivityManager::class.java))
  }

  companion object {
    /** ConnectivityManager's default network in Application.onCreate, or null before it ran. */
    @JvmStatic var networkAtStart: String? = null
  }
}
