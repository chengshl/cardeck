package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPcmToneTest {
    @Test
    fun generatedSignalIsNonzeroBoundedAndStereoAligned() {
        val source = DiagnosticPcmTone(sampleRate = 48_000)
        val pcm = ByteArray(4 * 1_024)
        source.fill(pcm)
        val samples = pcm.indices.step(2).map { i ->
            val unsigned = (pcm[i].toInt() and 0xff) or ((pcm[i + 1].toInt() and 0xff) shl 8)
            if (unsigned >= 32768) unsigned - 65536 else unsigned
        }
        assertTrue(samples.any { it != 0 })
        assertTrue(samples.all { it in -1_000..1_000 })
        assertEquals(samples.size / 2, 1_024)
        for (frame in 0 until 1_024) {
            assertEquals(samples[frame * 2], samples[frame * 2 + 1])
        }
    }

    @Test
    fun frameBoundariesHaveNoPhaseReset() {
        val segmented = DiagnosticPcmTone(sampleRate = 48_000)
        val combined = DiagnosticPcmTone(sampleRate = 48_000)
        val first = ByteArray(4 * 352)
        val second = ByteArray(4 * 352)
        segmented.fill(first)
        segmented.fill(second)
        val joined = first + second
        val continuous = ByteArray(4 * 704)
        combined.fill(continuous)
        assertArrayEquals(continuous, joined)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompletePcmFrameIsRejected() {
        DiagnosticPcmTone(sampleRate = 48_000).fill(ByteArray(7))
    }
}
