package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233PairingReplySummaryTest {
    @Test fun pairingResponseDoesNotExposeHandshakeBytesOrHex() {
        val secret = "synthetic-private-pairing-material"
        val bytes = secret.toByteArray(Charsets.UTF_8)
        val summary = B233PairingReplySummary.describe("RTSP/1.0 403 $secret", bytes)
        assertEquals("status=403 respBytes=" + bytes.size + " redacted", summary)
        assertFalse(summary.contains(secret))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertFalse(summary.contains(hex))
        assertFalse(summary.contains("head="))
        assertEquals("403", B233PairingReplySummary.safeStatus("RTSP/1.0 403 $secret"))
    }

    @Test fun successRetainsOnlyBoundedStatusAndLength() {
        val summary = B233PairingReplySummary.describe("RTSP/1.0 200 OK private-id", byteArrayOf(0, 1, 2))
        assertEquals("status=200 respBytes=3 redacted", summary)
        assertFalse(summary.contains("private-id"))
    }

    @Test fun malformedPeerStatusCannotInjectLogContent() {
        val secret = "password=synthetic-secret"
        val summary = B233PairingReplySummary.describe(secret + "\nRTSP/1.0 200 OK", byteArrayOf())
        assertEquals("status=other respBytes=0 redacted", summary)
        assertFalse(summary.contains(secret))
        assertEquals("other", B233PairingReplySummary.safeStatus(secret))
        assertTrue(summary.length < 64)
    }

    @Test fun pairingSuccessRequiresExactRtsp200Code() {
        assertTrue(B233PairingReplySummary.isSuccessfulPairingStatus("RTSP/1.0 200 OK"))
        assertTrue(B233PairingReplySummary.isSuccessfulPairingStatus("RTSP/1.0 200"))
        assertTrue(B233PairingReplySummary.isSuccessfulPairingStatus("RTSP/1.0 200\tOK"))
        for (invalid in listOf(
            "RTSP/1.0 500 200 OK",
            "RTSP/1.0 1200 OK",
            "RTSP/1.0 2000 OK",
            "HTTP/1.1 200 OK",
            "RTSP/1.0 403 200 OK",
            "RTSP/1.0 200 OK\nInjected",
            "RTSP/1.0 200 OK\r\nInjected",
            "prefix RTSP/1.0 200 OK",
        )) {
            assertFalse("must reject [$invalid]", B233PairingReplySummary.isSuccessfulPairingStatus(invalid))
        }
    }
}
