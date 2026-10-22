package io.github.shaonialife.cardeck.iap2

/**
 * iPhone HCI 2026-10-19 11:34: after two 0x4300 availability messages,
 * B233 re-sent 0x1D01; iPhone completed ONE more identification/MFi round
 * and then received 0x4301. Require B233/GM identity and Buick's four-digit Bluetooth-name format.
 */
internal class B233ReidentificationGate {
    private var used = false

    fun claim(messageId: Int, identityText: String): Boolean {
        if (used || messageId != Iap2MessageIds.IDENTIFICATION_INFORMATION) return false
        val identity = identityText.lowercase()
        if (!Regex("(?i)BUICK_[0-9]{4}").containsMatchIn(identity) || !identity.contains("b233") ||
            !identity.contains("saic general motors")
        ) return false
        used = true
        return true
    }
}
