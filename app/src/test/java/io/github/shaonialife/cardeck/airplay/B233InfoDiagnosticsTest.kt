package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233InfoDiagnosticsTest {
    @Test fun peerSuppliedStringsAndBinaryNeverReachInfoSummary() {
        val secret = "synthetic-credential-never-log"
        val host = "192.0.2.99"
        val info = mapOf(
            secret to secret,
            "name" to "receiver-" + secret,
            "deviceID" to host,
            "displays" to listOf(mapOf("uuid" to secret, "widthPixels" to 1920)),
            "hidDevices" to listOf(mapOf("uuid" to secret, "hidDescriptor" to secret.toByteArray())),
            "audioFormats" to listOf(mapOf("type" to 100, "audioType" to secret,
                "audioOutputFormats" to (1L shl 15), "audioInputFormats" to 7L)),
            "audioLatencies" to listOf(mapOf("type" to 100, "audioType" to secret,
                "inputLatencyMicros" to 1200L, "outputLatencyMicros" to 2400L)),
        )
        val lines = B233InfoDiagnostics.summary(info)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("hidEntries=1 audioFormats=1"))
        assertTrue(lines[1].contains("type=100 audioType=? output=8000 input=7"))
        assertTrue(lines[2].contains("in=1200 out=2400"))
        for (line in lines) {
            assertFalse(line, line.contains(secret))
            assertFalse(line, line.contains(host))
            assertFalse(line, line.contains("uuid"))
            assertFalse(line, line.contains("deviceID"))
        }
    }

    @Test fun forgedStatusAndParserExceptionDoNotEchoPeerText() {
        val secret = "synthetic-private-peer-reason"
        val response = B233InfoDiagnostics.response("侧信道 RTSP/1.0 500 " + secret, 42)
        val failure = B233InfoDiagnostics.parseFailure(
            IllegalArgumentException("remote=" + secret), 84,
        )
        assertTrue(response.contains("status=500 bodyBytes=42"))
        assertTrue(failure.contains("type=IllegalArgumentException bodyBytes=84"))
        assertFalse(response.contains(secret))
        assertFalse(failure.contains(secret))
    }

    @Test fun capabilityLogIsBoundedEvenForLargeReceiverLists() {
        val many = (0 until 100).map { mapOf("type" to it, "audioOutputFormats" to it.toLong()) }
        val lines = B233InfoDiagnostics.summary(mapOf("audioFormats" to many, "audioLatencies" to many))
        assertEquals(33, lines.size)
        assertTrue(lines.first().contains("audioFormats=100"))
    }
}
