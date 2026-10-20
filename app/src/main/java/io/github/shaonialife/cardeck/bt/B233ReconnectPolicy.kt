package io.github.shaonialife.cardeck.bt

/**
 * B233 T17 reconnect pacing only. No Bluetooth, Wi-Fi or wall-clock dependencies:
 * callers provide elapsed-realtime milliseconds, making policy deterministic.
 *
 * The E5 often accepts iAP2/MFi but then never acknowledges CarPlay 0x4300
 * after a failed audio session. Rapid retries and repeated Bluetooth profile
 * disconnects should not churn the head unit while it is releasing that slot.
 *
 * This is a conservative recovery experiment, not proof that cooldown alone
 * fixes receiver firmware that requires a hard restart.
 */
internal class B233ReconnectPolicy {
    companion object {
        const val AFTER_AUDIO_TEARDOWN_MS = 20_000L
        const val MAX_START_TIMEOUT_BACKOFF_MS = 60_000L
    }

    private var until = 0L
    private var consecutiveStartTimeouts = 0

    @Synchronized
    fun reset() {
        until = 0L
        consecutiveStartTimeouts = 0
    }

    @Synchronized
    fun onStartSessionAccepted() {
        // Any new 0x4301 proves the head unit's CarPlay handshake slot reopened.
        reset()
    }

    @Synchronized
    fun onAudioSessionEnded(nowMs: Long): Long {
        until = maxOf(until, nowMs + AFTER_AUDIO_TEARDOWN_MS)
        return until - nowMs
    }

    @Synchronized
    fun onStartSessionTimeout(nowMs: Long): Long {
        consecutiveStartTimeouts = (consecutiveStartTimeouts + 1).coerceAtMost(4)
        val backoff = (15_000L shl (consecutiveStartTimeouts - 1))
            .coerceAtMost(MAX_START_TIMEOUT_BACKOFF_MS)
        until = maxOf(until, nowMs + backoff)
        return until - nowMs
    }

    @Synchronized
    fun remainingMs(nowMs: Long): Long = (until - nowMs).coerceAtLeast(0L)

    @Synchronized
    fun untilMs(): Long = until
}
