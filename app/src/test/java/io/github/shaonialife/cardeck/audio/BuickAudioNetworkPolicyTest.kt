package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickAudioNetworkPolicyTest {
    @Test
    fun signalDropIsNotAnAckCounter() {
        assertEquals(14, BuickAudioNetworkPolicy.dropDb(-32, -46))
        assertEquals(0, BuickAudioNetworkPolicy.dropDb(-46, -32))
        assertNull(BuickAudioNetworkPolicy.dropDb(null, -46))
        assertNull(BuickAudioNetworkPolicy.dropDb(-127, -46))
        assertFalse(BuickAudioNetworkPolicy.shouldWarnForRssi(14, 2))
        assertTrue(BuickAudioNetworkPolicy.shouldWarnForRssi(14, 3))
    }

    @Test
    fun timedFallbackIsNeverDefaultOrNonBuick() {
        fun should(
            enabled: Boolean = true,
            b233: Boolean = true,
            same: Boolean = true,
            running: Boolean = true,
            time: Long = 18_000L,
        ) = BuickAudioNetworkPolicy.shouldAttemptTimedFallback(enabled, b233, same, running, time)

        assertFalse(should(enabled = false))
        assertFalse(should(b233 = false))
        assertFalse(should(same = false))
        assertFalse(should(running = false))
        assertFalse(should(time = 17_999L))
        assertTrue(should())
    }
}
