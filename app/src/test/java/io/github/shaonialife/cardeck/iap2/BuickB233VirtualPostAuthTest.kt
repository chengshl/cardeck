package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickB233VirtualPostAuthTest {
    @Test
    fun capabilityListUsesNetworkOrderU16() {
        val ids = BuickB233VirtualPostAuth.receiveIds(
            byteArrayOf(0x4e, 0x0c, 0xa1.toByte(), 0x00, 0x50, 0x01),
        )
        assertEquals(setOf(0x4E0C, 0xA100, 0x5001), ids)
    }

    @Test
    fun burstOnlyContainsAdvertisedMessages() {
        val selected = BuickB233VirtualPostAuth.burst(
            setOf(0x4E0C, 0x4E0A, 0x5001),
            atMillis = 1_700_000_000_000L,
            sessionId = "TEST-SESSION",
        )
        assertEquals(listOf(0x4E0C, 0x4E0A), selected.map { it.first })
        assertEquals("TEST-SESSION", Iap2Csm.parseParams(selected[0].second)[0].value.decodeToString())
        assertTrue(selected.all { Iap2Csm.encode(it.first, it.second).size >= Iap2Csm.HEADER_BYTES })
    }

    @Test
    fun subscriptionsAreReactiveAndUseValidIdleFrames() {
        val requests = mapOf(0x5000 to 0x5001, 0x4157 to 0x4158, 0x4154 to 0x4155, 0x4170 to 0x4171)
        for ((request, reply) in requests) {
            val actual = BuickB233VirtualPostAuth.replyToSubscription(request)
            assertEquals(reply, actual?.first)
            assertTrue((actual?.second?.size ?: 0) > 0)
        }
        assertEquals(null, BuickB233VirtualPostAuth.replyToSubscription(0xA101))
    }

    @Test
    fun missingOrMalformedCapabilityListDoesNotGenerateUnsolicitedMessages() {
        assertTrue(BuickB233VirtualPostAuth.receiveIds(null).isEmpty())
        assertTrue(BuickB233VirtualPostAuth.receiveIds(byteArrayOf(0x4e)).isEmpty())
        assertFalse(BuickB233VirtualPostAuth.burst(setOf(0x5001)).isNotEmpty())
    }
}
