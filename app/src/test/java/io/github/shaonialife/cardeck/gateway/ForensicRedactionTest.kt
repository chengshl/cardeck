package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.*
import org.junit.Test

class ForensicRedactionTest {
    private val redactor = LogRedactor(listOf("LOCAL_TEST_CREDENTIAL"))

    @Test fun preservesSystemExceptionContextWithoutExposingCredentials() {
        val source = "10-26 21:02:24.098 E SettingsProvider: SELinux denied " +
            "signature mismatch; token=SHOULD_NEVER_LEAK password:abc123 " +
            "Authorization: Bearer FORBIDDEN_VALUE LOCAL_TEST_CREDENTIAL"
        val lines = redactor.forensicChunks(source)
        val output = lines.joinToString(" ")
        assertTrue(output.contains("SELinux denied"))
        assertTrue(output.contains("signature mismatch"))
        assertTrue(output.contains("21:02:24.098"))
        assertFalse(output.contains("SHOULD_NEVER_LEAK"))
        assertFalse(output.contains("abc123"))
        assertFalse(output.contains("FORBIDDEN_VALUE"))
        assertFalse(output.contains("LOCAL_TEST_CREDENTIAL"))
        assertTrue(lines.all { it.length <= 256 })
    }

    @Test fun longStackTraceKeepsBothHeadAndTailInBoundedChunks() {
        val raw = "Start trace " + "at VendorService.dispatch(Event.java:231); ".repeat(24) + " End cause"
        val chunks = redactor.forensicChunks(raw)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.first().contains("Start trace"))
        assertTrue(chunks.last().contains("End cause"))
        assertTrue(chunks.all { it.length <= 256 })
    }

    @Test fun rawProtocolIsNeverSentInDeepMode() {
        val result = redactor.forensicChunks("RAW tx: 123456789012")
        assertTrue(result.joinToString("").contains("已省略"))
        assertFalse(result.joinToString("").contains("123456789012"))
    }

    @Test fun quotedJsonIdentityFieldsAreRedactedInBothModes() {
        // Short quoted values are not caught by generic IP/long-token masking.
        val raw = """10-26 21:02:24.098 E System: {"ssid":"Private Network","deviceId":"DEVICE-SECRET-7","serial":"SN-PRIVATE-123","vin":"W0PRIVATEVIN","latitude":"22.12","longitude":"114.12"} stage=RECORD"""
        val detailed = redactor.forensicChunks(raw).joinToString(" ")
        val quick = redactor.line(raw)
        for (secret in listOf(
            "Private Network", "DEVICE-SECRET-7", "SN-PRIVATE-123",
            "W0PRIVATEVIN", "22.12", "114.12",
        )) {
            assertFalse("detailed mode leaked: $secret", detailed.contains(secret))
            assertFalse("quick mode leaked: $secret", quick.contains(secret))
        }
        assertTrue(detailed.contains("stage=RECORD"))
        assertTrue(detailed.contains("ssid=[已隐藏]"))
        assertTrue(quick.contains("ssid=[已隐藏]"))
    }

}
