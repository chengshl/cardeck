package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raw encoded AAC bytes must never be published through the first-frame log. */
class AacFirstFrameLogPrivacyTest {
    @Test fun identicalLengthsWithDifferentMediaProduceIdenticalLog() {
        val mediaA = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val mediaB = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val first = firstAacFrameSafeLog(mediaA, mediaA, false)
        val second = firstAacFrameSafeLog(mediaB, mediaB, false)
        assertEquals(first, second)
        assertEquals("AUDIO: 首帧 4B（裸帧 4B）封装=raw", first)
        assertFalse(first.contains("DE AD BE EF"))
        assertFalse(first.contains("deadbeef", ignoreCase = true))
    }

    @Test fun adtsSummaryIncludesOnlyLengthsAndFramingMode() {
        val raw = byteArrayOf(0x55, 0x66, 0x77, 0x33)
        val framed = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(),
            0x00, 0x1F, 0xFC.toByte(), 0x55, 0x66, 0x77, 0x33,
        )
        val summary = firstAacFrameSafeLog(framed, raw, true)
        assertEquals("AUDIO: 首帧 11B（裸帧 4B）封装=ADTS", summary)
        assertFalse(summary.contains("FF F1"))
        assertFalse(summary.contains("55 66"))
    }

    @Test fun emptyPayloadDoesNotCreateAHexDump() {
        val summary = firstAacFrameSafeLog(byteArrayOf(), byteArrayOf(), false)
        assertEquals("AUDIO: 首帧 0B（裸帧 0B）封装=raw", summary)
        assertTrue(summary.contains("0B"))
    }
}
