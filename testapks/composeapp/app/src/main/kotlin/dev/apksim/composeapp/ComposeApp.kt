package dev.apksim.composeapp

import android.app.Application

/** The app's Application. It does nothing itself; the androidTest runner swaps in a subclass (HarnessRunner). */
open class ComposeApp : Application()
