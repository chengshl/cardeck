package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两个链路引擎通过直接交换输出字节完成握手与数据往返（无线程、无 I/O）。 */
class Iap2LinkEngineTest {
    @Test
    fun handshakeReachesNormalOnBothSides() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()

        phone.start(0)
        accessory.start(0)

        exchange(phone, accessory)
        exchange(phone, accessory)
        exchange(phone, accessory)

        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(Iap2LinkEngine.State.NORMAL, accessory.state)
        assertEquals(4, accessory.peerSynchronization?.maxOutgoing)
        assertTrue(phone.peerSynchronization?.sessions?.any { it.id == Iap2LinkEngine.SESSION_CONTROL } == true)
    }

    @Test
    fun controlPayloadRoundTripBothDirections() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val phoneToAccessory = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(phoneToAccessory, 1))
        exchange(phone, accessory)
        val receivedAtAccessory = pollControls(accessory)
        assertEquals(1, receivedAtAccessory.size)
        assertArrayEquals(phoneToAccessory, receivedAtAccessory[0])

        val accessoryToPhone = Iap2Csm.encode(0x1D01, Iap2Csm.string(0, "CarPlay HU"))
        assertTrue(accessory.sendControl(accessoryToPhone, 2))
        exchange(phone, accessory)
        val receivedAtPhone = pollControls(phone)
        assertEquals(1, receivedAtPhone.size)
        assertArrayEquals(accessoryToPhone, receivedAtPhone[0])
    }

    @Test
    fun duplicateFrameIsReAcknowledgedNotRedelivered() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val payload = Iap2Csm.encode(0x1D02)
        assertTrue(phone.sendControl(payload, 1))
        // 截获 phone 输出的数据帧，重复喂给 accessory 两次
        val dataFrame = phone.takeOutput()
        accessory.feed(dataFrame, 2)
        accessory.feed(dataFrame, 3)

        assertEquals(1, pollControls(accessory).size)
    }

    @Test
    fun windowBacklogFlushesAfterAcknowledgement() {
        val phone = Iap2LinkEngine(maxOutgoing = 2)
        val accessory = Iap2LinkEngine(maxOutgoing = 8)
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val frames = (0 until 5).map { Iap2Csm.encode(0x5000 + it) }
        frames.forEach { assertTrue(phone.sendControl(it, 10)) }
        // 窗口 2：只有前 2 帧立即发出，其余积压；ACK 后由 handleAcknowledgement 冲刷
        var now = 11L
        while (pollControls(accessory).size < 5) {
            exchange(phone, accessory)
            now += 1
            phone.advanceTime(now)
            accessory.advanceTime(now)
            assertTrue(!phone.isDead && !accessory.isDead)
        }
    }

    @Test
    fun buickOneShotSynProbeNeverResendsWithoutPeerResponse() {
        val phone = Iap2LinkEngine()
        assertFalse(phone.offerSynchronizationOnce())
        phone.enterNegotiating()
        assertTrue(phone.takeOutput().isEmpty())

        assertTrue(phone.offerSynchronizationOnce())
        val syn = phone.takeOutput()
        assertTrue(syn.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(0xFF, syn[0].toInt() and 0xFF)
        assertEquals(0x5A, syn[1].toInt() and 0xFF)
        assertEquals(0x80, syn[4].toInt() and 0xFF)
        assertFalse(phone.offerSynchronizationOnce())
        phone.advanceTime(10_000L)
        assertTrue(phone.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
    }

    @Test
    fun buickIdleAcknowledgementIsNormalOnlyAndDoesNotConsumeDataSequence() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        assertFalse(phone.offerIdleAcknowledgement())
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(Iap2LinkEngine.State.NORMAL, car.state)
        // A handshake may have one final ACK buffered after NORMAL.
        // Clear only handshake residue before testing the 9-byte idle packet.
        phone.takeOutput()
        car.takeOutput()

        assertTrue(phone.offerIdleAcknowledgement())
        val first = phone.takeOutput()
        assertEquals(Iap2LinkEngine.HEADER_BYTES, first.size)
        assertEquals(0x40, first[4].toInt() and 0xFF)
        assertEquals(0, first[7].toInt() and 0xFF)

        assertTrue(phone.offerIdleAcknowledgement())
        val second = phone.takeOutput()
        assertEquals(Iap2LinkEngine.HEADER_BYTES, second.size)
        // An idle ACK cannot advance the outgoing CSM data sequence.
        assertEquals(first[5], second[5])
        car.feed(first, tick())
        car.feed(second, tick())
        assertEquals(Iap2LinkEngine.State.NORMAL, car.state)
        assertTrue(pollControls(car).isEmpty())

        val data = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(data, tick()))
        car.feed(phone.takeOutput(), tick())
        assertEquals(1, pollControls(car).size)
        assertEquals(Iap2LinkEngine.State.NORMAL, car.state)
    }

    @Test
    fun staleAckCannotConfirmPending109OrSuppressRetransmission() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)

        // Initial data sequence is 100. Confirm 100..108, then leave 109 pending.
        repeat(9) { index ->
            assertTrue(phone.sendControl(Iap2Csm.encode(0x5100 + index), tick()))
            exchange(phone, car)
            assertEquals(1, pollControls(car).size)
        }
        val diagnostics = mutableListOf<String>()
        phone.onLinkAckDiagnostic = { diagnostics += it }
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4300), tick()))
        val firstTransmission = phone.takeOutput()
        assertTrue(firstTransmission.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(109, firstTransmission[5].toInt() and 0xFF)

        // ACK 110 is beyond the newest transmitted seq 109: never treat it
        // as cumulative proof that seq 109 was received.
        phone.feed(ackOnly(110), tick())
        assertTrue(diagnostics.any { it.contains("class=future-unsent") && it.contains("ignored=true") })

        // Delayed ACK 90 is older than confirmed 108, not proof of receipt of 109.
        phone.feed(ackOnly(90), tick())
        assertTrue(diagnostics.any { it.contains("class=stale-or-backward") && it.contains("ignored=true") })
        phone.advanceTime(10_000L)
        val retry = phone.takeOutput()
        assertTrue("seq 109 must remain eligible for retry", retry.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(109, retry[5].toInt() and 0xFF)
        assertFalse(phone.isDead)

        // A legitimate ACK arriving after invalid ACKs must still release the
        // outstanding frame. Rejecting bad ACKs must not deadlock normal progress.
        val progress = mutableListOf<Pair<String, String>>()
        phone.onAcknowledgementProgress = { _, pendingBefore, pendingAfter ->
            progress += pendingBefore to pendingAfter
        }
        phone.feed(ackOnly(109), tick())
        assertTrue("valid ACK109 must confirm pending 109", progress.any { it.first == "109" && it.second.isEmpty() })
        phone.advanceTime(20_000L)
        assertTrue("confirmed 109 must not be retransmitted", phone.takeOutput().isEmpty())
        assertFalse(phone.isDead)
    }

    @Test
    fun futureAckAcrossSequenceWrapCannotSuppressRetry() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)

        // 100..255 acknowledged, then 0 remains outstanding across wrap.
        repeat(156) { index ->
            assertTrue(phone.sendControl(Iap2Csm.encode(0x5200 + index), tick()))
            exchange(phone, car)
            assertEquals(1, pollControls(car).size)
        }
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4300), tick()))
        val first = phone.takeOutput()
        assertTrue(first.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(0, first[5].toInt() and 0xFF)

        val diagnostics = mutableListOf<String>()
        phone.onLinkAckDiagnostic = { diagnostics += it }
        phone.feed(ackOnly(1), tick())
        assertTrue(diagnostics.any { it.contains("class=future-unsent") && it.contains("ignored=true") })
        phone.advanceTime(10_000L)
        val retry = phone.takeOutput()
        assertTrue("wrapped seq 0 must remain eligible for retry", retry.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(0, retry[5].toInt() and 0xFF)
        assertFalse(phone.isDead)

        // After the rejected ACK1, the real cumulative ACK0 must be accepted
        // across 255 -> 0 and stop further retransmissions.
        val progress = mutableListOf<Pair<String, String>>()
        phone.onAcknowledgementProgress = { _, pendingBefore, pendingAfter ->
            progress += pendingBefore to pendingAfter
        }
        phone.feed(ackOnly(0), tick())
        assertTrue("valid ACK0 must confirm wrapped pending 0", progress.any { it.first == "0" && it.second.isEmpty() })
        phone.advanceTime(20_000L)
        assertTrue("confirmed seq0 must not be retransmitted", phone.takeOutput().isEmpty())
        assertFalse(phone.isDead)
    }

    @Test
    fun invalidAckMustNotDrainQueuedControlFrame() {
        val phone = Iap2LinkEngine(maxOutgoing = 2)
        val car = Iap2LinkEngine(maxOutgoing = 2)
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        phone.takeOutput() // discard any final handshake ACK
        car.takeOutput()

        // With window=2, seq100 and seq101 are sent; seq102 must wait for a real ACK.
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4300), tick()))
        val first = phone.takeOutput()
        assertTrue(first.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(100, first[5].toInt() and 0xFF)
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4301), tick()))
        val second = phone.takeOutput()
        assertTrue(second.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(101, second[5].toInt() and 0xFF)
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4302), tick()))
        assertTrue("seq102 must remain queued while window is full", phone.takeOutput().isEmpty())

        val diagnostics = mutableListOf<String>()
        phone.onLinkAckDiagnostic = { diagnostics += it }
        phone.feed(ackOnly(103), tick()) // ACK for a packet not yet sent
        phone.feed(ackOnly(90), tick())  // delayed ACK older than the current watermark
        assertTrue(diagnostics.any { it.contains("class=future-unsent") && it.contains("ignored=true") })
        assertTrue(diagnostics.any { it.contains("class=stale-or-backward") && it.contains("ignored=true") })
        assertTrue("invalid ACKs must not flush seq102", phone.takeOutput().isEmpty())

        // The outstanding packet still retries. Only a valid ACK100 can open a slot.
        phone.advanceTime(10_000L)
        val retry = phone.takeOutput()
        assertTrue(retry.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(100, retry[5].toInt() and 0xFF)
        phone.feed(ackOnly(100), tick())
        val queued = phone.takeOutput()
        assertTrue("valid ACK must release the queued frame", queued.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(102, queued[5].toInt() and 0xFF)
        assertFalse(phone.isDead)
    }

    @Test
    fun fragmentedAck109AfterRetransmitMustConfirmExactlyOnce() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)

        // Advance to the observed #33 boundary: ACK108 received, seq109 pending.
        repeat(9) { index ->
            assertTrue(phone.sendControl(Iap2Csm.encode(0x5300 + index), tick()))
            exchange(phone, car)
            assertEquals(1, pollControls(car).size)
        }
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4300), tick()))
        val sent = phone.takeOutput()
        assertTrue(sent.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(109, sent[5].toInt() and 0xFF)

        val progress = mutableListOf<Pair<String, String>>()
        phone.onAcknowledgementProgress = { _, before, after -> progress += before to after }
        val ack = ackOnly(109)

        // RFCOMM is a byte stream: a valid 9-byte ACK may span separate reads.
        // A partial header must never confirm seq109 or cancel its retry timer.
        phone.feed(ack.copyOfRange(0, 4), tick())
        assertTrue("partial ACK must not advance acknowledgement", progress.isEmpty())
        phone.advanceTime(10_000L)
        val retry = phone.takeOutput()
        assertTrue("pending seq109 must retry while ACK is incomplete", retry.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals(109, retry[5].toInt() and 0xFF)

        // Completing the same frame must confirm 109, without another retry.
        phone.feed(ack.copyOfRange(4, ack.size), tick())
        assertTrue("completed ACK109 must release seq109", progress.any { it.first == "109" && it.second.isEmpty() })
        phone.feed(ackOnly(109), tick()) // duplicate cumulative ACK
        phone.advanceTime(20_000L)
        assertTrue("duplicate ACK must not restart retransmission", phone.takeOutput().isEmpty())
        assertFalse(phone.isDead)
    }

    @Test
    fun ack109AndResponse4301AcrossRfcommReadsDoNotBlockControlDelivery() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)
        phone.takeOutput() // discard final negotiation residue
        car.takeOutput()

        // ACK108 is confirmed, then #33 0x4300 leaves seq109 outstanding.
        repeat(9) { index ->
            assertTrue(phone.sendControl(Iap2Csm.encode(0x5400 + index), tick()))
            exchange(phone, car)
            assertEquals(1, pollControls(car).size)
        }
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4300), tick()))
        val request = phone.takeOutput()
        assertEquals(109, request[5].toInt() and 0xFF)

        // The peer receives 0x4300 and emits ACK109 immediately followed by
        // the separate 0x4301 control response in the same RFCOMM byte stream.
        car.feed(request, tick())
        assertArrayEquals(Iap2Csm.encode(0x4300), pollControls(car).single())
        val ack = car.takeOutput()
        assertEquals(Iap2LinkEngine.HEADER_BYTES, ack.size)
        assertEquals(109, ack[6].toInt() and 0xFF)
        val response = Iap2Csm.encode(0x4301)
        assertTrue(car.sendControl(response, tick()))
        val responsePacket = car.takeOutput()
        assertTrue(responsePacket.size > Iap2LinkEngine.HEADER_BYTES)

        val progress = mutableListOf<Pair<String, String>>()
        phone.onAcknowledgementProgress = { _, before, after -> progress += before to after }
        val stream = ack + responsePacket
        // One RFCOMM read ends inside the next link frame; another completes it.
        val split = ack.size + 5
        phone.feed(stream.copyOfRange(0, split), tick())
        assertTrue("ACK109 must clear pending 109", progress.any { it.first == "109" && it.second.isEmpty() })
        assertTrue("partial 0x4301 frame must not be delivered", pollControls(phone).isEmpty())
        phone.feed(stream.copyOfRange(split, stream.size), tick())
        assertArrayEquals("0x4301 must survive ACK+data coalescing", response, pollControls(phone).single())
        phone.takeOutput() // ignore the normal ACK of the incoming 0x4301
        phone.advanceTime(20_000L)
        assertTrue("confirmed seq109 must not retry after 0x4301", phone.takeOutput().isEmpty())
        assertFalse(phone.isDead)
    }

    @Test
    fun piggybackedAck109On4301WithoutStandaloneAckConfirmsAndDelivers() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)
        phone.takeOutput() // discard final handshake residue
        car.takeOutput()

        // Reproduce the #33 boundary: 100..108 confirmed, 0x4300/seq109 pending.
        repeat(9) { index ->
            assertTrue(phone.sendControl(Iap2Csm.encode(0x5500 + index), tick()))
            exchange(phone, car)
            assertEquals(1, pollControls(car).size)
        }
        assertTrue(phone.sendControl(Iap2Csm.encode(0x4300), tick()))
        val request = phone.takeOutput()
        assertEquals(109, request[5].toInt() and 0xFF)
        car.feed(request, tick())
        assertArrayEquals(Iap2Csm.encode(0x4300), pollControls(car).single())

        // A data frame also carries the cumulative ACK in its header. Ignore
        // the separate ACK-only output and deliver only the 0x4301 response.
        val standaloneAck = car.takeOutput()
        assertEquals(Iap2LinkEngine.HEADER_BYTES, standaloneAck.size)
        assertEquals(109, standaloneAck[6].toInt() and 0xFF)
        val response = Iap2Csm.encode(0x4301)
        assertTrue(car.sendControl(response, tick()))
        val responsePacket = car.takeOutput()
        assertTrue(responsePacket.size > Iap2LinkEngine.HEADER_BYTES)
        assertEquals("0x4301 must carry piggybacked ACK109", 109, responsePacket[6].toInt() and 0xFF)

        val progress = mutableListOf<Pair<String, String>>()
        phone.onAcknowledgementProgress = { _, before, after -> progress += before to after }
        phone.feed(responsePacket, tick())
        assertTrue("piggybacked ACK109 must confirm pending seq109",
            progress.any { it.first == "109" && it.second.isEmpty() })
        assertArrayEquals("0x4301 must be delivered without standalone ACK",
            response, pollControls(phone).single())
        phone.takeOutput() // discard the expected ACK for the received response
        phone.advanceTime(20_000L)
        assertTrue("confirmed seq109 must not retry after piggybacked ACK",
            phone.takeOutput().isEmpty())
        assertFalse(phone.isDead)
    }

    private fun ackOnly(ack: Int): ByteArray {
        val frame = ByteArray(Iap2LinkEngine.HEADER_BYTES)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[3] = Iap2LinkEngine.HEADER_BYTES.toByte()
        frame[4] = 0x40
        frame[5] = 99.toByte()
        frame[6] = ack.toByte()
        val checksum = (0 until Iap2LinkEngine.HEADER_BYTES - 1).sumOf { frame[it].toInt() and 0xFF }
        frame[Iap2LinkEngine.HEADER_BYTES - 1] = (-checksum).toByte()
        return frame
    }

    // ---- 工具 ----

    private fun exchange(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        b.feed(a.takeOutput(), tick())
        a.feed(b.takeOutput(), tick())
    }

    private fun exchangeUntilNormal(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        var guard = 0
        while ((a.state != Iap2LinkEngine.State.NORMAL || b.state != Iap2LinkEngine.State.NORMAL) &&
            guard++ < 10
        ) {
            exchange(a, b)
        }
    }

    private fun pollControls(engine: Iap2LinkEngine): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            when (val event = engine.pollEvent() ?: return out) {
                is Iap2LinkEngine.Event.Control -> out += event.bytes
                is Iap2LinkEngine.Event.Dead -> error("engine died: ${event.reason}")
            }
        }
    }

    private var clock = 0L

    private fun tick(): Long = ++clock
}
