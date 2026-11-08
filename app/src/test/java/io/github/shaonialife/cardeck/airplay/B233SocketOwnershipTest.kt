package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic seam checks of the T104 proposed guard protocol, NOT an
 * Android integration test. Production still requires full Gradle CI.
 */
class B233SocketOwnershipTest {
    private class FakeEventChannel {
        private val lock = Any()
        private var current: Any? = null
        var acknowledgements = 0
            private set
        var nonceUses = 0
            private set

        fun register(socket: Any) = synchronized(lock) { current = socket }

        fun ack(originatingSocket: Any?): Boolean = synchronized(lock) {
            val captured = current ?: return@synchronized false
            if (originatingSocket != null && current !== originatingSocket) return@synchronized false
            // The real code encrypts and writes only after this check.
            if (captured !== current) return@synchronized false
            nonceUses++
            acknowledgements++
            true
        }
    }

    private class FakeRtspChannel {
        private val lock = Any()
        var current: Any? = null
        var sequence = 0
            private set
        var outbound = 0
            private set

        fun request(expected: Any?, betweenChecks: () -> Unit = {}): Boolean {
            val captured = current ?: return false
            if (expected != null && captured !== expected) return false
            betweenChecks()
            return synchronized(lock) {
                if (expected != null && current !== expected) return@synchronized false
                // The real method writes to the captured Socket, not current.
                sequence++
                outbound++
                true
            }
        }
    }

    @Test fun retiredEventReaderNeverConsumesNewConnectionNonce() {
        val channel = FakeEventChannel()
        val old = Any()
        val replacement = Any()
        channel.register(old)
        channel.register(replacement)
        assertFalse(channel.ack(old))
        assertEquals(0, channel.nonceUses)
        assertEquals(0, channel.acknowledgements)
        assertTrue(channel.ack(replacement))
        assertEquals(1, channel.nonceUses)
    }

    @Test fun legacyEventAcknowledgementRetainsOriginalNoOriginPrecondition() {
        val channel = FakeEventChannel()
        channel.register(Any())
        assertTrue(channel.ack(null))
        assertEquals(1, channel.acknowledgements)
    }

    @Test fun rtspNewSocketReplacesOldBeforeFirstGuard() {
        val port = FakeRtspChannel()
        val old = Any()
        port.current = Any()
        assertFalse(port.request(old))
        assertEquals(0, port.outbound)
        assertEquals(0, port.sequence)
    }

    @Test fun rtspReconnectBetweenQueueAndWriteDoesNotConsumeCSeq() {
        val port = FakeRtspChannel()
        val old = Any()
        port.current = old
        assertFalse(port.request(old) { port.current = Any() })
        assertEquals(0, port.outbound)
        assertEquals(0, port.sequence)
    }

    @Test fun correctRtspSocketAndLegacyNullExpectedBothWork() {
        val port = FakeRtspChannel()
        val socket = Any()
        port.current = socket
        assertTrue(port.request(socket))
        assertTrue(port.request(null))
        assertEquals(2, port.outbound)
        assertEquals(2, port.sequence)
    }

    private class FakeReaderOwner {
        private val lock = Any()
        var currentSocket: Any? = null
        var readerSocket: Any? = null
        var closedReaderCount = 0
        fun startReader(captured: Any, expected: Any?): Boolean = synchronized(lock) {
            if (expected != null && (currentSocket !== expected || captured !== expected)) return@synchronized false
            if (readerSocket !== captured && readerSocket != null) closedReaderCount++
            readerSocket = captured
            true
        }
    }

    @Test fun staleRtspRequestNeverClosesReplacementReader() {
        val owner = FakeReaderOwner()
        val old = Any(); val fresh = Any()
        owner.currentSocket = old
        // request captured old Socket before reconnect, then a fresh reader was installed.
        owner.currentSocket = fresh
        assertTrue(owner.startReader(fresh, fresh))
        assertFalse(owner.startReader(old, old))
        assertTrue(owner.readerSocket === fresh)
        assertEquals(0, owner.closedReaderCount)
    }

    @Test fun currentRtspReaderStillStartsAndLegacyNullStillWorks() {
        val owner = FakeReaderOwner()
        val old = Any(); val next = Any()
        owner.currentSocket = old
        assertTrue(owner.startReader(old, old))
        owner.currentSocket = next
        assertTrue(owner.startReader(next, null)) // non-B233 legacy behavior
        assertTrue(owner.readerSocket === next)
        assertEquals(1, owner.closedReaderCount)
    }
}
