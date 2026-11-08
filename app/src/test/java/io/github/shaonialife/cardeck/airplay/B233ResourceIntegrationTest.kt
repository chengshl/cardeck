package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T104-v4 local production-interface contracts, not Android Gradle integration. */
class B233ResourceIntegrationTest {
    private class Deferred : Executor {
        val queued = arrayListOf<Runnable>()
        override fun execute(command: Runnable) { queued.add(command) }
        fun drain() { while (queued.isNotEmpty()) queued.removeAt(0).run() }
    }

    private fun modes(resourceId: Long = 1L, transfer: Long = 1L, borrow: String? = null): Map<String, Any> =
        mapOf(
            "type" to "changeModes",
            "params" to mapOf(
                "resources" to listOf(linkedMapOf<String, Any>(
                    "resourceID" to resourceId, "transferType" to transfer,
                ).also { if (borrow != null) it["borrowID"] = borrow }),
            ),
        )

    @Test fun successfulAudioClaimInvalidatesEarlierQueuedSnapshot() {
        val fence = B233ResourceSessionFence()
        val c = Any(); val e = Any(); val g = fence.begin(c, e, null)
        val queue = Deferred(); val sent = mutableListOf<B233ResourceSessionFence.Snapshot>()
        val driver = B233ResourceEventDriver(fence, queue) { sent += it }
        assertTrue(driver.onEvent(g, c, e, modes(), claimAudio = false))
        assertTrue(fence.noteLocalAudioClaim(g, c, e))
        queue.drain()
        assertTrue(sent.isEmpty())
        assertTrue(driver.onEvent(g, c, e, mapOf("type" to "requestUI"), claimAudio = true))
        queue.drain()
        assertEquals(listOf(1L, 2L), sent.single().resources.map { it["resourceID"] })
        assertEquals(1L, sent.single().resources.last()["entity"])
    }

    @Test fun cannotReclaimAudioUntilOutstandingBorrowIsReturned() {
        val fence = B233ResourceSessionFence()
        val c = Any(); val e = Any(); val g = fence.begin(c, e, null)
        fence.plan(g, c, e, modes(2L,3L,"held"), true)
        assertFalse(fence.noteLocalAudioClaim(g, c, e))
        fence.plan(g, c, e, modes(2L,4L,"held"), true)
        assertTrue(fence.noteLocalAudioClaim(g, c, e))
    }

    @Test fun staleSessionCannotAdoptSuccessfulAudioClaim() {
        val fence = B233ResourceSessionFence()
        val oldC = Any(); val oldE = Any(); val g1=fence.begin(oldC,oldE,null)
        val c=Any();val e=Any();val g2=fence.begin(c,e,null)
        assertFalse(fence.noteLocalAudioClaim(g1,oldC,oldE))
        assertTrue(fence.noteLocalAudioClaim(g2,c,e))
        assertFalse(fence.close(g1,oldC,oldE))
    }

    @Test fun audioPolicyTogglePreventsStaleSnapshotFromSending() {
        val fence=B233ResourceSessionFence(); val c=Any();val e=Any();val g=fence.begin(c,e,null)
        var currentClaim=true
        val queue=Deferred(); var writes=0
        val driver=B233ResourceEventDriver(fence,queue) { snapshot ->
            val captured=snapshot.resources.any{it["resourceID"]==2L}
            if(captured==currentClaim) writes++
        }
        assertTrue(fence.noteLocalAudioClaim(g,c,e))
        assertTrue(driver.onEvent(g,c,e,mapOf("type" to "requestUI"),true))
        currentClaim=false
        queue.drain()
        assertEquals(0,writes)
        assertTrue(driver.onEvent(g,c,e,mapOf("type" to "requestUI"),false))
        queue.drain()
        assertEquals(1,writes)
    }

    @Test fun saturatedResourceQueueCoalescesLatestWithoutInlineSend() {
        val pool = java.util.concurrent.ThreadPoolExecutor(
            1, 1, 15L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(2),
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
        val workerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWorker = java.util.concurrent.CountDownLatch(1)
        val lastDelivered = java.util.concurrent.CountDownLatch(1)
        val fence = B233ResourceSessionFence()
        val c = Any(); val e = Any(); val gen = fence.begin(c, e, null)
        val delivered = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val driver = B233ResourceEventDriver(fence, pool) { snapshot ->
            if (snapshot.revision == 1L) {
                workerEntered.countDown()
                releaseWorker.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            delivered.add(snapshot.revision)
            if (snapshot.revision == 4L) lastDelivered.countDown()
        }
        val event = mapOf("type" to "requestUI")
        try {
            assertTrue(driver.onEvent(gen, c, e, event, claimAudio = false))
            assertTrue(workerEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(driver.onEvent(gen, c, e, event, claimAudio = false))
            assertTrue(driver.onEvent(gen, c, e, event, claimAudio = false))
            assertTrue(driver.onEvent(gen, c, e, event, claimAudio = false))
            // The latest event must reach the worker without invoking send here.
            assertFalse(delivered.contains(4L))
            releaseWorker.countDown()
            assertTrue(lastDelivered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(delivered.contains(4L))
            assertFalse(delivered.contains(2L))
            assertFalse(delivered.contains(3L))
        } finally {
            releaseWorker.countDown()
            pool.shutdownNow()
        }
    }
}
