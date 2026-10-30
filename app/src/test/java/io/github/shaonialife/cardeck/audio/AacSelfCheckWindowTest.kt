package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AacSelfCheckWindowTest {
    @Test
    fun silentPcmDoesNotArmAndFirstNonzeroArms() {
        val window = AacSelfCheckWindow(maxFrames = 3)
        assertNull(window.accept(false, listOf(byteArrayOf(1)), 10L))
        assertFalse(window.armed)
        assertNull(window.accept(true, listOf(byteArrayOf(2)), 11L))
        assertTrue(window.armed)
        assertNull(window.accept(false, listOf(byteArrayOf(3)), 12L))
        val frames = window.accept(false, listOf(byteArrayOf(4)), 13L)!!
        assertEquals(3, frames.size)
        assertArrayEquals(byteArrayOf(2), frames[0])
        assertArrayEquals(byteArrayOf(4), frames[2])
        assertTrue(window.completed)
        assertNull(window.accept(true, listOf(byteArrayOf(5)), 14L))
    }

    @Test
    fun stopsAtByteBudgetAndCopiesInputs() {
        val window = AacSelfCheckWindow(maxFrames = 10, maxBytes = 5)
        val input = byteArrayOf(7, 8, 9)
        assertNull(window.accept(true, listOf(input), 10L))
        input[0] = 0
        val frames = window.accept(false, listOf(byteArrayOf(1, 2, 3)), 11L)!!
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(7, 8, 9), frames.single())
    }

    @Test
    fun timesOutWithNoFramesRatherThanClaimingAacIsValid() {
        val window = AacSelfCheckWindow(maxDurationNanos = 10L)
        assertNull(window.accept(true, emptyList(), 100L))
        assertEquals(0, window.accept(false, emptyList(), 111L)!!.size)
        assertTrue(window.completed)
    }
}
