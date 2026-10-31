package io.github.shaonialife.cardeck.airplay

/**
 * #24 BT-B isolated B233 resource claim policy.
 * A model hint is not vehicle authentication; use only the exact SETUP receiver
 * model, and preserve legacy screen+audio claims for unknown/non-B233 receivers.
 */
internal object AirPlayModeClaims {
    fun claimAudio(receiverModel: String?, audioEnabledForSession: Boolean): Boolean =
        !receiverModel?.trim().equals("B233", ignoreCase = true) || audioEnabledForSession

    fun resources(claimAudio: Boolean): List<Map<String, Long>> {
        val screen = linkedMapOf(
            "resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L,
        )
        val audio = linkedMapOf(
            "resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L,
        )
        return if (claimAudio) listOf(screen, audio) else listOf(screen)
    }
}
