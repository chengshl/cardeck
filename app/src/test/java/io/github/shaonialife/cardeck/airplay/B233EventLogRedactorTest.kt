package io.github.shaonialife.cardeck.airplay

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233EventLogRedactorTest {
    @Test fun malformedPeerBytesAndExceptionMessageCannotReachB233Log() {
        val syntheticSecret = "SYNTHETIC_PRIVATE_HEADER_AND_BODY"
        val diagnostic = listOf(
            B233EventLogRedactor.malformedCommand(syntheticSecret.length),
            B233EventLogRedactor.unknownRequest(syntheticSecret.length),
            B233EventLogRedactor.recoveredCommand(syntheticSecret.length),
            B233EventLogRedactor.error(
                B233EventLogRedactor.Stage.READ,
                IOException(syntheticSecret),
            ),
        ).joinToString("\n")
        assertFalse(diagnostic.contains(syntheticSecret))
        assertFalse(diagnostic.contains("hex="))
        assertTrue(diagnostic.contains("bodyBytes=${syntheticSecret.length}"))
        assertTrue(diagnostic.contains("type=IOException"))
        assertTrue(diagnostic.contains("redacted"))
    }

    @Test fun malformedSignatureKeepsOnlyLengthNotPeerBytePrefix() {
        assertEquals("B233:length:42", B233EventLogRedactor.malformedSignature(42))
        assertEquals("B233:length:0", B233EventLogRedactor.malformedSignature(-1))
        assertEquals(
            B233EventLogRedactor.malformedSignature(42),
            B233EventLogRedactor.malformedSignature(42),
        )
    }
}
