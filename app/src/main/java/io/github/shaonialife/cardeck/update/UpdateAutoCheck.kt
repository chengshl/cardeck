package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.content.Context
import android.os.Build

/**
 * Foreground-only automatic update check. Not a background updater or installer.
 * Shared debounce prevents a popup storm when MainActivity -> BuickMenuActivity.
 */
internal object UpdateAutoCheck {
    private const val PREF = "cardeck_auto_update_probe"
    private const val KEY_WHEN = "last_attempt_at-"
    private const val RETRY_MS = 15L * 60 * 1000 // wakeups within one visit don't recheck

    internal fun due(lastAttempt: Long, now: Long): Boolean =
        lastAttempt <= 0L || now < lastAttempt || now - lastAttempt >= RETRY_MS

    internal fun track(channel: String, branch: String, code: Long): String =
        "$channel|$branch|$code"

    fun checkWhenForeground(activity: Activity) {
        if (!BuickUpdater.autoEnabled(activity)) return
        val origin = activity.getString(io.github.shaonialife.cardeck.R.string.cardeck_installed_build_origin)
        val channel = UpdatePreference.selected(activity)
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
            else { @Suppress("DEPRECATION") packageInfo.versionCode.toLong() }
        val key = KEY_WHEN + track(channel.key, origin, version) + "|" + activity.javaClass.simpleName
        val prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!due(prefs.getLong(key, 0L), now)) return
            // In-memory SharedPreferences update is synchronous even with apply().
            // Each visible screen has its own cooldown; switching screens does not
            // re-enable a check in an already checked screen.
            prefs.edit().putLong(key, now).apply()
        }
        BuickUpdater.checkSelected(activity, silent = true)
    }
}
