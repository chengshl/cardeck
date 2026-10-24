package io.github.shaonialife.cardeck.bt

/**
 * Buick-Test T65: the receiver decides when CarPlay may connect.
 *
 * An incoming iAP2 RFCOMM session never needs this gate. An outgoing RFCOMM
 * dial to the accessory UUID is allowed only after an actual vehicle Bluetooth
 * connection event. This is a compatibility bridge for receivers that initiate
 * the workflow but require the phone to open the accessory-side socket.
 *
 * No polling-driven invitation, no automatic re-arm on failure, and no carryover
 * from a previous established session. Manual disconnect stops the caller.
 */
internal class B233CarLedConnectionPolicy {
    companion object {
        const val INVITATION_WINDOW_MS = 45_000L
        const val DUPLICATE_EVENT_MS = 5_000L
        const val MAX_OUTBOUND_ATTEMPTS = 2
    }

    private var lastSignalAt = -1L
    private var until = 0L
    private var attemptsLeft = 0

    @Synchronized
    fun reset() {
        lastSignalAt = -1L
        until = 0L
        attemptsLeft = 0
    }

    /** Returns true only when a new externally initiated Bluetooth event arms us. */
    @Synchronized
    fun onVehicleBluetoothConnected(nowMs: Long): Boolean {
        if (lastSignalAt >= 0 && nowMs - lastSignalAt < DUPLICATE_EVENT_MS) return false
        lastSignalAt = nowMs
        until = nowMs + INVITATION_WINDOW_MS
        attemptsLeft = MAX_OUTBOUND_ATTEMPTS
        return true
    }

    /** Consume a bounded, event-backed phone-side dial attempt. */
    @Synchronized
    fun claimOutboundAttempt(nowMs: Long): Boolean {
        if (nowMs >= until || attemptsLeft <= 0) return false
        attemptsLeft--
        return true
    }

    /**
     * Explicit diagnostics fallback only. Does NOT run on normal startup,
     * disconnection, Bluetooth pairing, or a background retry timer.
     */
    @Synchronized
    fun requestOneManualProbe(nowMs: Long) {
        until = nowMs + 15_000L
        attemptsLeft = 1
    }

    /** An established session or accepted reverse connection ends this invitation. */
    @Synchronized
    fun clearPendingInvitation() {
        until = 0L
        attemptsLeft = 0
    }
}
