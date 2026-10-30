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
