package io.github.shaonialife.cardeck.video

import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests: no Android Looper, network, MediaCodec, or BLE side effects. */
class VideoFrameDiagnosticsTest {
    private val FIRST = VideoFrameDiagnostics.Signal.FIRST_FRAME_FLUSHED
    private val ZERO = VideoFrameDiagnostics.Signal.NO_FIRST_FRAME_6S
    private val GAP = VideoFrameDiagnostics.Signal.NO_FRAMES_15S

    @Test fun keepAliveAloneDoesNotCountAsAFrameOrResetTheFirstFrameDeadline() {
        val x = VideoFrameDiagnostics(10_000)
        assertNull(x.keepAliveFlushed(10_000))
        assertNull(x.keepAliveFlushed(15_999))
        assertEquals(ZERO, x.keepAliveFlushed(16_000))
        assertNull(x.keepAliveFlushed(30_000))
        assertEquals(20_000, x.ageMs(30_000))
    }

    @Test fun firstFrameRequiresSuccessfulFlushAndCanArriveAfterZeroFrameAlert() {
        val x = VideoFrameDiagnostics(100)
        assertEquals(ZERO, x.keepAliveFlushed(6_101))
        assertEquals(FIRST, x.frameFlushed(6_150))
        assertNull(x.frameFlushed(6_151))
        assertNull(x.keepAliveFlushed(12_000))
        assertEquals(6_050, x.ageMs(6_150))
    }

    @Test fun anExistingFrameDoesNotHideFifteenSecondStall() {
        val x = VideoFrameDiagnostics(500)
        assertEquals(FIRST, x.frameFlushed(900))
        assertNull(x.keepAliveFlushed(15_899))
        assertEquals(GAP, x.keepAliveFlushed(15_900))
        assertNull(x.keepAliveFlushed(30_900))
        assertEquals(15_000, x.gapMs(15_900))
    }

    @Test fun newFramesRearmStallAlertButNeverRepeatFirstFrameEvent() {
        val x = VideoFrameDiagnostics(0)
        assertEquals(FIRST, x.frameFlushed(10))
        assertEquals(GAP, x.keepAliveFlushed(15_010))
        assertNull(x.frameFlushed(15_020))
        assertNull(x.keepAliveFlushed(15_021))
        assertEquals(GAP, x.keepAliveFlushed(30_020))
    }

    @Test fun aNewSenderHasAnIndependentFirstFrameClock() {
        val old = VideoFrameDiagnostics(0)
        assertEquals(ZERO, old.keepAliveFlushed(6_000))
        val fresh = VideoFrameDiagnostics(10_000)
        assertNull(fresh.keepAliveFlushed(10_001))
        assertEquals(FIRST, fresh.frameFlushed(10_005))
        assertNull(fresh.keepAliveFlushed(50_000 - 39_000))
    }

    @Test fun elapsedDiagnosticsNeverProduceNegativeDurationsOnClockAnomaly() {
        val x = VideoFrameDiagnostics(1000)
        assertEquals(0, x.ageMs(990))
        assertEquals(0, x.gapMs(900))
        assertNull(x.keepAliveFlushed(900))
    }
}
