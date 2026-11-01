package io.github.shaonialife.cardeck.airplay

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class B233SetupDiagnosticsTest {
    @Test fun b233InitialSetupDiagnosticsNeverEchoIdentifiersOrPeerReason() {
        val secret = "synthetic-peer-credential"
        val host = "192.0.2.55"
        val lines = listOfNotNull(
            B233SetupDiagnostics.attempt(256, 12345, true),
            B233SetupDiagnostics.failure(IOException("remote=$secret host=$host")),
            B233SetupDiagnostics.rejection("RTSP/1.0 403 $secret $host", 73, 2),
            B233SetupDiagnostics.forbiddenHint("RTSP/1.0 403 $secret"),
        )
        assertEquals(4, lines.size)
        for (line in lines) {
            assertFalse(line, line.contains(secret))
            assertFalse(line, line.contains(host))
            assertFalse(line, line.contains("deviceID="))
            assertFalse(line, line.contains("plistKeys="))
            assertFalse(line, line.contains("connectedHost="))
        }
        assertTrue(lines[0].contains("body=256B"))
        assertTrue(lines[1].contains("type=IOException"))
        assertTrue(lines[2].contains("status=403 responseBytes=73 candidateCount=2"))
    }

    @Test fun onlyExact403GetsDeviceFilterHint() {
        assertNull(B233SetupDiagnostics.forbiddenHint("RTSP/1.0 500 403 private"))
        assertNull(B233SetupDiagnostics.forbiddenHint("RTSP/1.0 200 OK"))
        assertNull(B233SetupDiagnostics.forbiddenHint("invalid 403"))
        assertTrue(B233SetupDiagnostics.forbiddenHint("RTSP/1.0 403 Forbidden")!!.contains("identity redacted"))
    }

    @Test fun forgedSuccessInPeerReasonNeverLeaksAndRemainsRejected() {
        val status = "RTSP/1.0 500 200 synthetic-private-text"
        assertFalse(B233ModeReplySummary.isTransportAccepted(status, true))
        val summary = B233SetupDiagnostics.rejection(status, 42, 1)
        assertTrue(summary.contains("status=500"))
        assertFalse(summary.contains("200 synthetic-private-text"))
    }
}
