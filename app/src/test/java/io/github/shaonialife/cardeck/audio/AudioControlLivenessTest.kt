package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioControlLivenessTest {
    @Test fun emptySnapshotIsExplicitlyUnknown() {
        val health = AudioControlLiveness { 1_000_000_000L }
        val s = health.summary()
        assertTrue(s.contains("eventRx=0 eventAck=0 eventAckFail=0 eventAgeMs=never"))
        assertTrue(s.contains("feedback=0 feedbackAgeMs=never"))
        assertTrue(s.contains("sampleFirst=-1 sampleLast=-1"))
    }

    @Test fun advancingFeedbackClockAndAckAreIndependentlyCounted() {
        var now = 1_000_000_000L
        val health = AudioControlLiveness { now }
        health.onEventRequest()
        health.onEventAck(true)
        health.onFeedback(null) // SETUP first response has no sampleTime
        now += 1_000_000_000L
        health.onFeedback(0L)
        health.onFeedback(48_384L)
        health.onFeedback(48_384L)
        health.onEventRequest()
        health.onEventAck(false)
        now += 2_000_000_000L
        val s = health.summary()
        assertTrue(s.contains("eventRx=2 eventAck=1 eventAckFail=1"))
        assertTrue(s.contains("eventAgeMs=2000 ackAgeMs=3000"))
        assertTrue(s.contains("feedback=4 feedbackAgeMs=2000"))
        assertTrue(s.contains("sampleFirst=0 sampleLast=48384"))
        assertTrue(s.contains("sampleAdvance=1 sampleStall=1"))
    }

    @Test fun playbackClockResetIsNotFalselyReportedAsStall() {
        var now = 7_000_000_000L
        val health = AudioControlLiveness { now }
        health.onFeedback(900_000L)
        now += 100_000_000L
        health.onFeedback(0L)
        val s = health.summary()
        assertTrue(s.contains("sampleFirst=900000 sampleLast=0"))
        assertTrue(s.contains("sampleAdvance=0 sampleStall=0"))
    }
}
