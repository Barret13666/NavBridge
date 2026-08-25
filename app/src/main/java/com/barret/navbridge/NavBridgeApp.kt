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

        // Nothing is written here on first run. The preference is left absent
        // until the user actually picks a language, and until then
        // LocaleHelper answers from the phone's own configuration -- so the
        // app follows the system, and follows it still if the system language
        // is changed later. Pinning a value at first launch would have quietly
        // frozen that.
        LocaleHelper.apply(this)
    }
}
