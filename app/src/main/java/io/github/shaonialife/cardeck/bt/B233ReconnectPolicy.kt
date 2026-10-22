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

        /**
         * T27: T21 changed the thrown IOException from the old text
         * "0x4301 not received..." to
         * "iAP2 link closed while waiting 0x4301 after ...".
         * The previous call-site only matched the old text, silently
         * disabling T17 backoff and T18 one-shot inbound recovery.
         *
         * Classify only failures AFTER 0x4300 (waiting for 0x4301).
         * A user stop, pre-0x4300 error or unrelated RF failure must not
         * enter the slow backoff path.
         */
        fun isUnansweredStartSession(message: String?): Boolean {
            val detail = message?.lowercase() ?: return false
            return detail.contains("0x4301 not received") ||
                detail.contains("while waiting 0x4301")
        }
    }

    private var until = 0L
    private var consecutiveStartTimeouts = 0
    private var inboundFallbackUsed = false
    private var inboundFallbackArmed = false

    @Synchronized
    fun reset() {
        until = 0L
        consecutiveStartTimeouts = 0
        inboundFallbackUsed = false
        inboundFallbackArmed = false
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
        // T18: only after repeated outbound 0x4300 failures, allow ONE
        // receiver-initiated (...cafe) iAP2 session. The B233 did initiate
        // this direction in a T17 log, yet the server always discarded it.
        // One-shot and B233-only prevent a persistent dual-session race.
        if (consecutiveStartTimeouts >= 2 && !inboundFallbackUsed) {
            inboundFallbackArmed = true
        }
        val backoff = (15_000L shl (consecutiveStartTimeouts - 1))
            .coerceAtMost(MAX_START_TIMEOUT_BACKOFF_MS)
        until = maxOf(until, nowMs + backoff)
        return until - nowMs
    }

    /** Atomic one-shot gate used only after the inbound peer is verified B233. */
    @Synchronized
    fun claimInboundRecoveryAttempt(): Boolean {
        if (!inboundFallbackArmed || inboundFallbackUsed) return false
        inboundFallbackArmed = false
        inboundFallbackUsed = true
        return true
    }

    @Synchronized
    fun remainingMs(nowMs: Long): Long = (until - nowMs).coerceAtLeast(0L)

    @Synchronized
    fun untilMs(): Long = until
}
