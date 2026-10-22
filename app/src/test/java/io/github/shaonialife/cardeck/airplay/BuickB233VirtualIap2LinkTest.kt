package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B233 must use the T48 receiver-initiated handshake, not Audi's proactive DETECT. */
class BuickB233VirtualIap2LinkTest {
    @Test
    fun buickOptionalStateUpdatesDoNotKillCarPlayOnMissingAck() {
        // On the 2026-10-21 audio run 0x4171 seq113 was sent again
        // 4 times and then closed CarPlay after ~20 seconds.
        assertFalse(VirtualIap2Link.shouldAwaitCsmAck(true, 0x4171))
        assertFalse(VirtualIap2Link.shouldAwaitCsmAck(true, 0x5001))
        // Audi and all other receivers keep their original reliable CSM.
        assertTrue(VirtualIap2Link.shouldAwaitCsmAck(false, 0x4171))
        assertTrue(VirtualIap2Link.shouldAwaitCsmAck(false, 0x5001))
        // Do not disable auth, session or other subscription ACKs.
        assertTrue(VirtualIap2Link.shouldAwaitCsmAck(true, 0xAA05))
        assertTrue(VirtualIap2Link.shouldAwaitCsmAck(true, 0x4155))
        assertTrue(VirtualIap2Link.shouldAwaitCsmAck(true, 0x4158))
        assertTrue(VirtualIap2Link.shouldAwaitCsmAck(true, 0x4E0C))
        assertTrue(VirtualIap2Link.shouldAwaitCsmAck(true, 0x4300))
    }

    @Test
    fun stalledB233DetectTriggersBoundedSynOnlyBeforeHandshake() {
        val sent = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val link = VirtualIap2Link(
            log = {},
            sendEventIap = { eventSent.add(it); true },
            sendControlIap = { sent.add(it) },
            tunnelWriter = { null },
            alive = { false },
            isBuickB233 = true,
        )
        link.armBuickB233()
        assertTrue(sent.isEmpty())
        link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(1, sent.size)
        assertTrue(sent[0].contentEquals(Iap2LinkEngine.MARKER))
        link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(1, sent.size)
        link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(2, sent.size)
        val probe = sent[1]
        assertTrue(probe.copyOfRange(0, 6).contentEquals(Iap2LinkEngine.MARKER))
        assertTrue(probe.size > 14 && probe[6] == 0xFF.toByte() &&
            probe[7] == 0x5A.toByte() && (probe[10].toInt() and 0x80) != 0)
        repeat(30) { link.onCarBytes(Iap2LinkEngine.MARKER) }
        assertEquals("no rapid retransmit storm", 2, sent.size)
        assertTrue(eventSent.isEmpty())
    }

    @Test
    fun establishedB233VirtualLinkIgnoresStrayDetect() {
        val sent = ArrayList<ByteArray>()
        val link = VirtualIap2Link(
            log = {}, sendEventIap = { true },
            sendControlIap = { sent.add(it) }, tunnelWriter = { null },
            alive = { false }, isBuickB233 = true,
        )
        link.armBuickB233()
        link.onCarBytes(Iap2LinkEngine.MARKER)
        val peer = Iap2LinkEngine()
        peer.start(0L)
        link.onCarBytes(peer.takeOutput())
        val count = sent.size
        repeat(12) { link.onCarBytes(Iap2LinkEngine.MARKER) }
        assertEquals("never reset NORMAL negotiated session", count, sent.size)
    }
}
