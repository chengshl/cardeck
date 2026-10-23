package io.github.shaonialife.cardeck.video

import android.content.Context

/**
 * T40: allow full desktop and HID injection in Buick-main while retaining
 * the existing T28 fail-safe diagnostic route as a user-selectable fallback.
 *
 * Sony Xperia 1 V Android 15 previously suffered system_server crashes during
 * virtual display creation; restoring desktop is an explicit high-risk choice.
 * Default ON to satisfy Buick-main display/touch restoration. Turning this OFF
 * restores T28 displayless on the affected Sony+Buick receiver combination.
 */
internal object BuickMainDisplayMode {
    private const val PREFS = "buick_main_display"
    private const val KEY_FULL_DESKTOP = "enable_full_desktop_touch"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FULL_DESKTOP, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FULL_DESKTOP, enabled).apply()
    }

    /** Return true only when Sony B233 crash risk exists and the user wants fallback. */
    fun useT28Displayless(sonyB233CrashRisk: Boolean, enableFullDesktop: Boolean): Boolean =
        sonyB233CrashRisk && !enableFullDesktop
}
