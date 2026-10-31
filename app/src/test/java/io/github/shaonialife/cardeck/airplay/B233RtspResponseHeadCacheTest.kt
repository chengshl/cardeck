package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure in-memory regressions; no socket, car, credentials or device data. */
class B233RtspResponseHeadCacheTest {
    @Test
    fun successfulFeedbackResponsesDoNotAccumulateHeaders() {
        val cache = B233RtspResponseHeadCache()
        repeat(10_000) { seq ->
            cache.recordIfError(seq, "RTSP/1.0 200 OK", "synthetic-success-$seq", true)
        }
        assertEquals(0, cache.retainedCount())
        assertNull(cache.take(9_999))
    }

    @Test
    fun errorHeadersStayBoundedAndNewestFailureIsAvailable() {
        val cache = B233RtspResponseHeadCache(maxEntries = 3)
        repeat(10) { seq ->
            cache.recordIfError(seq, "RTSP/1.0 454 Session Not Found", "synthetic-error-$seq", true)
        }
        assertEquals(3, cache.retainedCount())
        assertNull(cache.take(6))
        assertEquals("synthetic-error-9", cache.take(9))
        assertEquals(2, cache.retainedCount())
        assertNull("diagnostic must be consumed once", cache.take(9))
    }

    @Test
    fun unsolicitedPeerRequestsNeverPoisonErrorDiagnostics() {
        val cache = B233RtspResponseHeadCache()
        cache.recordIfError(8, "TEARDOWN /stream RTSP/1.0", "peer-request", false)
        cache.recordIfError(9, "RTSP/1.0 500 Error", "unmatched-response", false)
        cache.recordIfError(10, "RTSP/1.0 400 Error", "matched-response", true)
        assertNull(cache.take(8))
        assertNull(cache.take(9))
        assertEquals("matched-response", cache.take(10))
    }

    @Test
    fun concurrentRequestErrorsRemainCseqScoped() {
        val cache = B233RtspResponseHeadCache()
        val begin = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                check(begin.await(5, TimeUnit.SECONDS))
                cache.recordIfError(101, "RTSP/1.0 401 Unauthorized", "first-request", true)
            }
            val second = executor.submit {
                check(begin.await(5, TimeUnit.SECONDS))
                cache.recordIfError(202, "RTSP/1.0 403 Forbidden", "second-request", true)
            }
            begin.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals("first-request", cache.take(101))
            assertEquals("second-request", cache.take(202))
            assertEquals(0, cache.retainedCount())
        } finally {
            begin.countDown()
            executor.shutdownNow()
        }
    }
}
