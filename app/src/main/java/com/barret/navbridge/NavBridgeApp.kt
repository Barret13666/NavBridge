package com.barret.navbridge

import android.app.Application

/**
 * Exists for one reason: the saved language has to be applied before the first
 * Activity is created, or the app flashes up in the system language and only
 * corrects itself on the next screen.
 *
 * Also covers the case where Android restarts the process for the foreground
 * service alone, with no Activity involved at all -- the notification text
 * still needs to come out in the chosen language.
 */
class NavBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleHelper.apply(this)
    }
}
