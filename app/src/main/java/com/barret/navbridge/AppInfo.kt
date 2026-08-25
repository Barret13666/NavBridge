package com.barret.navbridge

import android.content.Context

/**
 * The app's version string, read from the package the system actually
 * installed rather than from a constant compiled in beside it.
 *
 * That distinction is the point: PackageManager reports what is on the phone,
 * so an APK sideloaded over a newer one, or a debug build left behind on a
 * test device, reports itself honestly instead of whatever the source tree
 * happened to say when it was last opened.
 *
 * Shared by the main screen's heading and the About screen so the two cannot
 * disagree -- which they could, and briefly did, when only one of them
 * displayed it.
 */
object AppInfo {

    /** e.g. "1.0.5". Empty when the package cannot be read, which should not happen. */
    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** e.g. "v1.0.5", or "" so callers can append it without producing a stray "v". */
    fun versionLabel(context: Context): String {
        val name = versionName(context)
        return if (name.isEmpty()) "" else "v$name"
    }
}
