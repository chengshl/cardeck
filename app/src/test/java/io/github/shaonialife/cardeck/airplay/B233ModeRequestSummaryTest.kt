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
