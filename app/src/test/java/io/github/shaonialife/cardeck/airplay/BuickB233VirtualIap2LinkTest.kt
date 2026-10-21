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
    fun waitsForCarDetectAndEchoesOnceViaControl() {
        val controlSent = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val link = VirtualIap2Link(
            log = {},
            sendEventIap = { eventSent.add(it); true },
            sendControlIap = { controlSent.add(it) },
            tunnelWriter = { null },
            alive = { false },
            isBuickB233 = true,
        )

        link.armBuickB233()
        assertTrue("B233 must not send Audi proactive DETECT", controlSent.isEmpty())

        link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(1, controlSent.size)
        assertTrue(controlSent.first().contentEquals(Iap2LinkEngine.MARKER))

        link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals("repeated markers must not reset an active B233 handshake", 1, controlSent.size)
        assertTrue("B233 must respond over CONTROL RTSP, not Events", eventSent.isEmpty())
    }
}
