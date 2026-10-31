package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayModeClaimsTest {
    @Test fun b233VideoOnlyKeepsScreenAndDoesNotClaimAudio() {
        assertFalse(AirPlayModeClaims.claimAudio("B233", false))
        assertFalse(AirPlayModeClaims.claimAudio(" b233 ", false))
        assertEquals(listOf(1L), AirPlayModeClaims.resources(false).map { it["resourceID"] })
    }

    @Test fun b233AudioEnabledClaimsBothResources() {
        assertTrue(AirPlayModeClaims.claimAudio("B233", true))
        assertEquals(listOf(1L, 2L), AirPlayModeClaims.resources(true).map { it["resourceID"] })
    }

    @Test fun unknownAndOtherModelsRetainLegacyClaims() {
        listOf(null, "", "B233x", "BUICK", "MIB3", "B232").forEach {
            assertTrue("Legacy claim changed for model=$it", AirPlayModeClaims.claimAudio(it, false))
        }
    }

    @Test fun resourcePayloadPreservesOriginalEntitiesAndOrder() {
        val screen = AirPlayModeClaims.resources(false).single()
        assertEquals(mapOf("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L), screen)
        val both = AirPlayModeClaims.resources(true)
        assertEquals(screen, both[0])
        assertEquals(mapOf("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L), both[1])
    }
}
