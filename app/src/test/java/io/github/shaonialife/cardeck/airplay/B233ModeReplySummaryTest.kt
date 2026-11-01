package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class B233ModeReplySummaryTest {
    @Test fun b233ModeClaimRequiresExactRtsp200NotReasonPhrase() {
        for (valid in listOf("RTSP/1.0 200", "RTSP/1.0 200 OK", "RTSP/1.0 200\tOK")) {
            assertTrue(valid, B233ModeReplySummary.isTransportAccepted(valid, true))
        }
        for (invalid in listOf(
            "RTSP/1.0 500 200 OK", "RTSP/1.0 403 200 private",
            "RTSP/1.0 1200 OK", "RTSP/1.0 2000 OK",
            "HTTP/1.1 200 OK", "prefix RTSP/1.0 200 OK",
            "RTSP/1.0 200 OK\r\nInjected: secret",
        )) {
            assertFalse(invalid, B233ModeReplySummary.isTransportAccepted(invalid, true))
        }
    }

    @Test fun legacyNonB233ModeClaimDecisionRemainsUnchanged() {
        assertTrue(B233ModeReplySummary.isTransportAccepted("RTSP/1.0 500 200 OK", false))
        assertTrue(B233ModeReplySummary.isTransportAccepted("HTTP/1.1 200 OK", false))
        assertFalse(B233ModeReplySummary.isTransportAccepted("RTSP/1.0 403 Forbidden", false))
    }

    @Test fun b233ModeStatusAndFailureLogsDoNotEchoPeerText() {
        val secret = "synthetic-credential"
        assertEquals("403", B233ModeReplySummary.safeTransportStatus("RTSP/1.0 403 $secret", true))
        assertEquals("other", B233ModeReplySummary.safeTransportStatus("bad $secret", true))
        assertEquals("IOException", B233ModeReplySummary.safeTransportError(
            java.io.IOException("remote reason=$secret"), true))
        assertEquals("RTSP/1.0 403 $secret",
            B233ModeReplySummary.safeTransportStatus("RTSP/1.0 403 $secret", false))
        assertEquals("remote reason=$secret",
            B233ModeReplySummary.safeTransportError(java.io.IOException("remote reason=$secret"), false))
    }

    @Test fun emptyReplyDoesNotClaimResourceAcceptance() {
        assertEquals("body=empty ownership=unverified", B233ModeReplySummary.describe(byteArrayOf()))
    }

    @Test fun replyIncludesOnlyAllowlistedNumericAndBooleanShape() {
        val secret = "wireless-private-credential"
        val encoded = BinaryPlist.encode(mapOf(
            "type" to "modesChanged",
            "status" to 200L,
            "accepted" to true,
            "secret" to secret,
            "params" to mapOf(
                "resources" to listOf(mapOf("resourceID" to 2L, "entity" to 1L,
                    "transferType" to 3L, "transferPriority" to 500L,
                    "reasonStr" to secret, "uuid" to secret)),
                "appStates" to listOf(mapOf("private" to secret)),
            ),
        ))
        val result = B233ModeReplySummary.describe(encoded)
        assertTrue(result.contains("topStatus=200"))
        assertTrue(result.contains("topAccepted=true"))
        assertTrue(result.contains("resourceID=2,entity=1,transferType=3,transferPriority=500"))
        assertTrue(result.contains("ownership=unverified"))
        assertFalse(result.contains(secret))
        assertFalse(result.contains("uuid"))
        assertFalse(result.contains("reasonStr"))
    }

    @Test fun unknownFieldsAndUnknownTypeAreNeverEchoed() {
        val value = BinaryPlist.encode(mapOf(
            "type" to "device-secret-session-id",
            "reasonStr" to "secret",
            "privateAddress" to "192.0.2.1",
            "status" to 9_999_999L,
        ))
        val result = B233ModeReplySummary.describe(value)
        assertTrue(result.contains("type=other-or-absent"))
        assertTrue(result.contains("topStatus=out-of-range"))
        assertFalse(result.contains("device-secret"))
        assertFalse(result.contains("192.0.2.1"))
        assertFalse(result.contains("secret"))
    }

    @Test fun invalidAndOversizePayloadsAreBoundedAndNonfatal() {
        val invalid = B233ModeReplySummary.describe("not plist".toByteArray())
        assertTrue(invalid.contains("unrecognized"))
        assertTrue(invalid.contains("unverified"))
        val huge = B233ModeReplySummary.describe(ByteArray(4097) { 0x61.toByte() })
        assertEquals("body=over-limit ownership=unverified", huge)
    }

    @Test fun resourceListIsCappedAndNeverEchoesUnknownMap() {
        val value = BinaryPlist.encode(mapOf(
            "params" to mapOf("resources" to (0..15).map { idx ->
                mapOf("resourceID" to idx.toLong(), "password" to "do-not-log")
            }),
        ))
        val out = B233ModeReplySummary.describe(value)
        assertTrue(out.contains("count=16["))
        assertFalse(out.contains("resourceID=5"))
        assertFalse(out.contains("do-not-log"))
        assertTrue(out.length < 550)
    }
}
