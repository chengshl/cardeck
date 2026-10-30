package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure deterministic telemetry tests; no Android Network, no real vehicle. */
class TimingSendMetricsTest {
    @Test
    fun receivedRequestIsNotSuccessfulSend() {
        val metrics = TimingSendMetrics()
        metrics.requestReceived()
        val before = metrics.snapshot()
        assertEquals(1L, before.requestRx)
        assertEquals(0L, before.responseSendOk)
        assertEquals(0L, before.sendError)
        assertEquals(-1L, before.lastSuccessMonoMs)
        metrics.responseSendFailed()
        val after = metrics.snapshot()
        assertEquals(1L, after.requestRx)
        assertEquals(0L, after.responseSendOk)
        assertEquals(1L, after.sendError)
        assertEquals(-1L, after.lastSuccessMonoMs)
    }

    @Test
    fun recordsLastSuccessfulMonotonicTimeAndLoopExitSeparately() {
        val metrics = TimingSendMetrics()
        metrics.requestReceived()
        metrics.responseSentOk(12_345L)
        metrics.requestReceived()
        metrics.responseSendFailed()
        metrics.exited("receive_error")
        val snapshot = metrics.snapshot()
        assertEquals(2L, snapshot.requestRx)
        assertEquals(1L, snapshot.responseSendOk)
        assertEquals(1L, snapshot.sendError)
        assertEquals(1L, snapshot.loopExit)
        assertEquals("receive_error", snapshot.lastLoopExitReason)
        assertEquals(12_345L, snapshot.lastSuccessMonoMs)
    }

    @Test
    fun cleanStopAndNoRequestsAreNotFalseAcknowledgements() {
        val metrics = TimingSendMetrics()
        metrics.exited("stop")
        val snapshot = metrics.snapshot()
        assertEquals(0L, snapshot.requestRx)
        assertEquals(0L, snapshot.responseSendOk)
        assertEquals(0L, snapshot.sendError)
        assertEquals("stop", snapshot.lastLoopExitReason)
        assertEquals(-1L, snapshot.lastSuccessMonoMs)
    }

    @Test
    fun retiredSocketCannotPolluteReplacementTelemetry() {
        // A previous UDP worker can finish AFTER a replacement has received data.
        // Their counters and final exit reasons must never share one instance.
        val retired = TimingSendMetrics()
        retired.requestReceived()
        retired.responseSentOk(1_000L)

        val replacement = TimingSendMetrics()
        replacement.requestReceived()
        replacement.responseSendFailed()
        retired.exited("stop")

        val oldSnapshot = retired.snapshot()
        val newSnapshot = replacement.snapshot()
        assertEquals(1L, oldSnapshot.requestRx)
        assertEquals(1L, oldSnapshot.responseSendOk)
        assertEquals(0L, oldSnapshot.sendError)
        assertEquals(1L, oldSnapshot.loopExit)
        assertEquals(1_000L, oldSnapshot.lastSuccessMonoMs)
        assertEquals(1L, newSnapshot.requestRx)
        assertEquals(0L, newSnapshot.responseSendOk)
        assertEquals(1L, newSnapshot.sendError)
        assertEquals(0L, newSnapshot.loopExit)
        assertEquals("none", newSnapshot.lastLoopExitReason)
        assertEquals(-1L, newSnapshot.lastSuccessMonoMs)
    }

    @Test
    fun replacementWorkerReportsItsFirstThreeRequestsIndependently() {
        val retired = TimingSendMetrics()
        repeat(10) { retired.requestReceived() }
        assertTrue(retired.snapshot().requestRx > 3L)
        val replacement = TimingSendMetrics()
        repeat(3) {
            replacement.requestReceived()
            assertTrue(replacement.snapshot().requestRx <= 3L)
        }
        replacement.requestReceived()
        assertTrue(replacement.snapshot().requestRx > 3L)
        assertEquals(10L, retired.snapshot().requestRx)
    }

    @Test
    fun snapshotsRemainConsistentUnderConcurrentWriters() {
        val metrics = TimingSendMetrics()
        val threads = (0 until 4).map {
            Thread {
                repeat(1000) {
                    metrics.requestReceived()
                    metrics.responseSentOk(it.toLong())
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        val snapshot = metrics.snapshot()
        assertEquals(4_000L, snapshot.requestRx)
        assertEquals(4_000L, snapshot.responseSendOk)
        assertEquals(0L, snapshot.sendError)
        assertTrue(snapshot.lastSuccessMonoMs >= 0)
    }
}
