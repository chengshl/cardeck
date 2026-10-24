package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamActivationTest {
    @Test
    fun b233InitialAndLateAudioBothRecordAndReleaseSyncGate() {
        for (lateGrant in listOf(false, true)) {
            var records = 0
            var unlocked = 0
            var stopped = 0
            val result = AudioStreamActivation.finish(
                isBuickB233 = true,
                record = { records++; true },
                onConfirmed = { unlocked++ },
                onRejected = { stopped++ },
            )
            assertTrue("lateGrant=$lateGrant", result)
            assertEquals(1, records)
            assertEquals(1, unlocked)
            assertEquals(0, stopped)
        }
    }

    @Test
    fun b233RejectedRecordNeverUnlocksAndStopsFailedSender() {
        var unlocked = 0
        var stopped = 0
        val result = AudioStreamActivation.finish(
            isBuickB233 = true,
            record = { false },
            onConfirmed = { unlocked++ },
            onRejected = { stopped++ },
        )
        assertFalse(result)
        assertEquals(0, unlocked)
        assertEquals(1, stopped)
    }

    @Test
    fun nonBuickKeepsExistingRecordBehaviorWithoutB233SyncCallbacks() {
        var unlocked = 0
        var stopped = 0
        val result = AudioStreamActivation.finish(
            isBuickB233 = false,
            record = { true },
            onConfirmed = { unlocked++ },
            onRejected = { stopped++ },
        )
        assertTrue(result)
        assertEquals(0, unlocked)
        assertEquals(0, stopped)
    }
}
