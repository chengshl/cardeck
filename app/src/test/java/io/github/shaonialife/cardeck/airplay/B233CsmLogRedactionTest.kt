package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class B233CsmLogRedactionTest {
    @Test fun b233DoesNotEchoIdentityBytesEvenWithLargeLimit() {
        val identity = "BUICK_2427\u0000E2LB\u0000serial=123".toByteArray(Charsets.UTF_8)
        assertEquals("[redacted]", B233CsmLogRedaction.payload(true, identity, 99999))
        assertEquals("[redacted]", B233CsmLogRedaction.params(true, "name=BUICK_2427"))
        assertFalse(B233CsmLogRedaction.payload(true, identity, 160).contains("42 55 49 43 4b"))
    }

    @Test fun legacyNonB233HexRemainsAvailableWithoutChangingProtocol() {
        assertEquals("42 55", B233CsmLogRedaction.payload(false, byteArrayOf(0x42, 0x55), 80))
        assertEquals("name=legacy", B233CsmLogRedaction.params(false, "name=legacy"))
    }

    @Test fun emptyAndBinaryFramesFailClosedForB233() {
        assertEquals("[redacted]", B233CsmLogRedaction.payload(true, byteArrayOf(), 0))
        assertEquals("[redacted]", B233CsmLogRedaction.payload(true, byteArrayOf(0, -1, 1, 127), 200))
    }
}
