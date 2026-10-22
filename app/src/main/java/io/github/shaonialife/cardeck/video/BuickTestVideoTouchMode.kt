package io.github.shaonialife.cardeck.video

/** Buick-Test-only explicit opt-in to virtual display and touch injection.
 * WARNING: Sony Xperia 1 V / Android 15 / B233 previously rebooted system_server
 * on display-add. Buick-main must retain its T28 displayless safety guard. */
internal object BuickTestVideoTouchMode {
    const val RESTORE_FULL_DESKTOP_AND_TOUCH = true

    fun useT28Displayless(sonyB233CrashRisk: Boolean): Boolean =
        sonyB233CrashRisk && !RESTORE_FULL_DESKTOP_AND_TOUCH
}
