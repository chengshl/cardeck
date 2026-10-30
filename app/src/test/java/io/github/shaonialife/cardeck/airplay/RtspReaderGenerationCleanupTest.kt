package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic scheduling only; no vehicle, gateway or personal data. */
class RtspReaderGenerationCleanupTest {
    @Test fun staleReaderCannotDrainNewSessionPendingCseq() {
        val writeLock = Any()
        val pending = ConcurrentHashMap<Int, String>()
        var generation = 1
        val checkedOldGeneration = CountDownLatch(1)
        val allowOldRetirement = CountDownLatch(1)
        val drained = AtomicReference<List<String>?>()
        val workerFailure = AtomicReference<Throwable?>()

        val oldReader = Thread {
            try {
                // Reproduce the old implementation's vulnerable window: a check
                // occurs before the new generation registers its response waiter.
                checkedOldGeneration.countDown()
                check(allowOldRetirement.await(3, TimeUnit.SECONDS))
                drained.set(synchronized(writeLock) {
                    RtspReaderGenerationCleanup.drainIfCurrent(1, generation, pending)
                })
            } catch (error: Throwable) {
                workerFailure.set(error)
            }
        }
        oldReader.start()
        try {
            assertTrue(checkedOldGeneration.await(3, TimeUnit.SECONDS))
            synchronized(writeLock) {
                generation = 2
                pending[42] = "new-session-waiter"
            }
        } finally {
            allowOldRetirement.countDown()
        }
        oldReader.join(3000)
        assertFalse(oldReader.isAlive)
        assertNull("worker thread must not fail", workerFailure.get())
        assertNull("stale reader must not retire any queue", drained.get())
        assertEquals("new-session-waiter", pending[42])
    }

    @Test fun currentReaderDrainsOnlyExistingWaiters() {
        val pending = ConcurrentHashMap<Int, String>()
        pending[7] = "old-session-waiter"
        val drained = RtspReaderGenerationCleanup.drainIfCurrent(1, 1, pending)
        assertEquals(listOf("old-session-waiter"), drained)
        assertTrue(pending.isEmpty())
        pending[42] = "new-session-waiter"
        assertNull(RtspReaderGenerationCleanup.drainIfCurrent(1, 2, pending))
        assertEquals("new-session-waiter", pending[42])
    }

    @Test fun emptyCurrentReaderIsDifferentFromStaleReader() {
        val pending = ConcurrentHashMap<Int, String>()
        assertEquals(emptyList<String>(), RtspReaderGenerationCleanup.drainIfCurrent(3, 3, pending))
        assertNull(RtspReaderGenerationCleanup.drainIfCurrent(2, 3, pending))
    }

    @Test fun oldCheckThenUnconditionalClearWouldLoseNewWaiter() {
        val pending = ConcurrentHashMap<Int, String>()
        var generation = 1
        val oldCheckPassed = generation == 1
        generation = 2
        pending[42] = "new-session-waiter"
        if (oldCheckPassed) pending.clear() // exact unsafe old interleaving
        assertEquals(2, generation)
        assertFalse("baseline must reproduce the lost waiter", pending.containsKey(42))
    }
}
