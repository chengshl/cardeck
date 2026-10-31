package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmCaptureFrameWindowTest {
    @Test
    fun partialReadsMustAccumulateBeforeAnyRtpTimestampOrSequenceProgress() {
        val frame = PcmCaptureFrameWindow(1408)
        assertEquals(0, frame.offset)
        assertEquals(1408, frame.remaining)
        assertFalse(frame.accept(100))
        assertEquals(100, frame.offset)
        assertEquals(1308, frame.remaining)
        assertFalse(frame.accept(1100))
        assertEquals(1200, frame.offset)
        assertTrue(frame.accept(208))
        assertEquals(0, frame.offset)
        assertEquals(1408, frame.remaining)
        assertFalse(frame.accept(8))
    }

    @Test
    fun oneCompleteReadProducesExactlyOneCompleteFrame() {
        val frame = PcmCaptureFrameWindow(1408)
        assertTrue(frame.accept(1408))
        assertEquals(0, frame.pendingBytes)
        assertTrue(frame.accept(1408))
    }

    @Test
    fun temporaryZeroReadCannotAdvanceOrLosePendingSamples() {
        val frame = PcmCaptureFrameWindow(1408)
        assertFalse(frame.accept(456))
        assertFalse(frame.accept(0))
        assertEquals(456, frame.pendingBytes)
        assertTrue(frame.accept(952))
    }

    @Test
    fun captureErrorDropsOnlyIncompleteWindowAndNeverSplicesOldAndNewAudio() {
        val frame = PcmCaptureFrameWindow(1408)
        assertFalse(frame.accept(700))
        frame.discard()
        assertEquals(0, frame.pendingBytes)
        assertFalse(frame.accept(700))
        assertTrue(frame.accept(708))
    }

    @Test
    fun numericSummaryShowsWhetherShortReadsReallyHappenedWithoutSamples() {
        val frame = PcmCaptureFrameWindow(1408)
        assertFalse(frame.accept(100))
        assertFalse(frame.accept(1100))
        assertFalse(frame.accept(0))
        assertTrue(frame.accept(208))
        assertEquals(2L, frame.shortReadCount)
        assertEquals(1L, frame.zeroReadCount)
        assertEquals(1L, frame.completedFrameCount)
        assertEquals(0L, frame.discardedByteCount)
        assertEquals(0L, frame.errorResetCount)
        assertEquals(
            "shortReads=2 zeroReads=1 completeFrames=1 errorResets=0 discardedBytes=0 pendingBytes=0",
            frame.diagnosticSummary(),
        )
        assertFalse(frame.diagnosticSummary().contains("sample"))
        assertFalse(frame.diagnosticSummary().contains("password"))
    }

    @Test
    fun errorResetSummaryCountsOnlyActuallyDiscardedPartialBytes() {
        val frame = PcmCaptureFrameWindow(1408)
        assertFalse(frame.accept(700))
        frame.discard()
        assertEquals(700L, frame.discardedByteCount)
        assertEquals(1L, frame.errorResetCount)
        assertEquals(0, frame.pendingBytes)
        assertTrue(frame.accept(1408))
        frame.discard()
        assertEquals(700L, frame.discardedByteCount)
        assertEquals(2L, frame.errorResetCount)
        assertEquals(1L, frame.completedFrameCount)
        assertTrue(frame.diagnosticSummary().contains("discardedBytes=700 pendingBytes=0"))
    }

    @Test
    fun readerCannotOverfillFrameOrAcceptInvalidSizes() {
        val frame = PcmCaptureFrameWindow(1408)
        assertFalse(frame.accept(700))
        assertThrows(IllegalArgumentException::class.java) { frame.accept(709) }
        assertThrows(IllegalArgumentException::class.java) { frame.accept(-1) }
        assertEquals(700, frame.pendingBytes)
        assertTrue(frame.accept(708))
        assertThrows(IllegalArgumentException::class.java) { PcmCaptureFrameWindow(0) }
    }
}
