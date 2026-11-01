package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class B233AudioSetupReplyTest {
    @Test fun validRtsp200MeansTransportOnlyNeverAudioResourceOwnership() {
        listOf("RTSP/1.0 200", "RTSP/1.0 200 OK", "RTSP/1.0 200\tOK").forEach {
            assertTrue(it, B233AudioSetupReply.isTransportAccepted(it, true))
        }
    }

    @Test fun b233RejectsPeerReasonTextWithFakeSuccessNumber() {
        listOf(
            "RTSP/1.0 500 200 OK",
            "RTSP/1.0 403 200 secret",
            "RTSP/1.0 404 retry 200",
            "RTSP/1.0 1200 OK",
            "RTSP/1.0 2000 OK",
            "HTTP/1.1 200 OK",
            "prefix RTSP/1.0 200 OK",
            "RTSP/1.0 200 OK\r\nInjected: secret",
        ).forEach { assertFalse(it, B233AudioSetupReply.isTransportAccepted(it, true)) }
    }

    @Test fun peerSuppliedErrorReasonAndExceptionMessageAreRedacted() {
        val secret = "wifi-private-credential"
        assertEquals("403", B233AudioSetupReply.safeStatus("RTSP/1.0 403 $secret", true))
        assertEquals("500", B233AudioSetupReply.safeStatus("RTSP/1.0 500 200 $secret", true))
        assertEquals("other", B233AudioSetupReply.safeStatus("bad $secret", true))
        assertEquals("IOException", B233AudioSetupReply.safeError(IOException(secret), true))
        assertFalse(B233AudioSetupReply.safeStatus("RTSP/1.0 403 $secret", true).contains(secret))
    }

    @Test fun nonB233AcceptanceAndDiagnosticsRemainLegacy() {
        val old = "RTSP/1.0 500 200 reason"
        assertTrue(B233AudioSetupReply.isTransportAccepted(old, false))
        assertTrue(B233AudioSetupReply.isTransportAccepted("HTTP/1.1 200 OK", false))
        assertFalse(B233AudioSetupReply.isTransportAccepted("RTSP/1.0 403 Forbidden", false))
        assertEquals(old, B233AudioSetupReply.safeStatus(old, false))
        assertEquals("old-error", B233AudioSetupReply.safeError(IOException("old-error"), false))
    }

    @Test fun bogus200CannotMarkPotentiallyCreatedStream() {
        val bad = "RTSP/1.0 500 200 challenge"
        assertFalse(B233AudioSetupReply.isTransportAccepted(bad, true))
        assertTrue(B233AudioSetupReply.isTransportAccepted(bad, false))
        assertTrue(B233AudioSetupReply.isTransportAccepted("RTSP/1.0 200 OK", true))
    }
}
