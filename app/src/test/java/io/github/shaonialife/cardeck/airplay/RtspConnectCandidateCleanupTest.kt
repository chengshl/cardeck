package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

/** Offline ownership tests: no real TCP, vehicle or device data. */
class RtspConnectCandidateCleanupTest {
    @Test
    fun failedB233ConnectClosesCandidateBeforeRetry() {
        val candidate = Socket()
        var propagated = false
        try {
            RtspConnectCandidateCleanup.publishOrClose(
                candidate, cleanupOnFailure = true,
            ) {
                throw IOException("synthetic connect failure")
            }
        } catch (_: IOException) {
            propagated = true
        }
        assertTrue("connect exception must propagate to the existing retry path", propagated)
        assertTrue("failed B233 TCP candidate must be closed", candidate.isClosed)
    }

    @Test
    fun rejectedPublicationClosesCandidateWithoutNetwork() {
        val candidate = Socket()
        try {
            assertFalse(
                RtspConnectCandidateCleanup.publishOrClose(
                    candidate, cleanupOnFailure = true,
                ) { false },
            )
            assertTrue("endpoint-change candidate must be closed", candidate.isClosed)
        } finally {
            candidate.close()
        }
    }

    @Test
    fun successfulPublicationRetainsLiveSocketOwnership() {
        val candidate = Socket()
        try {
            assertTrue(
                RtspConnectCandidateCleanup.publishOrClose(
                    candidate, cleanupOnFailure = true,
                ) { true },
            )
            assertFalse("published socket must stay available to its reader", candidate.isClosed)
        } finally {
            candidate.close()
        }
    }

    @Test
    fun nonB233ExceptionPathRemainsUnchanged() {
        val candidate = Socket()
        try {
            var propagated = false
            try {
                RtspConnectCandidateCleanup.publishOrClose(
                    candidate, cleanupOnFailure = false,
                ) {
                    throw IOException("synthetic legacy connect failure")
                }
            } catch (_: IOException) {
                propagated = true
            }
            assertTrue(propagated)
            assertFalse("non-B233 exception path must not change in this L3 trial", candidate.isClosed)
        } finally {
            candidate.close()
        }
    }
}
