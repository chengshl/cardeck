package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only, never interpreted as receiver evidence. */
class ModeTransactionEvidenceTest {
    @Test fun structuralResourceRequestDoesNotAssertOwnership() {
        val fixture = mapOf(
            "type" to "changeModes",
            "params" to mapOf("resources" to listOf(
                mapOf("resourceID" to 1L, "transferType" to 1L),
                mapOf("resourceID" to 2L, "transferType" to 3L, "borrowConstraint" to 2L),
            )),
        )
        val decoded = BinaryPlist.parse(BinaryPlist.encode(fixture)) as Map<*, *>
        val x = ModeTransactionEvidence.inspectRequest(decoded)
        assertEquals(ModeTransactionEvidence.RequestShape.RESOURCES_LIST, x.shape)
        assertEquals(2L, x.resources[1].knownResourceId)
        assertEquals(3L, x.resources[1].transferTypeCode)
        assertTrue(x.resources[1].hasBorrowConstraint)
        assertTrue(x.mentionsMainAudio)
    }

    @Test fun missingResourcesAreNotAnAudioRequest() {
        val x = ModeTransactionEvidence.inspectRequest(mapOf(
            "type" to "changeModes",
            "params" to mapOf("appStates" to emptyList<Any>()),
        ))
        assertEquals(ModeTransactionEvidence.RequestShape.NO_RESOURCES, x.shape)
        assertTrue(x.hasAppStates)
        assertFalse(x.mentionsMainAudio)
    }

    @Test fun unknownValuesAreNotMisclassifiedAsAudioOwnership() {
        val x = ModeTransactionEvidence.inspectRequest(mapOf(
            "type" to "changeModes",
            "params" to mapOf("resources" to listOf(
                mapOf("resourceID" to 200L, "transferType" to 999L),
                "unexpected entry",
            )),
        ))
        assertNull(x.resources[0].knownResourceId)
        assertNull(x.resources[0].transferTypeCode)
        assertNull(x.resources[1].knownResourceId)
        assertFalse(x.mentionsMainAudio)
    }

    @Test fun emptyHttp200DoesNotProveApplicationAcceptance() {
        val x = ModeTransactionEvidence.inspectResponse("RTSP/1.0 200 OK", byteArrayOf())
        assertTrue(x.transportOk)
        assertEquals(ModeTransactionEvidence.ResponseShape.EMPTY, x.bodyShape)
        assertNull(x.statusField)
        assertFalse(x.applicationAcceptanceVerified)
    }

    @Test fun statusValueHasNoAssumedSuccessMeaning() {
        val body = BinaryPlist.encode(mapOf("status" to 7L))
        val x = ModeTransactionEvidence.inspectResponse("RTSP/1.0 200 OK", body)
        assertEquals(ModeTransactionEvidence.ResponseShape.PLIST_MAP, x.bodyShape)
        assertEquals(7L, x.statusField)
        assertFalse(x.applicationAcceptanceVerified)
    }

    @Test fun badBodyAndRejectedTransportAreSeparate() {
        val rejected = ModeTransactionEvidence.inspectResponse("RTSP/1.0 400 Bad Request", byteArrayOf())
        assertFalse(rejected.transportOk)
        val malformed = ModeTransactionEvidence.inspectResponse("RTSP/1.0 200 OK", "invalid".toByteArray())
        assertEquals(ModeTransactionEvidence.ResponseShape.UNPARSEABLE, malformed.bodyShape)
        assertFalse(malformed.applicationAcceptanceVerified)
    }
}
