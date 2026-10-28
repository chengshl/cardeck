package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.*
import org.junit.Test

class B233ModeRequestSummaryTest {
    @Test fun recognizedNumericFieldsAreCapturedWithoutReasonOrSecrets() {
        val cmd = mapOf(
            "type" to "changeModes",
            "params" to mapOf(
                "resources" to listOf(mapOf(
                    "resourceID" to 2L, "transferType" to 3L, "transferPriority" to 500L,
                    "unborrowConstraint" to 500L,
                    "extraSecret" to "do not log",
                )),
                "appStates" to listOf(mapOf("appStateID" to 1L, "state" to true, "speechMode" to 1L)),
                "initialPermanentEntity" to listOf(mapOf("resourceID" to 2L, "permanentEntity" to 1L)),
                "reasonStr" to "private command value",
                "networkAddress" to "secret address",
            ),
        )
        val s = B233ModeRequestSummary.describe(cmd)
        assertTrue(s.contains("resourceID=2"))
        assertTrue(s.contains("transferType=3"))
        assertTrue(s.contains("transferPriority=500"))
        assertTrue(s.contains("unborrowConstraint=500"))
        assertTrue(s.contains("appStateID=1"))
        assertTrue(s.contains("permanentEntity=1"))
        assertFalse(s.contains("private"))
        assertFalse(s.contains("secret"))
        assertFalse(s.contains("networkAddress"))
        assertFalse(s.contains("do not log"))
    }

    @Test fun actualT84B233RequestIsOnlyMainScreenTakeNotMainAudio() {
        // T84 Gateway, 2026-10-28 23:59:18.421 BJT (received timestamp):
        // In the failed PCM session, B233 requested only mainScreen TAKE.
        // Same numeric request also appeared in the video-only recovery session.
        // This reconstructs the whitelisted numeric fields, NOT the original plist.
        val command = mapOf(
            "type" to "changeModes",
            "params" to mapOf(
                "resources" to listOf(
                    mapOf(
                        "resourceID" to 1L,
                        "transferType" to 1L,
                        "transferPriority" to 500L,
                        "takeConstraint" to 500L,
                        "borrowConstraint" to 100L,
                    ),
                ),
            ),
        )
        val evidence = ModeTransactionEvidence.inspectRequest(command)
        assertEquals(ModeTransactionEvidence.RequestShape.RESOURCES_LIST, evidence.shape)
        assertEquals(1, evidence.resources.size)
        assertEquals(1L, evidence.resources.single().knownResourceId)
        assertEquals(1L, evidence.resources.single().transferTypeCode)
        assertFalse(evidence.mentionsMainAudio)
        assertFalse(evidence.hasAppStates)

        val summary = B233ModeRequestSummary.describe(command)
        assertTrue(summary.contains("resourceID=1,transferType=1,transferPriority=500,takeConstraint=500,borrowConstraint=100,unborrowConstraint=-"))
        assertTrue(summary.contains("appStates=absent"))
        assertTrue(summary.contains("initialPermanent=absent"))
        assertFalse(summary.contains("resourceID=2"))
    }

    @Test fun missingMalformedAndLargeInputsStayBounded() {
        assertEquals("not-changeModes", B233ModeRequestSummary.describe(mapOf("type" to "requestUI")))
        assertEquals("params=absent-or-unknown", B233ModeRequestSummary.describe(mapOf("type" to "changeModes")))
        val many = (0..80).map { mapOf("resourceID" to it, "transferType" to 900000L) }
        val s = B233ModeRequestSummary.describe(mapOf(
            "type" to "changeModes",
            "params" to mapOf("resources" to many),
        ))
        assertTrue(s.contains("count=81["))
        assertTrue(s.contains("out-of-range"))
        assertFalse(s.contains("resourceID=4,"))
        assertTrue(s.length < 900)
    }
}
