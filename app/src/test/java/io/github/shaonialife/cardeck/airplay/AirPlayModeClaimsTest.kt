package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayModeClaimsTest {
    @Test
    fun buickVideoOnlyLeavesCarPlayAudioUnclaimed() {
        assertFalse(AirPlayModeClaims.claimAudio(isBuickB233 = true, audioEnabledForSession = false))
        val resources = AirPlayModeClaims.resources(claimAudio = false)
        assertEquals(1, resources.size)
        assertEquals(1L, resources[0]["resourceID"])
        assertEquals(1L, resources[0]["entity"])
    }

    @Test
    fun buickCarPlayAudioKeepsBothResources() {
        assertTrue(AirPlayModeClaims.claimAudio(isBuickB233 = true, audioEnabledForSession = true))
        val resources = AirPlayModeClaims.resources(claimAudio = true)
        assertEquals(listOf(1L, 2L), resources.map { it["resourceID"] })
    }

    @Test
    fun otherReceiversPreserveExistingAudioClaim() {
        assertTrue(AirPlayModeClaims.claimAudio(isBuickB233 = false, audioEnabledForSession = false))
        assertTrue(AirPlayModeClaims.claimAudio(isBuickB233 = false, audioEnabledForSession = true))
        assertEquals(2, AirPlayModeClaims.resources(claimAudio = true).size)
    }

    @Test
    fun b233UnnegotiatedSetupReleasesAudioClaimOnlyForCurrentRunningVideo() {
        fun eligible(
            isBuick: Boolean = true,
            selected: Boolean = true,
            sameClient: Boolean = true,
            cancelled: Boolean = false,
            runningVideo: Boolean = true,
            negotiated: Boolean = false,
        ): Boolean = AirPlayModeClaims.shouldReleaseUnnegotiatedAudioClaim(
            isBuickB233 = isBuick,
            audioEnabledForSession = selected,
            sameClient = sameClient,
            cancelled = cancelled,
            videoRunning = runningVideo,
            audioSetupMayHaveCreatedStream = negotiated,
        )
        assertTrue(eligible()) // All audio SETUP candidates failed; screen survives.
        assertFalse(eligible(isBuick = false)) // Other receivers are untouched.
        assertFalse(eligible(selected = false)) // Existing video-only mode is untouched.
        assertFalse(eligible(sameClient = false)) // Stale asynchronous callbacks ignored.
        assertFalse(eligible(cancelled = true)) // Cancelled/retired session ignored.
        assertFalse(eligible(runningVideo = false)) // No active screen to preserve.
        assertFalse(eligible(negotiated = true)) // Never revoke running/accepted audio.
    }

    @Test
    fun b233VideoOnlyResourcesPreserveScreenAndExcludeMainAudio() {
        val resources = AirPlayModeClaims.resources(claimAudio = false)
        assertEquals(listOf(1L), resources.map { it["resourceID"] })
        assertEquals(1L, resources.single()["permanentEntity"])
    }
}
