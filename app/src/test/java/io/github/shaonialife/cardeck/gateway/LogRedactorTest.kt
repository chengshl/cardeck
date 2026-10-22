package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.*
import org.junit.Test

class LogRedactorTest {
    @org.junit.Test
    fun airplaySessionKeyFragmentsNeverLeaveTheDevice() {
        val redactor = LogRedactor()
        val line = "AIRPLAY paired writeKey=abcdef012345 readKey=abcdef987654"
        org.junit.Assert.assertEquals("[已隐藏含凭证或密钥的日志行]", redactor.line(line))
        org.junit.Assert.assertEquals("[已隐藏含凭证或密钥的日志行]", redactor.line("AirPlay READKEY=1234"))
    }

    @Test fun masksCredentialsAddressesIdentifiersAndControlCharacters() {
        val redactor = LogRedactor(listOf("exact-short-secret"))
        assertFalse(redactor.line("value=exact-short-secret").contains("exact-short-secret"))
        assertEquals("[已隐藏含凭证或密钥的日志行]", redactor.line("Authorization: Bearer sample"))
        val safe = redactor.line("192.0.2.200 00:11:22:33:44:55 user@example.com wss://example.org/?key=sample\n\u0000")
        assertFalse(safe.contains("192.0.2.200"))
        assertFalse(safe.contains("00:11:22"))
        assertFalse(safe.contains("user@example"))
        assertFalse(safe.contains("wss://"))
        assertFalse(safe.contains('\n'))
        assertFalse(safe.contains('\u0000'))
        assertFalse(redactor.line("ssid=private-wifi").contains("private-wifi"))
        assertFalse(redactor.line("blob=" + "a".repeat(64)).contains("a".repeat(32)))
    }

    @Test fun boundsLinesAndDropsOldestUnderBackpressure() {
        val buffer = GatewayLogBuffer(LogRedactor(), 3)
        listOf("old", "new1", "new2", "new3").forEach(buffer::offer)
        assertEquals(1L, buffer.dropped)
        assertEquals(listOf("new1", "new2", "new3"), buffer.take())
        buffer.offer("x".repeat(9000))
        assertEquals(listOf("[超长日志已省略]"), buffer.take())
    }
}
