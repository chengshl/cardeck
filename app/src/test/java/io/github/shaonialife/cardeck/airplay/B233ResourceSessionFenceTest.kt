package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test contracts for queued B233-only events; no real I/O is performed. */
class B233ResourceSessionFenceTest {
    private class DeferredExecutor : Executor {
        val pending = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() {
            while (pending.isNotEmpty()) pending.removeAt(0).run()
        }
    }

    private fun event(type: String, resource: Long = 1L, transfer: Long = 1L): Map<String, Any> =
        if (type == "changeModes") mapOf("type" to type, "params" to mapOf("resources" to listOf(mapOf("resourceID" to resource, "transferType" to transfer))))
        else mapOf("type" to type)

    @Test fun staleQueuedEventNeverSendsAfterReplacement() {
        val fence = B233ResourceSessionFence()
        val queue = DeferredExecutor()
        val sent = mutableListOf<B233ResourceSessionFence.Snapshot>()
        val driver = B233ResourceEventDriver(fence, queue) { sent += it }
        val controlA = Any()
        val eventA = Any()
        val genA = fence.begin(controlA, eventA, null)
        assertTrue(driver.onEvent(genA, controlA, eventA, event("changeModes"), false))
        val controlB = Any()
        val eventB = Any()
        val genB = fence.begin(controlB, eventB, null)
        assertFalse(driver.onEvent(genA, controlA, eventA, event("changeModes"), false))
        assertTrue(driver.onEvent(genB, controlB, eventB, event("requestUI"), false))
        assertEquals(2, queue.pending.size)
        queue.drain()
        assertEquals(1, sent.size)
        assertEquals(genB, sent.single().generation)
        assertEquals(1L, sent.single().resources.single()["entity"])
    }

    @Test fun oldCloseCannotRetireReplacementSession() {
        val fence = B233ResourceSessionFence()
        val a = Any(); val ae = Any()
        val ga = fence.begin(a, ae, null)
        val b = Any(); val be = Any()
        val gb = fence.begin(b, be, null)
        assertFalse(fence.close(ga, a, ae))
        assertTrue(fence.plan(gb, b, be, event("requestUI"), false) != null)
        assertTrue(fence.close(gb, b, be))
        assertFalse(fence.close(gb, b, be))
        assertTrue(fence.plan(gb, b, be, event("requestUI"), false) == null)
    }

    @Test fun wrongEventSocketAndWrongControlSocketNeverMutate() {
        val fence = B233ResourceSessionFence()
        val control = Any(); val event = Any()
        val gen = fence.begin(control, event, null)
        assertTrue(fence.plan(gen, control, Any(), event("changeModes"), true) == null)
        assertTrue(fence.plan(gen, Any(), event, event("changeModes"), true) == null)
        val s = fence.plan(gen, control, event, event("requestUI"), true)!!
        assertEquals(1L, s.resources.first()["entity"])
        assertEquals(listOf(1L), s.resources.map { it["resourceID"] })
    }

    @Test fun newerEventSupersedesPreviouslyQueuedSnapshot() {
        val fence = B233ResourceSessionFence()
        val queue = DeferredExecutor()
        val sent = mutableListOf<B233ResourceSessionFence.Snapshot>()
        val driver = B233ResourceEventDriver(fence, queue) { sent += it }
        val control = Any(); val event = Any()
        val gen = fence.begin(control, event, null)
        assertTrue(driver.onEvent(gen, control, event, event("changeModes"), false))
        assertTrue(driver.onEvent(gen, control, event, event("changeModes", transfer = 2L), false))
        queue.drain()
        assertEquals(1, sent.size)
        assertEquals(1L, sent.single().resources.single()["entity"])
    }

