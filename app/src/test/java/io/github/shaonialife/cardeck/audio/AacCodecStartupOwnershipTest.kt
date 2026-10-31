package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM regression for ownership before Android MediaCodec is published. */
class AacCodecStartupOwnershipTest {
    private class FakeCodec {
        var configured = false
        var started = false
        var cleanups = 0
        fun close() { cleanups++ }
    }

    @Test fun configureFailureCleansUpUnpublishedCodec() {
        val resource = FakeCodec()
        val failure = IllegalStateException("configure failed")
        try {
            startOwnedAudioCodecResource(
                acquire = { resource },
                initialize = { throw failure },
                cleanup = { it.close() },
            )
            fail("configure exception should propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, resource.cleanups)
    }

    @Test fun startFailureAfterConfigureStillReleasesCodec() {
        val resource = FakeCodec()
        val failure = IllegalStateException("start failed")
        try {
            startOwnedAudioCodecResource(
                acquire = { resource },
                initialize = {
                    it.configured = true
                    throw failure
                },
                cleanup = { it.close() },
            )
            fail("start exception should propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertTrue(resource.configured)
        assertFalse(resource.started)
        assertEquals(1, resource.cleanups)
    }

    @Test fun successfulStartupTransfersResourceWithoutEarlyRelease() {
        val resource = FakeCodec()
        val owner = startOwnedAudioCodecResource(
            acquire = { resource },
            initialize = {
                it.configured = true
                it.started = true
            },
            cleanup = { it.close() },
        )
        assertSame(resource, owner)
        assertTrue(owner.started)
        assertEquals(0, resource.cleanups)
        owner.close()
        assertEquals(1, resource.cleanups)
    }

    @Test fun cleanupFailureCannotHideOriginalStartupFailure() {
        val resource = FakeCodec()
        val failure = IllegalArgumentException("codec initialization error")
        try {
            startOwnedAudioCodecResource(
                acquire = { resource },
                initialize = { throw failure },
                cleanup = {
                    it.close()
                    throw IllegalStateException("release failed")
                },
            )
            fail("original initialization exception must survive")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
        }
        assertEquals(1, resource.cleanups)
    }

    @Test fun acquisitionFailureHasNothingToRelease() {
        var cleaned = false
        val failure = IllegalStateException("create failed")
        try {
            startOwnedAudioCodecResource<FakeCodec>(
                acquire = { throw failure },
                initialize = { it.started = true },
                cleanup = { cleaned = true },
            )
            fail("creation exception must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertFalse(cleaned)
    }
}
