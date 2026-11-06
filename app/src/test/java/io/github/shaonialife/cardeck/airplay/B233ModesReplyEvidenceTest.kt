package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233ModesReplyEvidenceTest {
    @Test fun emptyTransportResponseNeverBecomesGrant() {
        assertEquals(
            "bodyBytes=0 format=empty ownership=unverified",
            B233ModesReplyEvidence.summarize(byteArrayOf()),
        )
    }

    @Test fun boundedResourceMapRecordsNumbersNotIdentityOrGrant() {
        val reply = BinaryPlist.encode(
            linkedMapOf<String, Any?>(
                "params" to linkedMapOf(
                    "resources" to listOf(
                        linkedMapOf("resourceID" to 2L, "entity" to 1L, "receiverName" to "PRIVATE_DEVICE"),
                        linkedMapOf("resourceID" to 1L, "entity" to 1L),
                    ),
                    "status" to 0L,
                    "privateToken" to "DO_NOT_LOG",
                ),
            ),
        )
        val summary = B233ModesReplyEvidence.summarize(reply)
        assertTrue(summary.contains("format=bplist_map"))
        assertTrue(summary.contains("resources=2"))
        assertTrue(summary.contains("resourceIds=audio,screen"))
        assertTrue(summary.contains("statusNumber=0"))
        assertTrue(summary.contains("ownership=unverified"))
        assertFalse(summary.contains("PRIVATE_DEVICE"))
        assertFalse(summary.contains("DO_NOT_LOG"))
        assertFalse(summary.contains("entity=1"))
    }

    @Test fun unknownPayloadAndMalformedPlistAreSafelyCategorized() {
        assertEquals(
            "bodyBytes=11 format=non_bplist ownership=unverified",
            B233ModesReplyEvidence.summarize("secret=abcd".toByteArray()),
        )
        assertEquals(
            "bodyBytes=8 format=bplist_invalid ownership=unverified",
            B233ModesReplyEvidence.summarize("bplist00".toByteArray()),
        )
    }

    @Test fun oversizedBodyNeverParsedOrEchoed() {
        val summary = B233ModesReplyEvidence.summarize(ByteArray(4_097) { 0x41 })
        assertEquals("bodyBytes=4097 format=oversize ownership=unverified", summary)
    }

    @Test fun arbitraryStatusOrResourceNumbersNotEchoed() {
        val reply = BinaryPlist.encode(
            mapOf("status" to 123456789L, "resources" to listOf(mapOf("resourceID" to 987654321L))),
        )
        val summary = B233ModesReplyEvidence.summarize(reply)
        assertTrue(summary.contains("resourceIds=none"))
        assertTrue(summary.contains("statusNumber=unknown"))
        assertFalse(summary.contains("123456789"))
        assertFalse(summary.contains("987654321"))
    }
}
