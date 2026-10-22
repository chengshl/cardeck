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
}
