package io.github.shaonialife.cardeck.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pure synthetic grant identities. No Android permission dialog or real capture. */
class ProjectionLeaseTest {
    @Test
    fun expiredCallbackCannotEraseSubsequentGrant() {
        val lease = ProjectionLease<Any>()
        val first = Any()
        val second = Any()
        lease.publish(first)
        lease.publish(second)
        assertFalse(lease.clearIfCurrent(first))
        assertSame(second, lease.current())
    }

    @Test
    fun currentOnStopRevokesReadinessAndRequiresFreshGrant() {
        val lease = ProjectionLease<Any>()
        val active = Any()
        lease.publish(active)
        assertTrue(lease.clearIfCurrent(active))
        assertNull(lease.current())
        assertFalse(lease.clearIfCurrent(active))
        val next = Any()
        lease.publish(next)
        assertSame(next, lease.current())
    }

    @Test
    fun explicitStopImmediatelyRevokesReadinessBeforeAsyncOnStop() {
        val lease = ProjectionLease<Any>()
        val stopped = Any()
        lease.publish(stopped)
        assertSame(stopped, lease.clearCurrent())
        assertNull(lease.current())
        // The platform callback for the revoked permission may arrive late.
        val newGrant = Any()
        lease.publish(newGrant)
        assertFalse(lease.clearIfCurrent(stopped))
        assertSame(newGrant, lease.current())
        assertSame(newGrant, lease.clearCurrent())
        assertNull(lease.current())
    }

    @Test
    fun delayedOldOnStopCannotRaceWithNewPublication() {
        val lease = ProjectionLease<Any>()
        val first = Any()
        val second = Any()
        lease.publish(first)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val oldCallback = pool.submit<Boolean> {
                waiting.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                lease.clearIfCurrent(first)
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            lease.publish(second)
            release.countDown()
            assertFalse(oldCallback.get(5, TimeUnit.SECONDS))
            assertSame(second, lease.current())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
    @Test
    fun serviceDestroyedWithoutExplicitStopRevokesOnlyItsLiveGrant() {
        val lease = ProjectionLease<Any>()
        val owned = Any()
        val retired = mutableListOf<Any>()
        lease.publish(owned)
        assertTrue(lease.retireOwned(owned) { retired.add(it) })
        assertNull(lease.current())
        assertFalse(lease.retireOwned(owned) { retired.add(it) })
        assertEquals(listOf(owned), retired)
    }

    @Test
    fun delayedOldServiceDestroyCannotStopReplacementProjection() {
        val lease = ProjectionLease<Any>()
        val oldServiceGrant = Any()
        val newServiceGrant = Any()
        val retired = mutableListOf<Any>()
        lease.publish(oldServiceGrant)
        lease.publish(newServiceGrant)
        assertFalse(lease.retireOwned(oldServiceGrant) { retired.add(it) })
        assertSame(newServiceGrant, lease.current())
        assertTrue(retired.isEmpty())
        assertTrue(lease.retireOwned(newServiceGrant) { retired.add(it) })
        assertEquals(listOf(newServiceGrant), retired)
        assertNull(lease.current())
    }

}
