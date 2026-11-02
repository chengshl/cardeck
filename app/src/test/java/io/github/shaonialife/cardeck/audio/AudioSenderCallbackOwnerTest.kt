package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class AudioSenderCallbackOwnerTest {
    @Test
    fun queuedOldSyncRefusalCannotMutateReplacementWithSameProfile() {
        // Both callbacks belong to the same client/profile; only sender identity differs.
        val oldSender = Any()
        val replacementSender = Any()
        val oldCallback = AudioSenderCallbackOwner().apply { bind(oldSender) }
        val newCallback = AudioSenderCallbackOwner().apply { bind(replacementSender) }
        var activeSender: Any? = oldSender
        var handled = 0
        fun dispatch(owner: AudioSenderCallbackOwner) {
            if (owner.owns(activeSender)) handled++
        }

        dispatch(oldCallback)
        assertEquals(1, handled)
        activeSender = replacementSender
        dispatch(oldCallback) // Delayed Bg.run from the retired sender.
        assertEquals(1, handled)
        dispatch(newCallback)
        assertEquals(2, handled)
    }

    @Test
    fun failedStartupFromOldSenderCannotClearReplacement() {
        val failedSender = Any()
        val replacementSender = Any()
        val failedAttemptOwner = AudioSenderCallbackOwner().apply { bind(failedSender) }
        var activeSender: Any? = replacementSender
        var cleared = 0
        fun cleanupFailedAttempt() {
            if (failedAttemptOwner.owns(activeSender)) {
                activeSender = null
                cleared++
            }
        }

        cleanupFailedAttempt() // Old start() returns false after a new sender is installed.
        assertTrue(activeSender === replacementSender)
        assertEquals(0, cleared)
        activeSender = failedSender
        cleanupFailedAttempt() // The current failed attempt still cleans itself.
        assertFalse(activeSender === failedSender)
        assertEquals(1, cleared)
    }

    @Test
    fun lateExceptionCannotClearReplacementOrDifferentSession() {
        val failedSender = Any()
        val replacementSender = Any()
        val failedOwner = AudioSenderCallbackOwner().apply { bind(failedSender) }
        var activeSender: Any? = replacementSender
        var sameClient = true
        var clears = 0
        fun cleanupOnException(owner: AudioSenderCallbackOwner?) {
            if (sameClient && owner?.owns(activeSender) == true) {
                activeSender = null
                clears++
            }
        }

        cleanupOnException(failedOwner) // The newer sender must survive.
        assertTrue(activeSender === replacementSender)
        cleanupOnException(null) // Exception before an owner was bound.
        assertTrue(activeSender === replacementSender)
        activeSender = failedSender
        sameClient = false // Old session must not clear a new session.
        cleanupOnException(failedOwner)
        assertTrue(activeSender === failedSender)
        sameClient = true
        cleanupOnException(failedOwner) // Only its own current sender may retire.
        assertEquals(null, activeSender)
        assertEquals(1, clears)
    }

    @Test
    fun asyncCaptureStopClearsOnlyItsOwnLiveSessionState() {
        val failedCapture = Any()
        val replacement = Any()
        val owner = AudioSenderCallbackOwner().apply { bind(failedCapture) }
        var activeSender: Any? = replacement
        var sameClient = true
        var activeProfile: String? = "aac"
        var startedAt = 123L
        var sessionAudioStarted = true
        fun onStopped() {
            if (sameClient && owner.owns(activeSender)) {
                activeSender = null
                activeProfile = null
                startedAt = 0L
                sessionAudioStarted = false
            }
        }

        onStopped() // Old capture worker must not retire a replacement.
        assertTrue(activeSender === replacement)
        assertEquals("aac", activeProfile)
        assertEquals(123L, startedAt)
        assertTrue(sessionAudioStarted)

        activeSender = failedCapture
        sameClient = false // A retired session cannot mutate a newer session.
        onStopped()
        assertTrue(activeSender === failedCapture)
        assertEquals("aac", activeProfile)

        sameClient = true
        onStopped() // The current stopped sender must retire its own state.
        assertEquals(null, activeSender)
        assertEquals(null, activeProfile)
        assertEquals(0L, startedAt)
        assertFalse(sessionAudioStarted)
    }

    @Test
    fun failedStartRetirementCannotRaceReplacementPublication() {
        val lock = Any()
        val failedSender = Any()
        val replacement = Any()
        val failedOwner = AudioSenderCallbackOwner().apply { bind(failedSender) }
        var activeSender: Any? = failedSender
        var profile: String? = "failed"
        var retirements = 0
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            try {
                failedOwner.retireIfCurrent(
                    lock,
                    { true },
                    { activeSender },
                ) {
                    activeSender = null
                    profile = null
                    retirements++
                }
            } finally {
                finished.countDown()
            }
        }

        synchronized(lock) {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // The failed-start cleanup must wait for this publication lock.
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            activeSender = replacement
            profile = "replacement"
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        worker.join()
        assertSame(replacement, activeSender)
        assertEquals("replacement", profile)
        assertEquals(0, retirements)

        activeSender = failedSender
        assertFalse(failedOwner.retireIfCurrent(lock, { false }, { activeSender }) {
            retirements++
        })
        assertSame(failedSender, activeSender)
        assertTrue(failedOwner.retireIfCurrent(lock, { true }, { activeSender }) {
            activeSender = null
            profile = null
            retirements++
        })
        assertEquals(null, activeSender)
        assertEquals(1, retirements)
    }

    @Test
    fun unboundNullAndRetiredSendersCannotPassOwnership() {
        val sender = Any()
        val gate = AudioSenderCallbackOwner()
        assertFalse(gate.owns(sender))
        gate.bind(sender)
        assertTrue(gate.owns(sender))
        assertFalse(gate.owns(null))
        assertFalse(gate.owns(Any()))
    }
}
