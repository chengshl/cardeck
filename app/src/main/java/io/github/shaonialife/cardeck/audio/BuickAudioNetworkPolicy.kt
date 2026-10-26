package io.github.shaonialife.cardeck.audio

/**
 * T74: signal and timer *diagnostic* thresholds. RSSI is obtained on the Android
 * transmitter's vehicle Wi-Fi Network; no Apple driver ACK/VI counters are
 * available to this process, and an UDP send() returning says nothing about
 * successful playback or 802.11 MAC acknowledgements.
 */
internal object BuickAudioNetworkPolicy {
    const val TIMED_TRIAL_MS = 18_000L
    const val RSSI_DROP_WARN_DB = 10
    const val DROP_WARN_STREAK = 3
    const val SLOW_UDP_SEND_MS = 40L

    fun dropDb(baselineRssi: Int?, currentRssi: Int?): Int? {
        if (baselineRssi == null || currentRssi == null) return null
        if (baselineRssi !in -100..-1 || currentRssi !in -100..-1) return null
        return (baselineRssi - currentRssi).coerceAtLeast(0)
    }

    fun shouldWarnForRssi(dropDb: Int?, consecutiveSamples: Int): Boolean =
        dropDb != null && dropDb >= RSSI_DROP_WARN_DB && consecutiveSamples >= DROP_WARN_STREAK

    fun shouldAttemptTimedFallback(
        enabled: Boolean,
        isBuickB233: Boolean,
        sessionUnchanged: Boolean,
        audioRunning: Boolean,
        elapsedMs: Long,
    ): Boolean =
        enabled && isBuickB233 && sessionUnchanged && audioRunning && elapsedMs >= TIMED_TRIAL_MS
}
