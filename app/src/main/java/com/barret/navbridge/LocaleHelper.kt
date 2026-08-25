package com.barret.navbridge

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app's language, chosen in Settings rather than inherited from the phone.
 *
 * English is the default on every device, including a Russian-locale one. That
 * is a deliberate choice, not an oversight: the firmware, the UDP protocol and
 * every log line in this project are English, so an interface that quietly
 * disagrees with them makes a fault harder to describe and harder to fix. A
 * Russian speaker changes it once, in one place, and it sticks.
 *
 * Applied through AppCompatDelegate.setApplicationLocales, which is the
 * per-app locale API. On Android 13+ it hands the choice to the framework, so
 * it also shows up under the system's own per-app language settings; below 13
 * AppCompat emulates it. Either way this is the supported route -- the older
 * trick of overriding Configuration in attachBaseContext is fragile across
 * process restarts and does not survive a service being restarted by the
 * system.
 *
 * The choice is ALSO kept in SharedPreferences, and not only so that
 * apply() can restore it at startup: the foreground service builds notification
 * text and the announcer builds spoken phrases, and both can run in situations
 * where no Activity has been through onCreate yet. They read the tag from here
 * and resolve strings against it directly.
 */
object LocaleHelper {

    const val PREFS = "nmea_bridge"
    const val KEY_LANGUAGE = "app_language"

    /** BCP-47 tags, index-aligned with R.array.language_labels. */
    val TAGS = listOf("en", "ru")

    fun savedTag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY_LANGUAGE, TAGS[0]) ?: TAGS[0]
        return if (tag in TAGS) tag else TAGS[0]
    }

    fun savedIndex(context: Context): Int = TAGS.indexOf(savedTag(context)).coerceAtLeast(0)

    /** Persists the choice and switches the UI to it immediately. */
    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, tag)
            .apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Re-applies the stored choice. Called from Application.onCreate. */
    fun apply(context: Context) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedTag(context)))
    }

    fun locale(context: Context): Locale = Locale.forLanguageTag(savedTag(context))

    /**
     * Resolves a string resource in the chosen language regardless of what the
     * calling context happens to be configured with.
     *
     * Needed because the service and the announcer are not Activities: their
     * base Context carries the SYSTEM configuration, so getString() on them
     * would hand back the system language and ignore the setting entirely.
     * Creating a configuration context per call is cheap and keeps the
     * lookup honest.
     */
    fun string(context: Context, resId: Int, vararg args: Any): String {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale(context))
        val localized = context.createConfigurationContext(config)
        return if (args.isEmpty()) localized.getString(resId)
        else localized.getString(resId, *args)
    }
}
