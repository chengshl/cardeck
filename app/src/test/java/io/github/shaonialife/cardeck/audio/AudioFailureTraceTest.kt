package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFailureTraceTest {
    @Test fun zeroOriginFirstRtpIsLatchedExactlyOnce() {
        var now = 0L
        val logs = mutableListOf<String>()
        val trace = AudioFailureTrace(logs::add) { now }
        trace.event("before_rtp")
        assertTrue(logs.last().contains("sinceFirstRtpMs=not_started"))

        trace.firstRtpSent() // legitimate System.nanoTime() value of zero
        now = 20_215_000_000L
        trace.firstRtpSent() // must not replace the original first-RTP timestamp
        trace.event("rtsp_peer_eof")
        assertEquals(1, logs.count { it.contains("event=first_rtp_sent") })
        assertTrue(logs.last().contains("sinceSetupMs=20215 sinceFirstRtpMs=20215"))
    }

    @Test fun negativeOriginPreservesFirstRtpToEofDelta() {
        var now = -5_000_000_000L
        val logs = mutableListOf<String>()
        val trace = AudioFailureTrace(logs::add) { now }
        now = -4_500_000_000L
        trace.firstRtpSent()
        now = 15_715_000_000L
        trace.event("rtsp_peer_eof")
        assertTrue(logs.last().contains("sinceSetupMs=20715 sinceFirstRtpMs=20215"))
    }
}
