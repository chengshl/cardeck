package io.github.shaonialife.cardeck.airplay

/**
 * T26 controlled B233 video-only experiment.
 *
 * AirPlay resources: ID 1 = main screen; ID 2 = main audio.
 * When B233 video goes together with ordinary Bluetooth A2DP, do not claim
 * CarPlay's main audio resource. Never change legacy receivers or the
 * explicit CarPlay-audio mode.
 */
internal object AirPlayModeClaims {
    fun claimAudio(isBuickB233: Boolean, audioEnabledForSession: Boolean): Boolean =
        !isBuickB233 || audioEnabledForSession

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
