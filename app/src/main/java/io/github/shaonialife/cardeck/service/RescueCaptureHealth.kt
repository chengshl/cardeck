package io.github.shaonialife.cardeck.service

import android.content.Context
import android.os.SystemClock

/**
 * Evidence-free local health metadata. Never stores raw logcat, tokens or device identifiers.
 * A previous "active" flag is not proof of a living service after process death.
 */
internal object RescueCaptureHealth {
    private const val PREFS = "rescue_capture_health"

    data class Snapshot(
        val startedAt: Long,
        val lastHeartbeatAt: Long,
        val lastMatchedLogAt: Long,
        val previousGapMinutes: Long,
        val lastStopReason: String,
    )

    fun snapshot(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            p.getLong("started", 0L),
            p.getLong("heartbeat", 0L),
            p.getLong("matched", 0L),
            p.getLong("gap_minutes", 0L),
            p.getString("stop_reason", "") ?: "",
        )
    }

    @Synchronized
    fun started(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("started", System.currentTimeMillis())
            .putLong("heartbeat_elapsed", 0L)
            .putLong("gap_minutes", 0L)
            .putString("stop_reason", "")
            .apply()
    }

    /** A delayed Handler callback can be Android deep sleep, not proof of logcat data loss. */
    @Synchronized
    fun heartbeat(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowElapsed = SystemClock.elapsedRealtime()
        val before = p.getLong("heartbeat_elapsed", 0L)
        val gap = if (before > 0L && nowElapsed >= before) {
            (nowElapsed - before) / 60_000L
        } else 0L
        p.edit()
            .putLong("heartbeat", System.currentTimeMillis())
            .putLong("heartbeat_elapsed", nowElapsed)
            .putLong("gap_minutes", gap)
            .apply()
        return gap
    }

    @Synchronized
    fun matchedLog(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Only refresh once a minute to avoid frequent disk writes in noisy sessions.
        if (now - p.getLong("matched", 0L) >= 60_000L) {
            p.edit().putLong("matched", now).apply()
        }
    }

    @Synchronized
    fun stopped(context: Context, reason: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("stop_reason", reason.take(96))
            .apply()
    }
}
