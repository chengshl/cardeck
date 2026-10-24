package io.github.shaonialife.cardeck.bt

import java.util.Locale

/**
 * Buick-Test T66: the vehicle chooses WHEN and WHICH receiver connects.
 *
 * Passive RFCOMM incoming sessions need no permission from this class.
 * For receivers that instead need a phone-originating accessory RFCOMM link,
 * a real ACL_CONNECTED event grants a short, bounded compatibility window.
 *
 * HFP/A2DP profile CONNECTED notifications are NOT CarPlay invitations.
 * One physical ACL connection can arm us only once, even if many profile
 * updates follow. A new grant requires ACL_DISCONNECTED then ACL_CONNECTED,
 * or an explicit user-initiated one-shot diagnostic request.
 *
 * Addresses are stored only in volatile memory to bind the invitation to
 * the exact receiver; never logged or persisted.
 */
internal class B233CarLedConnectionPolicy {
    companion object {
        const val INVITATION_WINDOW_MS = 45_000L
        const val INBOUND_PRIORITY_MS = 1_500L
        const val MAX_OUTBOUND_ATTEMPTS = 2
    }

    private var aclConnectedAddress: String? = null
    private var invitedAddress: String? = null
    private var notBeforeMs = 0L
    private var untilMs = 0L
    private var attemptsLeft = 0

    private fun key(address: String): String = address.uppercase(Locale.ROOT)

    @Synchronized
    fun reset() {
        aclConnectedAddress = null
        clearPendingInvitation()
    }

    /** Only a fresh, vehicle-specific ACL connection arms the fallback. */
    @Synchronized
    fun onVehicleAclConnected(address: String, nowMs: Long): Boolean {
        if (aclConnectedAddress != null) return false
        aclConnectedAddress = key(address)
        invitedAddress = aclConnectedAddress
        notBeforeMs = nowMs + INBOUND_PRIORITY_MS
        untilMs = nowMs + INVITATION_WINDOW_MS
        attemptsLeft = MAX_OUTBOUND_ATTEMPTS
        return true
    }

    /** A full physical disconnect resets the edge detector for the NEXT tap. */
    @Synchronized
    fun onVehicleAclDisconnected(address: String): Boolean {
        if (aclConnectedAddress != key(address)) return false
        aclConnectedAddress = null
        clearPendingInvitation()
        return true
    }

    /**
     * Consume only a matching receiver's invitation; allow the car's reverse
     * iAP2 socket a brief head start before trying accessory-side RFCOMM.
     */
    @Synchronized
    fun hasEligibleInvitation(address: String, nowMs: Long): Boolean {
        if (invitedAddress != null && invitedAddress != key(address)) return false
        return attemptsLeft > 0 && nowMs >= notBeforeMs && nowMs < untilMs
    }

    @Synchronized
    fun claimOutboundAttempt(address: String, nowMs: Long): Boolean {
        if (!hasEligibleInvitation(address, nowMs)) return false
        attemptsLeft--
        return true
    }

    /**
     * T67: the user explicitly pressed Start while the known B233 was already
     * physically connected (HFP/A2DP confirmed on that receiver). No new ACL
     * broadcast follows registerReceiver(), so T66 would wait indefinitely.
     *
     * Treat the existing ACL as observed WITHOUT fabricating a new edge. Give
     * reverse iAP2 1.5s priority, then ONE accessory RFCOMM compatibility
     * attempt in a 15s window. Never re-arm on later polling/profile events.
     * A real ACL disconnect clears this state as usual.
     */
    @Synchronized
    fun armAlreadyConnectedStartupProbe(address: String, nowMs: Long): Boolean {
        if (aclConnectedAddress != null || attemptsLeft > 0) return false
        aclConnectedAddress = key(address)
        invitedAddress = aclConnectedAddress
        notBeforeMs = nowMs + INBOUND_PRIORITY_MS
        untilMs = nowMs + 15_000L
        attemptsLeft = 1
        return true
    }

    /** Explicit diagnostics only, never an automatic session recovery. */
    @Synchronized
    fun requestOneManualProbe(nowMs: Long, selectedAddress: String?) {
        invitedAddress = selectedAddress?.let(::key)
        notBeforeMs = nowMs
        untilMs = nowMs + 15_000L
        attemptsLeft = 1
    }

    /** Does not rearm ACL: this prevents late HFP/A2DP updates from retrying. */
    @Synchronized
    fun clearPendingInvitation() {
        invitedAddress = null
        notBeforeMs = 0L
        untilMs = 0L
        attemptsLeft = 0
    }
}
