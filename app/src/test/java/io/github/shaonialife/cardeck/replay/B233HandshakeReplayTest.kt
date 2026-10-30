package io.github.shaonialife.cardeck.replay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U03: adapted from upstream shaonialife/cardeck@3095d64e replay methodology.
 *
 * This is a SYNTHETIC, IDENTIFIER-FREE link-event fixture, NOT a recording of
 * the user's 2023 Buick E5. It drives the production Iap2LinkEngine and locks
 * three distinct outcomes observed in #33:
 *   (1) 0x4300/seq109 never acknowledged,
 *   (2) seq109 acknowledged but 0x4301 absent,
 *   (3) seq109 acknowledged and 0x4301 reaches the control queue.
 *
 * Never treat a passing test as proof that the car sent ACK109 or switched
 * screens. Real recorded fixtures need separate privacy review and coverage.
 */
class B233HandshakeReplayTest {
    private enum class Outcome {
        ACK109_MISSING,
        ACK109_WITHOUT_START,
        ACK109_WITH_START,
    }

    private data class Fixture(
        val name: String,
        val outcome: Outcome,
    )

    // Hard-coded nonempty fixtures: CI MUST run all three and never silently skip.
    private val fixtures = listOf(
        Fixture("lost-link-ack", Outcome.ACK109_MISSING),
        Fixture("link-ack-no-start-session", Outcome.ACK109_WITHOUT_START),
        Fixture("link-ack-and-start-session", Outcome.ACK109_WITH_START),
    )

    @Test
    fun b233HandshakeReplaysAllThreeDistinctOutcomes() {
        assertEquals("Missing synthetic fixtures must fail, not pass as skipped", 3, fixtures.size)
        val executed = linkedSetOf<Outcome>()
        fixtures.forEach { fixture ->
            replay(fixture)
            assertTrue("Duplicate fixture: ${fixture.name}", executed.add(fixture.outcome))
        }
        assertEquals(Outcome.entries.toSet(), executed)
    }

    private fun replay(fixture: Fixture) {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        var now = 0L

        phone.start(now)
        car.start(now)
        repeat(12) {
            car.feed(phone.takeOutput(), ++now)
            phone.feed(car.takeOutput(), ++now)
        }
        assertEquals("phone not ready: ${fixture.name}", Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals("car not ready: ${fixture.name}", Iap2LinkEngine.State.NORMAL, car.state)
        phone.takeOutput()
        car.takeOutput()

        // All payloads are synthetic message IDs, no MAC, AP password, UUID or raw user logs.
        // Nine acknowledged CSM transmissions advance the initial seq99 to ACK108.
        val preamble = listOf(
            0x1D00, 0x1D02, 0xAA00, 0xAA02, 0xAA05,
            0x6801, 0x4E0D, 0x4E0A, 0x4E09,
        )
        preamble.forEach { messageId ->
            assertTrue("preamble enqueue failed: ${fixture.name}", phone.sendControl(Iap2Csm.encode(messageId), ++now))
            val tx = phone.takeOutput()
            assertTrue("preamble must transmit bytes", tx.isNotEmpty())
            car.feed(tx, ++now)
            val preambleEvent = car.pollEvent()
            assertTrue("preamble must reach real accessory control queue", preambleEvent is Iap2LinkEngine.Event.Control)
            assertEquals(
                "accessory must decode the exact preamble CSM",
                listOf(messageId),
                Iap2Csm.Parser().offer((preambleEvent as Iap2LinkEngine.Event.Control).bytes).map { it.messageId },
            )
            phone.feed(car.takeOutput(), ++now)
        }
        // Flush only remaining transport-level ACK residues.
        phone.takeOutput()
        car.takeOutput()

        assertTrue(phone.sendControl(Iap2Csm.encode(0x4300), ++now))
        val availability = phone.takeOutput()
        assertTrue("0x4300 frame must be emitted", availability.size >= 9)
        assertEquals("0x4300 must occupy seq109 in this fixture", 109, dataSequence(availability))
        car.feed(availability, ++now)
        val requestEvent = car.pollEvent()
        assertTrue("0x4300 must reach real accessory control queue", requestEvent is Iap2LinkEngine.Event.Control)
        assertEquals(
            "accessory must decode 0x4300 before an ACK can be considered evidence",
            listOf(0x4300),
            Iap2Csm.Parser().offer((requestEvent as Iap2LinkEngine.Event.Control).bytes).map { it.messageId },
        )
        val ack109 = car.takeOutput()
        assertEquals("ACK109 must be a complete ACK-only link frame", Iap2LinkEngine.HEADER_BYTES, ack109.size)
        assertEquals("accessory ACK must confirm seq109", 109, ack109[6].toInt() and 0xFF)

        if (fixture.outcome != Outcome.ACK109_MISSING) {
            phone.feed(ack109, ++now)
        } // Otherwise drop only this synthetic inbound ACK, as observed in #33.

        if (fixture.outcome == Outcome.ACK109_WITH_START) {
            // Transport-only proof: payload intentionally has NO real Wi-Fi credentials.
            assertTrue(car.sendControl(Iap2Csm.encode(0x4301), ++now))
            phone.feed(car.takeOutput(), ++now)
        }

        val received = mutableListOf<Int>()
        while (true) {
            when (val event = phone.pollEvent() ?: break) {
                is Iap2LinkEngine.Event.Control ->
                    received += Iap2Csm.Parser().offer(event.bytes).map { it.messageId }
                is Iap2LinkEngine.Event.Dead ->
                    error("phone link dead in ${fixture.name}: ${event.reason}")
            }
        }
        assertEquals(
            "0x4301 delivery must match the independent scenario, not ACK status",
            fixture.outcome == Outcome.ACK109_WITH_START,
            0x4301 in received,
        )

        // Advance beyond the retransmission timer. A genuine ACK cancels the
        // resend; a dropped ACK must leave 109 pending and retryable.
        phone.takeOutput()
        phone.advanceTime(now + 4_100L)
        val outbound = phone.takeOutput()
        val reSent109 = outbound.size >= 9 && dataSequence(outbound) == 109
        when (fixture.outcome) {
            Outcome.ACK109_MISSING -> assertTrue("missing ACK109 must retransmit seq109", reSent109)
            Outcome.ACK109_WITHOUT_START, Outcome.ACK109_WITH_START ->
                assertFalse("confirmed ACK109 must not retransmit 109", reSent109)
        }
        assertFalse("one replay tick must not kill the phone link", phone.isDead)
    }

    private fun dataSequence(bytes: ByteArray): Int {
        // The leading ff5a data frame uses byte 5 as its 8-bit send sequence.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0x5A, bytes[1].toInt() and 0xFF)
        assertEquals(0x40, bytes[4].toInt() and 0x40)
        return bytes[5].toInt() and 0xFF
    }
}
