package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseAudioPortOwnershipTest {
    @Test
    fun stoppedSenderCannotRetireReplacementListener() {
        val originalPort = Any()
        val replacementPort = Any()
        assertTrue(ownsReverseAudioPort(originalPort, originalPort))
        // Same AirPlay session, but a new audio SETUP installed a new listener.
        assertFalse(ownsReverseAudioPort(replacementPort, originalPort))
        assertTrue(ownsReverseAudioPort(replacementPort, replacementPort))
    }

    @Test
    fun staleAcceptedSetupCannotActivateAfterReplacement() {
        val originalPort = Any()
        val replacementPort = Any()
        assertFalse(canActivateAcceptedAudioSetup(true, originalPort, replacementPort))
        assertFalse(canActivateAcceptedAudioSetup(true, originalPort, null))
        assertTrue(canActivateAcceptedAudioSetup(true, originalPort, originalPort))
        // A B233 profile that never advertises reverse RTCP is unaffected.
        assertTrue(canActivateAcceptedAudioSetup(true, null, replacementPort))
        // Other receivers retain their previous accepted-SETUP behavior.
        assertTrue(canActivateAcceptedAudioSetup(false, originalPort, replacementPort))
    }

    @Test
    fun missingB233RouteSkipsSetupBeforeReplacingReverseListener() {
        assertTrue(shouldSkipAudioSetupBeforePortReplacement(true, true, false))
        assertFalse(shouldSkipAudioSetupBeforePortReplacement(true, true, true))
        // No reverse RTCP is advertised: retain existing behavior.
        assertFalse(shouldSkipAudioSetupBeforePortReplacement(true, false, false))
        // Non-B233 receivers retain the existing best-effort path.
        assertFalse(shouldSkipAudioSetupBeforePortReplacement(false, true, false))
    }

    @Test
    fun absentOrUnownedListenerNeverCloses() {
        val originalPort = Any()
        assertFalse(ownsReverseAudioPort(null, originalPort))
        assertFalse(ownsReverseAudioPort(originalPort, null))
        assertFalse(ownsReverseAudioPort(null, null))
    }
}
