package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRetransmitRequestTest {
    private fun packet(requestSeq: Int, firstSeq: Int, count: Int): ByteArray =
        byteArrayOf(
            0x80.toByte(), 0xD5.toByte(),
            (requestSeq ushr 8).toByte(), requestSeq.toByte(),
            (firstSeq ushr 8).toByte(), firstSeq.toByte(),
            (count ushr 8).toByte(), count.toByte(),
        )

    private fun parsed(requestSeq: Int, firstSeq: Int, count: Int): AudioRetransmitRequest.Fields {
        val result = AudioRetransmitRequest.inspect(packet(requestSeq, firstSeq, count), 8)
        assertTrue(result is AudioRetransmitRequest.Parse.Valid)
        return (result as AudioRetransmitRequest.Parse.Valid).fields
    }

    @Test
    fun parsesNormalEightByteRequestWithoutTreatingRequestSeqAsRtcpLength() {
        val f = parsed(1, 901, 2)
        assertEquals(1, f.requestSeq)
        assertEquals(901, f.firstMissingSeq)
        assertEquals(2, f.count)
        assertEquals(902, f.lastMissingSeq)
        assertFalse(f.wrapsRtpSequence)
        assertEquals("within_sent", AudioRetransmitRequest.coverage(f, 942L))
    }

    @Test
    fun rejectsShortTruncatedOrOverlongPackets() {
        val p = packet(1, 20, 3)
        assertEquals("invalid_length", (AudioRetransmitRequest.inspect(p, 7) as AudioRetransmitRequest.Parse.Invalid).reason)
        assertEquals("invalid_length", (AudioRetransmitRequest.inspect(p, 9) as AudioRetransmitRequest.Parse.Invalid).reason)
        assertEquals("invalid_length", (AudioRetransmitRequest.inspect(byteArrayOf(0x80.toByte(), 0xD5.toByte()), 2) as AudioRetransmitRequest.Parse.Invalid).reason)
    }

    @Test
    fun rejectsBadTypeVersionAndInvalidCounts() {
        val bad = packet(1, 20, 1)
        bad[1] = 0xD3.toByte()
        assertEquals("not_d5_v2", (AudioRetransmitRequest.inspect(bad, 8) as AudioRetransmitRequest.Parse.Invalid).reason)
        bad[1] = 0xD5.toByte()
        bad[0] = 0x40.toByte()
        assertEquals("not_d5_v2", (AudioRetransmitRequest.inspect(bad, 8) as AudioRetransmitRequest.Parse.Invalid).reason)
        assertEquals("zero_count", (AudioRetransmitRequest.inspect(packet(1, 20, 0), 8) as AudioRetransmitRequest.Parse.Invalid).reason)
        assertEquals("count_exceeds_diagnostic_cap", (AudioRetransmitRequest.inspect(packet(1, 20, 257), 8) as AudioRetransmitRequest.Parse.Invalid).reason)
        assertTrue(AudioRetransmitRequest.inspect(packet(1, 20, 256), 8) is AudioRetransmitRequest.Parse.Valid)
    }

    @Test
    fun handlesSequenceWrappingAndChecksEntireRequestedRange() {
        val f = parsed(65535, 65534, 4)
        assertEquals(1, f.lastMissingSeq)
        assertTrue(f.wrapsRtpSequence)
        // Historical sequence epoch is ambiguous after a full cycle: do NOT claim coverage.
        assertEquals("ambiguous_after_wrap", AudioRetransmitRequest.coverage(f, 65538L))
        assertEquals("outside_sent", AudioRetransmitRequest.coverage(f, 942L))
        val early = parsed(0, 0, 2)
        assertEquals("outside_sent", AudioRetransmitRequest.coverage(early, 1L))
        assertEquals("within_sent", AudioRetransmitRequest.coverage(early, 2L))
    }

    @Test
    fun repeatedRequestSequenceCanReferToDifferentMissingRanges() {
        val a = parsed(1, 100, 1)
        val b = parsed(1, 101, 2)
        assertEquals(a.requestSeq, b.requestSeq)
        assertFalse(a == b)
        assertEquals("within_sent", AudioRetransmitRequest.coverage(a, 150L))
        assertEquals("within_sent", AudioRetransmitRequest.coverage(b, 150L))
    }

    @Test
    fun unknownSenderAndWrapCannotBePresentedAsAlreadySent() {
        val f = parsed(12, 10, 1)
        assertEquals("sender_unavailable", AudioRetransmitRequest.coverage(f, null))
        assertEquals("not_sent_yet", AudioRetransmitRequest.coverage(f, 0L))
        assertEquals("ambiguous_after_wrap", AudioRetransmitRequest.coverage(f, 65537L))
    }
}
