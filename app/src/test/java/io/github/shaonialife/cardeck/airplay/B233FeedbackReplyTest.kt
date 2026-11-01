package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233FeedbackReplyTest {
    @Test fun genuineRtsp200CanUpdateFeedbackLivenessButDoesNotProveOwnership() {
        listOf("RTSP/1.0 200", "RTSP/1.0 200 OK", "RTSP/1.0 200\tOK").forEach {
            assertTrue(it, B233FeedbackReply.isTransportAccepted(it, true))
        }
    }

    @Test fun peerFailureContaining200CannotAdvanceB233FeedbackLiveness() {
        listOf(
            "RTSP/1.0 500 200 OK",
            "RTSP/1.0 403 200 retry",
            "RTSP/1.0 1200 OK",
            "RTSP/1.0 2000 OK",
            "HTTP/1.1 200 OK",
            "prefix RTSP/1.0 200 OK",
            "RTSP/1.0 200 OK\r\nSecret: value",
        ).forEach { assertFalse(it, B233FeedbackReply.isTransportAccepted(it, true)) }
    }

    @Test fun b233FeedbackLogsNeverEchoPeerReasonOrInjectedHeaders() {
        val secret = "private-car-id"
        assertEquals("500", B233FeedbackReply.safeStatus("RTSP/1.0 500 200 $secret", true))
        assertEquals("403", B233FeedbackReply.safeStatus("RTSP/1.0 403 $secret", true))
        assertEquals("other", B233FeedbackReply.safeStatus("bad $secret", true))
        assertFalse(B233FeedbackReply.safeStatus("RTSP/1.0 200 OK\r\nX-Secret: $secret", true).contains(secret))
    }

    @Test fun nonB233FeedbackAcceptanceAndLogTextRemainUnchanged() {
        val legacy = "RTSP/1.0 500 200 receiver-reason"
        assertTrue(B233FeedbackReply.isTransportAccepted(legacy, false))
        assertEquals(legacy, B233FeedbackReply.safeStatus(legacy, false))
        assertFalse(B233FeedbackReply.isTransportAccepted("RTSP/1.0 500 error", false))
    }
}
