package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Deterministic offline concurrency checks. No network, car or user data. */
class RtspSocketLifecycleTest {
    @Test
    fun closeDuringConnectRejectsLatePublication() {
        val gate = RtspSocketLifecycle()
        val epochBeforeConnect = gate.snapshot()
        val connectStarted = CountDownLatch(1)
        val allowConnectToComplete = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val candidate = Socket()
        var current: Socket? = null
        try {
            val result = executor.submit<Boolean> {
                connectStarted.countDown()
                check(allowConnectToComplete.await(5, TimeUnit.SECONDS))
                gate.tryPublish(epochBeforeConnect) {
                    current = candidate
                    true
                }
            }
            assertTrue(connectStarted.await(5, TimeUnit.SECONDS))
            gate.retire {
                current?.close()
                current = null
            }
            allowConnectToComplete.countDown()
            assertFalse("stale connect must not publish after close", result.get(5, TimeUnit.SECONDS))
            assertEquals(null, current)
        } finally {
            allowConnectToComplete.countDown()
            candidate.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun normalPublishThenCloseClosesPublishedSocket() {
        val gate = RtspSocketLifecycle()
        val candidate = Socket()
        var current: Socket? = null
        val epoch = gate.snapshot()
        try {
            assertTrue(gate.tryPublish(epoch) {
                current = candidate
                true
            })
            assertEquals(candidate, current)
            gate.retire {
                current?.close()
                current = null
            }
            assertTrue(candidate.isClosed)
            assertEquals(null, current)
            assertFalse("old epoch must remain retired", gate.tryPublish(epoch) {
                current = candidate
                true
            })
        } finally {
            candidate.close()
        }
    }

    @Test
    fun failedEndpointValidationNeverPublishes() {
        val gate = RtspSocketLifecycle()
        val epoch = gate.snapshot()
        var wasPublished = false
        assertFalse(gate.tryPublish(epoch) {
            // Mirrors AirPlayClient endpoint-generation recheck in publish callback.
            false
        })
        assertFalse(wasPublished)
        val nextEpoch = gate.snapshot()
        assertEquals(epoch, nextEpoch)
        assertTrue(gate.tryPublish(nextEpoch) {
            wasPublished = true
            true
        })
        assertTrue(wasPublished)
    }

    @Test
    fun staleEpochNeverExecutesPublicationCallback() {
        val gate = RtspSocketLifecycle()
        val stale = gate.snapshot()
        gate.retire { }
        var invoked = false
        assertFalse(gate.tryPublish(stale) {
            invoked = true
            true
        })
        assertFalse(invoked)
    }
    @Test
    fun retireReaderAndCipherBeforeAllowingNextSocketPublication() {
        // Model AirPlayClient.close() resetting all RTSP state INSIDE retire().
        // Previously its reader/cipher cleanup happened after retire() returned,
        // so the next published socket could have its new state erased.
        val gate = RtspSocketLifecycle()
        var socketOwner: String? = "old"
        var cipherOwner: String? = "old"
        var readerOwner: String? = "old"
        val firstEpoch = gate.snapshot()
        val readyToFinishRetirement = CountDownLatch(1)
        val resumeRetirement = CountDownLatch(1)
        val publicationStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val retirement = executor.submit {
                gate.retire {
                    socketOwner = null
                    readyToFinishRetirement.countDown()
                    check(resumeRetirement.await(5, TimeUnit.SECONDS))
                    cipherOwner = null
                    readerOwner = null
                }
            }
            assertTrue(readyToFinishRetirement.await(5, TimeUnit.SECONDS))
            val publication = executor.submit<Boolean> {
                publicationStarted.countDown()
                // The old epoch has been retired, but its teardown must finish
                // before the next publication can acquire the lifecycle lock.
                gate.tryPublish(firstEpoch + 1) {
                    socketOwner = "new"
                    cipherOwner = "new"
                    readerOwner = "new"
                    true
                }
            }
            assertTrue(publicationStarted.await(5, TimeUnit.SECONDS))
            resumeRetirement.countDown()
            retirement.get(5, TimeUnit.SECONDS)
            assertTrue(publication.get(5, TimeUnit.SECONDS))
            assertEquals("new", socketOwner)
            assertEquals("new", cipherOwner)
            assertEquals("new", readerOwner)
        } finally {
            resumeRetirement.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun supersededReaderCannotRouteLateResponseToNewPendingRequest() {
        val writerLock = Any()
        val old = Socket()
        val replacement = Socket()
        var activeReader = replacement
        var activeGeneration = 2
        val delivered = mutableListOf<String>()
        try {
            val oldAccepted = RtspReaderDispatchGate.dispatchIfCurrent(
                writerLock,
                { activeGeneration == 1 && activeReader === old },
                { delivered += "stale-response" },
            )
            assertFalse("old RTSP reader must not poison the new CSeq reply", oldAccepted)
            assertTrue(delivered.isEmpty())

            val currentAccepted = RtspReaderDispatchGate.dispatchIfCurrent(
                writerLock,
                { activeGeneration == 2 && activeReader === replacement },
                { delivered += "new-response" },
            )
            assertTrue(currentAccepted)
            assertEquals(listOf("new-response"), delivered)
        } finally {
            old.close()
            replacement.close()
        }
    }

    @Test
    fun replacingReaderCannotInterleaveBetweenOwnershipCheckAndDispatch() {
        val writerLock = Any()
        val dispatchEntered = CountDownLatch(1)
        val permitDispatch = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var activeGeneration = 1
        val delivered = mutableListOf<String>()
        try {
            val oldDispatch = executor.submit<Boolean> {
                RtspReaderDispatchGate.dispatchIfCurrent(
                    writerLock,
                    { activeGeneration == 1 },
                    {
                        dispatchEntered.countDown()
                        check(permitDispatch.await(5, TimeUnit.SECONDS))
                        delivered += "old-on-old"
                    },
                )
            }
            assertTrue(dispatchEntered.await(5, TimeUnit.SECONDS))
            val replacement = executor.submit {
                replacementStarted.countDown()
                synchronized(writerLock) { activeGeneration = 2 }
            }
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
            permitDispatch.countDown()
            assertTrue(oldDispatch.get(5, TimeUnit.SECONDS))
            replacement.get(5, TimeUnit.SECONDS)

            assertFalse(
                RtspReaderDispatchGate.dispatchIfCurrent(
                    writerLock,
                    { activeGeneration == 1 },
                    { delivered += "old-on-new" },
                ),
            )
            assertTrue(
                RtspReaderDispatchGate.dispatchIfCurrent(
                    writerLock,
                    { activeGeneration == 2 },
                    { delivered += "new-on-new" },
                ),
            )
            assertEquals(listOf("old-on-old", "new-on-new"), delivered)
        } finally {
            permitDispatch.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun delayedOldRequestCannotInstallReaderOverReconnectedSocket() {
        val gate = RtspSocketLifecycle()
        val old = Socket()
        val replacement = Socket()
        var current: Socket? = null
        var reader: Socket? = null
        try {
            assertTrue(gate.tryPublish(gate.snapshot()) {
                current = old
                true
            })
            // A request has already captured old; reconnect retires it.
            gate.retire {
                current = null
                reader = null
            }
            assertTrue(gate.tryPublish(gate.snapshot()) {
                current = replacement
                true
            })
            assertTrue(gate.runIfCurrent(
                { current === replacement && !replacement.isClosed },
                { reader = replacement },
            ))
            // Previously startReader(old) could replace readerSocket and close
            // the new socket. The delayed old caller must be rejected.
            assertFalse(gate.runIfCurrent(
                { current === old && !old.isClosed },
                { reader = old },
            ))
            assertTrue(reader === replacement)
            assertFalse(replacement.isClosed)
        } finally {
            old.close()
            replacement.close()
        }
    }

    @Test
    fun readerInstallAndRetirementCannotInterleave() {
        val gate = RtspSocketLifecycle()
        val currentSocket = Socket()
        val installing = CountDownLatch(1)
        val finishInstall = CountDownLatch(1)
        val retiring = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var current: Socket? = null
        var reader: Socket? = null
        try {
            assertTrue(gate.tryPublish(gate.snapshot()) {
                current = currentSocket
                true
            })
            val install = executor.submit<Boolean> {
                gate.runIfCurrent(
                    { current === currentSocket },
                    {
                        installing.countDown()
                        check(finishInstall.await(5, TimeUnit.SECONDS))
                        reader = currentSocket
                    },
                )
            }
            assertTrue(installing.await(5, TimeUnit.SECONDS))
            val retire = executor.submit {
                retiring.countDown()
                gate.retire {
                    current = null
                    reader = null
                }
            }
            assertTrue(retiring.await(5, TimeUnit.SECONDS))
            finishInstall.countDown()
            assertTrue(install.get(5, TimeUnit.SECONDS))
            retire.get(5, TimeUnit.SECONDS)
            assertTrue("retired socket must not retain a reader", reader == null)
            assertFalse(gate.runIfCurrent(
                { current === currentSocket },
                { reader = currentSocket },
            ))
        } finally {
            finishInstall.countDown()
            currentSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun delayedRequestCannotConsumeReplacementCipherNonce() {
        val writeLock = Any()
        val old = Socket()
        val replacement = Socket()
        var published: Socket? = old
        var reader: Socket? = old
        var nextCipherNonce = 0
        val capturedByRequest = old
        try {
            // startReader(old) succeeded, then close/reconnect completed before
            // the request obtained rtspWriteLock to register CSeq and encrypt.
            synchronized(writeLock) {
                published = replacement
                reader = replacement
            }
            val accepted = synchronized(writeLock) {
                RtspRequestWriteOwnership.isCurrent(
                    capturedByRequest, published, reader, capturedByRequest.isClosed,
                )
            }
            if (accepted) nextCipherNonce++
            assertFalse("retired request must not use the new cipher", accepted)
            assertEquals("new session's cipher nonce must be untouched", 0, nextCipherNonce)
            assertTrue(RtspRequestWriteOwnership.isCurrent(
                replacement, published, reader, replacement.isClosed,
            ))
        } finally {
            old.close()
            replacement.close()
        }
    }

    @Test
    fun currentButClosedRequestSocketCannotRegisterOrEncrypt() {
        val closed = Socket()
        closed.close()
        assertFalse(RtspRequestWriteOwnership.isCurrent(
            closed, closed, closed, closed.isClosed,
        ))
        val current = Socket()
        try {
            assertFalse("reader must also own request socket",
                RtspRequestWriteOwnership.isCurrent(current, current, closed, current.isClosed))
            assertTrue(RtspRequestWriteOwnership.isCurrent(
                current, current, current, current.isClosed,
            ))
        } finally {
            current.close()
        }
    }

    @Test
    fun parallelConnectAttemptsPublishAtMostOneSocket() {
        val gate = RtspSocketLifecycle()
        val sameEpoch = gate.snapshot()
        val bothReady = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val candidates = listOf(Socket(), Socket())
        var current: Socket? = null
        try {
            val results = candidates.map { candidate ->
                executor.submit<Boolean> {
                    bothReady.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    gate.tryPublish(sameEpoch) {
                        current = candidate
                        true
                    }
                }
            }
            assertTrue("both attempts must reach publication", bothReady.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(
                "a competing successful TCP connect must not overwrite the first published socket",
                1,
                results.count { it.get(5, TimeUnit.SECONDS) },
            )
            assertTrue(candidates.contains(current))
            gate.retire {
                current?.close()
                current = null
            }
        } finally {
            release.countDown()
            candidates.forEach { it.close() }
            executor.shutdownNow()
        }
    }

}
