package com.barret.navbridge

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/**
 * Base class for every screen in the app. Its only job is to install a base
 * context whose resources resolve in the language chosen in Settings, rather
 * than the phone's system language.
 *
 * Every Activity has to extend this. An Activity that forgets will look
 * correct on any phone whose system language already matches the setting,
 * which is the worst kind of wrong -- it works on the developer's device and
 * fails on the user's.
 */
open class LocaleAwareActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
