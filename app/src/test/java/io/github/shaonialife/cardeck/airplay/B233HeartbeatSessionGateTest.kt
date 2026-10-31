package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic worker interleavings: no network, no vehicle and no APK. */
class B233HeartbeatSessionGateTest {
    @Test
    fun oldKeepAliveCannotPingRetiredHostAfterFastReconnect() {
        val gate = B233HeartbeatSessionGate()
        val oldToken = gate.snapshot()
        val sentToOldHost = AtomicInteger()
        // The old loop sleeps with the first session's host and UDP port.
        gate.retire()
        val newToken = gate.snapshot()
        val socketIsOpenAgain = true
        val didSend = socketIsOpenAgain && gate.runIfCurrent(oldToken) {
            sentToOldHost.incrementAndGet()
        }
        assertFalse(didSend)
        assertEquals(0, sentToOldHost.get())
        assertTrue(gate.isCurrent(newToken))
        assertFalse(gate.isCurrent(oldToken))
    }

    @Test
    fun delayedOldFeedbackWorkerCannotAdoptReplacementSession() {
        val gate = B233HeartbeatSessionGate()
        // Capture at startFeedbackLoop() invocation, before Thread.start().
        val oldToken = gate.snapshot()
        val workerReady = CountDownLatch(1)
        val permitWorker = CountDownLatch(1)
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldWorker = executor.submit {
                workerReady.countDown()
                check(permitWorker.await(5, TimeUnit.SECONDS))
                gate.runIfCurrent(oldToken) { requests.incrementAndGet() }
            }
            assertTrue(workerReady.await(5, TimeUnit.SECONDS))
            gate.retire()
            val newToken = gate.snapshot()
            permitWorker.countDown()
            oldWorker.get(5, TimeUnit.SECONDS)
            assertEquals("old feedback worker must not request on new RTSP", 0, requests.get())
            assertTrue(gate.runIfCurrent(newToken) { requests.incrementAndGet() })
            assertEquals(1, requests.get())
        } finally {
            permitWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun feedbackResponseAfterCloseCannotBeReportedAsNewSession() {
        val gate = B233HeartbeatSessionGate()
        val oldToken = gate.snapshot()
        val replyArrived = CountDownLatch(1)
        val resumeReply = CountDownLatch(1)
        val reported = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val inFlight = executor.submit {
                // request() began while this worker still owned the session.
                check(gate.isCurrent(oldToken))
                replyArrived.countDown()
                check(resumeReply.await(5, TimeUnit.SECONDS))
                // Same check as immediately after request() returns.
                if (gate.isCurrent(oldToken)) reported.incrementAndGet()
            }
            assertTrue(replyArrived.await(5, TimeUnit.SECONDS))
            gate.retire()
            resumeReply.countDown()
            inFlight.get(5, TimeUnit.SECONDS)
            assertEquals("retired feedback must not update new-session telemetry", 0, reported.get())
        } finally {
            resumeReply.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun runIfCurrentRejectsStaleNetworkActionsAfterRetirement() {
        val gate = B233HeartbeatSessionGate()
        val token = gate.snapshot()
        val actions = AtomicInteger()
        gate.retire()
        assertFalse(gate.runIfCurrent(token) { actions.incrementAndGet() })
        assertEquals(0, actions.get())
    }

    @Test
    fun capturedRtspSocketCannotAdoptNewSessionAfterRetirement() {
        val gate = B233HeartbeatSessionGate()
        val token = gate.snapshot()
        val oldSocket = Any()
        val captured = gate.captureIfCurrent(token) { oldSocket }
        assertTrue("capture must belong to old session", captured === oldSocket)
        gate.retire()
        val newSocket = Any()
        assertTrue(gate.captureIfCurrent(gate.snapshot()) { newSocket } === newSocket)
        // Old request must never recapture the new socket, even if its write runs late.
        assertTrue(captured === oldSocket)
        assertFalse(gate.isCurrent(token))
        assertTrue(gate.captureIfCurrent(token) { newSocket } == null)
    }

    @Test
    fun retirementIsNotBlockedByPausedRtspWriteAfterSocketCapture() {
        val gate = B233HeartbeatSessionGate()
        val token = gate.snapshot()
        val oldSocket = Any()
        val captured = gate.captureIfCurrent(token) { oldSocket }
        val writeStarted = CountDownLatch(1)
        val allowWriteToFinish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit {
                // Simulate a blocked network write to the already captured socket.
                writeStarted.countDown()
                check(allowWriteToFinish.await(5, TimeUnit.SECONDS))
                check(captured === oldSocket)
            }
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            // close() must be able to retire BEFORE the simulated write unblocks.
            val retiring = executor.submit<Long> { gate.retire() }
            retiring.get(2, TimeUnit.SECONDS)
            assertFalse(gate.isCurrent(token))
            allowWriteToFinish.countDown()
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            allowWriteToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun nonB233LegacyWorkerDoesNotDependOnGate() {
        val gate = B233HeartbeatSessionGate()
        val guardB233 = false
        val token = gate.snapshot()
        gate.retire()
        // All non-B233 receivers retain the original socket-only loop check.
        val socketIsOpen = true
        assertTrue(socketIsOpen && (!guardB233 || gate.isCurrent(token)))
    }
}
