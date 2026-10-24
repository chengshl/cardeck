package io.github.shaonialife.cardeck.buick

import android.content.Context

/**
 * Connection direction for B233, independent of Android package name.
 *
 * Default PASSIVE: the phone only listens for receiver-initiated iAP2; ACL
 * and HFP/A2DP events NEVER authorize an outbound RFCOMM dial.
 * ACTIVE: starting the service permits outbound B233 iAP2 after a short
 * incoming-priority window, with bounded retry pacing.
 *
 * A change takes effect at the next service start, not during an active
 * session. Other receiver families retain their existing behavior.
 */
internal object BuickConnectionModePrefs {
    private const val FILE = "buick_connection_mode"
    private const val ACTIVE = "b233_phone_initiated"

    fun active(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ACTIVE, false)

    fun setActive(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ACTIVE, value).apply()
    }
}

/** Side-effect-free B233 outbound gate; the passive mode must never dial. */
internal object BuickConnectionModePolicy {
    const val INBOUND_PRIORITY_MS = 1_500L
    const val MIN_ACTIVE_RETRY_INTERVAL_MS = 15_000L

    fun mayDial(
        active: Boolean,
        sessionBusy: Boolean,
        runningForMs: Long,
        sinceLastAttemptMs: Long,
        cooldownRemainingMs: Long,
    ): Boolean =
        active && !sessionBusy &&
            runningForMs >= INBOUND_PRIORITY_MS &&
            sinceLastAttemptMs >= MIN_ACTIVE_RETRY_INTERVAL_MS &&
            cooldownRemainingMs <= 0L
}
