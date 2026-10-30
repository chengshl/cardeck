package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioRtpCadenceWindowTest {
    @Test
    fun reportsBurstGapAndSlowSendEvenWhenPacketCountLooksHealthy() {
        val w = AudioRtpCadenceWindow()
        assertNull(w.successfulSend(0L, 100_000L, 352L))
        assertNull(w.successfulSend(7_000_000L, 7_500_000L, 352L))
        val snapshot = checkNotNull(w.successfulSend(1_008_000_000L, 1_009_000_000L, 352L))
        assertEquals(1008L, snapshot.elapsedMs)
        assertEquals(3, snapshot.packets)
        assertEquals(1056L, snapshot.sampleFrames)
        assertEquals(1001L, snapshot.maxGapMs)
        assertEquals(1000L, snapshot.maxSendUs)
    }

    @Test
    fun resetsWindowAndKeepsCrossBoundaryGapForNextSuccessfulSend() {
        val w = AudioRtpCadenceWindow(reportEveryNanos = 1_000_000_000L)
        w.successfulSend(0L, 100_000L, 1024L)
        checkNotNull(w.successfulSend(1_000_000_000L, 1_000_100_000L, 1024L))
        assertNull(w.successfulSend(1_007_100_000L, 1_007_400_000L, 1024L))
        val next = checkNotNull(w.successfulSend(2_009_000_000L, 2_009_100_000L, 1024L))
        assertEquals(2, next.packets)
        assertEquals(2048L, next.sampleFrames)
        assertEquals(1001L, next.maxGapMs)
        assertEquals(300L, next.maxSendUs)
    }

    @Test
    fun validatesMonotonicSendDurationWithoutReadingAnyMedia() {
        val w = AudioRtpCadenceWindow()
        assertThrows(IllegalArgumentException::class.java) {
            w.successfulSend(3_000_000L, 2_000_000L, 352L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            w.successfulSend(0L, 1L, 0L)
        }
    }
}
