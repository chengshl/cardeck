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

    @Test fun closeDrainCannotClearWaiterRegisteredAfterRetirementLock() {
        val writeLock = Any()
        val pending = ConcurrentHashMap<Int, String>()
        pending[7] = "retiring-session-waiter"
        val closeHasLock = CountDownLatch(1)
        val allowCloseDrain = CountDownLatch(1)
        val drained = AtomicReference<List<String>?>()
        val failure = AtomicReference<Throwable?>()

        val closer = Thread {
            try {
                synchronized(writeLock) {
                    closeHasLock.countDown()
                    check(allowCloseDrain.await(3, TimeUnit.SECONDS))
                    drained.set(RtspReaderGenerationCleanup.drainAll(pending))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        closer.start()
        val nextRequest = Thread {
            try {
                synchronized(writeLock) { pending[42] = "next-session-waiter" }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            assertTrue(closeHasLock.await(3, TimeUnit.SECONDS))
            nextRequest.start()
        } finally {
            allowCloseDrain.countDown()
        }
        closer.join(3000)
        nextRequest.join(3000)
        assertFalse(closer.isAlive)
        assertFalse(nextRequest.isAlive)
        assertNull(failure.get())
        assertEquals(listOf("retiring-session-waiter"), drained.get())
        assertEquals("next-session-waiter", pending[42])
    }

    @Test fun oldCloseSnapshotThenClearOutsideWriteLockCouldLoseNewWaiter() {
        val pending = ConcurrentHashMap<Int, String>()
        pending[7] = "retiring-session-waiter"
        val oldSnapshot = pending.values.toList()
        pending[42] = "next-session-waiter"
        pending.clear() // baseline close() after releasing the write lock
        assertEquals(listOf("retiring-session-waiter"), oldSnapshot)
        assertFalse(pending.containsKey(42))
    }

    @Test fun onlyOwningReaderMayDispatchOrAcknowledge() {
        val oldSocket = Any()
        val newSocket = Any()
        assertTrue(RtspReaderGenerationCleanup.isCurrentReader(7, 7, oldSocket, oldSocket))
        assertFalse(RtspReaderGenerationCleanup.isCurrentReader(7, 8, oldSocket, oldSocket))
        assertFalse(RtspReaderGenerationCleanup.isCurrentReader(7, 7, oldSocket, newSocket))
        assertFalse(RtspReaderGenerationCleanup.isCurrentReader(7, 7, oldSocket, null))
    }

    @Test fun staleReaderCannotDeliverIntoReconnectedSessionPendingQueue() {
        val writeLock = Any()
        val oldSocket = Any()
        val newSocket = Any()
        var generation = 1
        var activeSocket: Any? = oldSocket
        val pending = ConcurrentHashMap<Int, String>()
        val parsedOldReply = CountDownLatch(1)
        val allowOldDispatch = CountDownLatch(1)
        val delivered = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()

        val oldReader = Thread {
            try {
                // The old socket has already parsed CSeq=42, but has not yet
                // delivered it. A reconnect may register a new pending CSeq.
                parsedOldReply.countDown()
                check(allowOldDispatch.await(3, TimeUnit.SECONDS))
                synchronized(writeLock) {
                    if (RtspReaderGenerationCleanup.isCurrentReader(
                            1, generation, oldSocket, activeSocket,
                        )) delivered.set(pending[42])
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        oldReader.start()
        try {
            assertTrue(parsedOldReply.await(3, TimeUnit.SECONDS))
            synchronized(writeLock) {
                generation = 2
                activeSocket = newSocket
                pending[42] = "new-session-waiter"
            }
        } finally {
            allowOldDispatch.countDown()
        }
        oldReader.join(3000)
        assertFalse(oldReader.isAlive)
        assertNull(failure.get())
        assertNull("stale reader must not deliver into the new CSeq queue", delivered.get())
        assertEquals("new-session-waiter", pending[42])
    }

    @Test fun ackGateRechecksReaderIdentityAfterReconnect() {
        val writeLock = Any()
        val oldSocket = Any()
        val newSocket = Any()
        var generation = 5
        var activeSocket: Any? = oldSocket
        val initialCheck = synchronized(writeLock) {
            RtspReaderGenerationCleanup.isCurrentReader(5, generation, oldSocket, activeSocket)
        }
        assertTrue(initialCheck)
        synchronized(writeLock) {
            generation = 6
            activeSocket = newSocket
        }
        // The early dispatch check passed, but ACK must be fenced again
        // while holding the same lock as RTSP socket replacement.
        val mayAck = synchronized(writeLock) {
            RtspReaderGenerationCleanup.isCurrentReader(5, generation, oldSocket, activeSocket)
        }
        assertFalse("old peer request must not be ACKed on new RTSP socket", mayAck)
    }

    @Test fun staleRequestCannotReplaceNewReaderAfterReconnect() {
        val writeLock = Any()
        val oldSocket = Any()
        val newSocket = Any()
        val requestCapturedOldSocket = CountDownLatch(1)
        val releaseStaleRequest = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        var publishedSocket: Any? = oldSocket
        var readerSocket: Any? = oldSocket
        var staleReaderInstalled = false

        val staleRequest = Thread {
            try {
                // request() captures oldSocket before acquiring rtspWriteLock.
                val captured = oldSocket
                requestCapturedOldSocket.countDown()
                check(releaseStaleRequest.await(3, TimeUnit.SECONDS))
                synchronized(writeLock) {
                    if (RtspReaderGenerationCleanup.isCurrentSocket(captured, publishedSocket)) {
                        readerSocket = captured
                        staleReaderInstalled = true
                    }
                }
            } catch (error: Throwable) {
                workerFailure.set(error)
            }
        }
        staleRequest.start()
        try {
            assertTrue(requestCapturedOldSocket.await(3, TimeUnit.SECONDS))
            // New connection publication uses the same lock as request().
            synchronized(writeLock) {
                publishedSocket = newSocket
                readerSocket = newSocket
            }
        } finally {
            releaseStaleRequest.countDown()
        }
        staleRequest.join(3000)
        assertFalse(staleRequest.isAlive)
        assertNull(workerFailure.get())
        assertFalse("retired request must not replace the active reader", staleReaderInstalled)
        assertTrue("new RTSP reader must remain owned by new socket", readerSocket === newSocket)
        assertTrue(RtspReaderGenerationCleanup.isCurrentSocket(newSocket, publishedSocket))
        assertFalse(RtspReaderGenerationCleanup.isCurrentSocket(oldSocket, publishedSocket))
        assertFalse(RtspReaderGenerationCleanup.isCurrentSocket(oldSocket, null))
    }

    @Test fun deadCurrentReaderMayRetireItsPublishedSocketAndWakeWaiters() {
        val socket = Any()
        val pending = ConcurrentHashMap<Int, String>()
        pending[7] = "waiting-request"
        val retired = RtspReaderGenerationCleanup.retireIfCurrent(
            4, 4, socket, socket, socket, pending,
        )
        assertEquals(listOf("waiting-request"), retired)
        assertTrue(pending.isEmpty())
    }

    @Test fun oldReaderCannotRetireReplacementSocketBeforeNewReaderStarts() {
        val oldSocket = Any()
        val newSocket = Any()
        val pending = ConcurrentHashMap<Int, String>()
        pending[42] = "new-connection-waiter"
        // The replacement is published, but readerGeneration has not advanced.
        assertNull(RtspReaderGenerationCleanup.retireIfCurrent(
            4, 4, oldSocket, oldSocket, newSocket, pending,
        ))
        assertEquals("new-connection-waiter", pending[42])
        assertNull(RtspReaderGenerationCleanup.retireIfCurrent(
            4, 5, oldSocket, newSocket, newSocket, pending,
        ))
        assertEquals("new-connection-waiter", pending[42])
    }

    @Test fun eofRetirementRacingNewSocketPublicationDoesNotDrainNewWaiter() {
        val lock = Any()
        val oldSocket = Any()
        val newSocket = Any()
        var published: Any? = oldSocket
        var activeReader: Any? = oldSocket
        var generation = 3
        val pending = ConcurrentHashMap<Int, String>()
        val eofObserved = CountDownLatch(1)
        val resumeOldEof = CountDownLatch(1)
        val retired = AtomicReference<List<String>?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                eofObserved.countDown()
                check(resumeOldEof.await(3, TimeUnit.SECONDS))
                synchronized(lock) {
                    retired.set(RtspReaderGenerationCleanup.retireIfCurrent(
                        3, generation, oldSocket, activeReader, published, pending,
                    ))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        try {
            assertTrue(eofObserved.await(3, TimeUnit.SECONDS))
            synchronized(lock) {
                published = newSocket
                activeReader = newSocket
                generation = 4
                pending[42] = "new-connection-waiter"
            }
        } finally {
            resumeOldEof.countDown()
        }
        worker.join(3000)
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertNull("old EOF must not retire the replacement connection", retired.get())
        assertEquals("new-connection-waiter", pending[42])
    }

}
