package com.barret.navbridge

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app's language: the phone's own until somebody chooses otherwise in
 * Settings, and that choice from then on.
 *
 * Following the system until an explicit choice is made means the Settings
 * spinner always shows the language actually on screen. It did not, briefly:
 * the default was pinned to English while the resources still resolved to the
 * system language, so a fresh install on a Russian phone showed a Russian
 * interface with "English" selected. Both halves were reporting truthfully
 * from different sources. Reading the same source for both is what fixes it,
 * and taking that source to be the phone is the least surprising choice --
 * everything else on the device already works that way.
 *
 * Only languages this app actually ships count as a match. A phone set to
 * German gets English, not a half-translated screen.
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

    /**
     * The language the app should be in: whatever was chosen in Settings, or
     * the phone's own language until something is chosen.
     *
     * Resources.getSystem() rather than the caller's resources, deliberately.
     * The caller's configuration has usually been through wrap() already, so
     * asking it what the "system" language is would just be reading our own
     * answer back. Resources.getSystem() is the device configuration and is
     * untouched by per-app locales.
     */
    fun systemDefaultTag(): String {
        val locales = android.content.res.Resources.getSystem().configuration.locales
        for (i in 0 until locales.size()) {
            val language = locales.get(i).language
            if (language in TAGS) return language
        }
        return TAGS[0]   // no language we ship -- English, which is the default locale
    }

    fun savedTag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY_LANGUAGE, null)
        return if (tag != null && tag in TAGS) tag else systemDefaultTag()
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

    /**
     * Re-asserts the stored choice through the per-app locale API. Called from
     * Application.onCreate.
     *
     * This is what makes the choice visible to the SYSTEM -- it shows up under
     * Settings > System > Languages > App languages on Android 13+, and
     * survives independently of this app's own preference. What it is NOT is a
     * guarantee about the resources of the activity starting right now: on
     * Android 13+ the call goes to the framework's LocaleManager, which
     * applies it by restarting the activity, and on a cold first launch the
     * first screen can be built and shown before that restart happens. Hence
     * wrap(), below, which does not wait for anybody.
     */
    fun apply(context: Context) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedTag(context)))
    }

    /**
     * Returns a Context whose resources resolve in the chosen language, for an
     * Activity to install as its base context before anything is inflated.
     *
     * THE BUG THIS FIXES: on a fresh install the Settings screen said English
     * while every label around it was Russian. Both were reporting honestly --
     * the spinner reads the saved preference, which defaults to English, while
     * the labels came from whatever the ACTIVITY's configuration said, which
     * on a first cold launch was still the phone's system language because the
     * per-app locale had not taken effect yet. Two sources of truth, one of
     * them lagging, and the lag is exactly one app launch.
     *
     * Overriding the configuration here removes the lag rather than racing it:
     * the resources are resolved from the preference directly, at
     * attachBaseContext time, before a single view exists. apply() still runs
     * for the system integration it provides, and the two agree because they
     * read the same preference.
     *
     * Locale.setDefault as well, so anything that formats without an explicit
     * locale -- date and number formatting inside library code, mostly -- lines
     * up with the visible language instead of quietly following the system.
     */
    fun wrap(context: Context): Context {
        val locale = locale(context)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
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
