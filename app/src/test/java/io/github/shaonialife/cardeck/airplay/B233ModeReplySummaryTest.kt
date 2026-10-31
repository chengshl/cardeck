package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class B233ModeReplySummaryTest {
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
