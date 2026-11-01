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
    fun absentOrUnownedListenerNeverCloses() {
        val originalPort = Any()
        assertFalse(ownsReverseAudioPort(null, originalPort))
        assertFalse(ownsReverseAudioPort(originalPort, null))
        assertFalse(ownsReverseAudioPort(null, null))
    }
}