    @Test fun originalEventMapMutationDoesNotRewriteSnapshot() {
        val fence = B233ResourceSessionFence()
        val c = Any(); val e = Any()
        val g = fence.begin(c, e, null)
        val resource = mutableMapOf<String, Any>("resourceID" to 1L, "transferType" to 1L)
        val command = mutableMapOf<String, Any>("type" to "changeModes", "params" to mapOf("resources" to listOf(resource)))
        val snapshot = fence.plan(g, c, e, command, claimAudio = false)!!
        resource["transferType"] = 2L
        command["type"] = "requestUI"
        assertEquals(2L, snapshot.resources.single()["entity"])
        assertEquals("changeModes", snapshot.type)
    }

    @Test fun readerNeverRunsNetworkSendSynchronous() {
        val fence = B233ResourceSessionFence()
        val queue = DeferredExecutor()
        var sendCount = 0
        val driver = B233ResourceEventDriver(fence, queue) { sendCount++ }
        val c = Any(); val e = Any()
        val g = fence.begin(c, e, null)
        assertTrue(driver.onEvent(g, c, e, event("requestUI"), false))
        assertEquals(0, sendCount)
        assertEquals(1, queue.pending.size)
        queue.drain()
        assertEquals(1, sendCount)
    }

    @Test fun unsupportedCommandsAreIgnoredWithoutOutboundRequests() {
        val fence = B233ResourceSessionFence()
        val queue = DeferredExecutor()
        val driver = B233ResourceEventDriver(fence, queue) { error("must not send") }
        val c = Any(); val e = Any()
        val g = fence.begin(c, e, null)
        assertFalse(driver.onEvent(g, c, e, event("iAPSendMessage"), true))
        assertFalse(driver.onEvent(g, c, e, mapOf("type" to "changeModes", "params" to "not a map"), true))
        queue.drain()
        assertEquals(0, queue.pending.size)
    }

    @Test fun videoOnlyKeepsAudioUnclaimedEvenIfCarChangesAudioResource() {
        val fence = B233ResourceSessionFence()
        val c = Any(); val e = Any()
        val g = fence.begin(c, e, null)
        val snapshot = fence.plan(g, c, e, event("changeModes", resource = 2L), false)!!
        assertEquals(listOf(1L), snapshot.resources.map { it["resourceID"] })
        val pending = fence.plan(g, c, e, event("requestUI"), true)!!
        assertEquals(listOf(1L), pending.resources.map { it["resourceID"] })
        // No event can include audio before the explicit T85 POST succeeds.
        assertTrue(fence.noteLocalAudioClaim(g, c, e))
        val audioMode = fence.plan(g, c, e, event("requestUI"), true)!!
        assertEquals(listOf(1L, 2L), audioMode.resources.map { it["resourceID"] })
        assertEquals(1L, audioMode.resources.last()["entity"])
    }

    @Test fun audioModeChangeInvalidatesQueuedClaim() {
        val fence = B233ResourceSessionFence()
        val queue = DeferredExecutor()
        val sent = mutableListOf<B233ResourceSessionFence.Snapshot>()
        val driver = B233ResourceEventDriver(fence, queue) { sent += it }
        val control = Any(); val event = Any()
        val generation = fence.begin(control, event, null)
        assertTrue(driver.onEvent(generation, control, event, event("requestUI"), true))
        assertTrue(fence.invalidatePending(generation, control, event))
        queue.drain()
        assertEquals(0, sent.size)
        assertTrue(driver.onEvent(generation, control, event, event("requestUI"), false))
        queue.drain()
        assertEquals(listOf(1L), sent.single().resources.map { it["resourceID"] })
    }

    @Test fun rejectedExecutorMustNotRunNetworkOnReader() {
        val fence = B233ResourceSessionFence()
        val control = Any(); val event = Any()
        val generation = fence.begin(control, event, null)
        val rejecting = Executor { throw java.util.concurrent.RejectedExecutionException("closed") }
        val driver = B233ResourceEventDriver(fence, rejecting) { error("no synchronous fallback") }
        assertFalse(driver.onEvent(generation, control, event, event("requestUI"), false))
    }

}
