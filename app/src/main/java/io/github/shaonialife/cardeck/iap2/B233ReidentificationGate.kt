package io.github.shaonialife.cardeck.iap2

/**
 * iPhone HCI 2026-10-19 11:34: after two 0x4300 availability messages,
 * B233 re-sent 0x1D01; iPhone completed ONE more identification/MFi round
 * and then received 0x4301. Only allow this on the exact observed receiver.
 */
internal class B233ReidentificationGate {
    private var used = false

    fun claim(messageId: Int, identityText: String): Boolean {
        if (used || messageId != Iap2MessageIds.IDENTIFICATION_INFORMATION) return false
        val identity = identityText.lowercase()
        if (!identity.contains("buick_2427") || !identity.contains("b233") ||
            !identity.contains("saic general motors")
        ) return false
        used = true
        return true
    }
}
