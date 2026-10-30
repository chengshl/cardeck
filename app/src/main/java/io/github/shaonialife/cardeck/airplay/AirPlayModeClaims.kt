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
    /**
     * T85 single-variable experiment: defer B233's mainAudio declaration until
     * audio SETUP is about to be attempted. The receiver's mainScreen ownership
     * remains declared from the start; non-B233 receivers preserve T84 behavior.
     *
     * Default true keeps legacy callers/tests compatible; B233 runtime call
     * sites must explicitly provide the per-client preparation flag.
     */
    fun claimAudio(
        isBuickB233: Boolean,
        audioEnabledForSession: Boolean,
        b233AudioClaimPrepared: Boolean = true,
    ): Boolean = !isBuickB233 || (audioEnabledForSession && b233AudioClaimPrepared)


    /**
     * B233 only: no audio SETUP may have created a stream after all candidates were tried.
     * The screen can remain live without claiming CarPlay main audio. A user intent to
     * play audio is NOT proof that an audio stream exists. A late permission grant is
     * NOT this terminal failure; it must retain its separate retry path.
     */
    fun shouldReleaseUnnegotiatedAudioClaim(
        isBuickB233: Boolean,
        audioEnabledForSession: Boolean,
        sameClient: Boolean,
        cancelled: Boolean,
        videoRunning: Boolean,
        audioSetupMayHaveCreatedStream: Boolean,
    ): Boolean =
        isBuickB233 && audioEnabledForSession && sameClient && !cancelled &&
            videoRunning && !audioSetupMayHaveCreatedStream

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
