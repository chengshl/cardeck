package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.audio.AudioControlLiveness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T86 offline-only evidence fixtures; no real vehicle payload or identifiers.
 * T85 observed PCM type=100 sampleTime increasing from 0 to 914352 before
 * peer EOF, while T82's type=102-only summary incorrectly displayed -1.
 */
class AudioFeedbackSampleTimeTest {
    private fun feedback(type: Number, sample: Any?): Map<String, Any> =
        mapOf("streams" to listOf(mapOf("type" to type, "sampleTime" to sample)))

    @Test fun pcmType100ClockAdvancesInObservedT85Sequence() {
        val observed = listOf(
            0L, 43_440L, 91_824L, 140_208L, 188_592L, 236_976L,
            285_360L, 333_744L, 382_128L, 430_512L, 478_896L,
            527_280L, 575_664L, 624_048L, 672_432L, 720_816L,
            769_200L, 817_584L, 865_968L, 914_352L,
        )
        val health = AudioControlLiveness { 1_000_000_000L }
        for (sample in observed) {
            // Round-trip via real plist format; this isn't merely a Map fixture.
            val parsed = BinaryPlist.parse(BinaryPlist.encode(feedback(100L, sample))) as Map<*, *>
            val extracted = AudioFeedbackSampleTime.fromParsedFeedback(parsed)
            assertEquals(sample, extracted)
            health.onFeedback(extracted)
        }
        val summary = health.summary()
        assertTrue(summary.contains("feedback=20"))
        assertTrue(summary.contains("sampleFirst=0 sampleLast=914352"))
        assertTrue(summary.contains("sampleAdvance=19 sampleStall=0"))
    }

    @Test fun aacType102RemainsSupportedAndWinsWhenBothArePresent() {
        val parsed = mapOf(
            "streams" to listOf(
                mapOf("type" to 100L, "sampleTime" to 48000L),
                mapOf("type" to 102, "sampleTime" to 96000L),
            ),
        )
        assertEquals(96000L, AudioFeedbackSampleTime.fromParsedFeedback(parsed))
        assertEquals(12345L, AudioFeedbackSampleTime.fromParsedFeedback(feedback(102L, 12345L)))
    }

    @Test fun invalidAacClockFallsBackToValidPcmClock() {
        val parsed = mapOf(
            "streams" to listOf(
                mapOf("type" to 102L, "sampleTime" to "bad"),
                mapOf("type" to 100L, "sampleTime" to 22400L),
            ),
        )
        assertEquals(22400L, AudioFeedbackSampleTime.fromParsedFeedback(parsed))
    }

    @Test fun noAudioClockDoesNotFalselyReportSuccessOrStall() {
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(null))
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(mapOf("streams" to listOf("noise"))))
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(feedback(110L, 1000L)))
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(feedback(100L, -1L)))
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(feedback(100L, "0")))
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(mapOf("streams" to "bad")))
    }

    @Test fun realValuedOrNonFinitePlistNumbersCannotInventAPlaybackClock() {
        // BinaryPlist decodes real objects as Double, not Long. Truncating
        // NaN to 0 or 1.5 to 1 would fabricate a valid receiver sampleTime.
        val invalidSamples = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            1.5, 100.0, Double.MAX_VALUE,
        )
        for (invalid in invalidSamples) {
            val roundTrip = BinaryPlist.parse(
                BinaryPlist.encode(feedback(100L, invalid)),
            ) as Map<*, *>
            assertNull("invalid sampleTime=$invalid", AudioFeedbackSampleTime.fromParsedFeedback(roundTrip))
        }
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(feedback(100.0, 48_000L)))
        assertNull(AudioFeedbackSampleTime.fromParsedFeedback(feedback(102.5, 48_000L)))

        // A malformed AAC clock must not hide a valid PCM clock.
        val mixed = mapOf(
            "streams" to listOf(
                mapOf("type" to 102L, "sampleTime" to Double.NaN),
                mapOf("type" to 100L, "sampleTime" to 48_384L),
            ),
        )
        assertEquals(48_384L, AudioFeedbackSampleTime.fromParsedFeedback(mixed))
    }

    @Test fun malformedEntriesDoNotPreventSelectingAnotherAudioStream() {
        val parsed = mapOf(
            "streams" to listOf(
                null,
                mapOf("type" to "102", "sampleTime" to 123L),
                mapOf("type" to 100, "sampleTime" to 480L),
            ),
        )
        assertEquals(480L, AudioFeedbackSampleTime.fromParsedFeedback(parsed))
    }
}
