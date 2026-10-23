package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.*
import org.junit.Test

class OfflineLogFormatterTest {
    @Test fun keepsTimestampAndMasksProtocolAndIp() {
        val input = """
            [21:27:41.181] DISPLAY-TRAIL T49: SESSION_BEGIN
            [21:27:41.700] RAW tx: ff 5a 00 80
            [21:27:41.716] java.lang.NullPointerException
            [21:27:41.730] AIRPLAY: /pair-setup req=7B -> OK resp=3B head=[01 02 03]
            [21:27:41.740] address 192.0.2.10
        """.trimIndent()
        val out = OfflineLogFormatter.appLines(input, LogRedactor())
        assertEquals(5, out.size)
        assertTrue(out.any { it.contains("21:27:41.716") && it.contains("NullPointerException") })
        assertTrue(out.any { it.contains("[原始协议数据已省略]") })
        assertFalse(out.any { it.contains("ff 5a 00") || it.contains("head=[01") || it.contains("192.0.2.10") })
    }

    @Test fun reportMentionsLocalOnly() {
        val lines = listOf("[DropBox] 2026-10-23 21:27:41 system_server_crash", "NullPointerException")
        val txt = OfflineLogFormatter.format("重启崩溃取证","20261023211554","Sony XQ-DQ72",lines,"2026-10-23 21:30")
        assertTrue(txt.contains("不会自动上传"))
        assertTrue(lines.all { txt.contains(it) })
    }

    @Test fun secretsAreMasked() {
        val txt = OfflineLogFormatter.appLines("[21:27:41.123] pass ABCDEFGH\n[21:27:41.500] completed",
            LogRedactor(listOf("ABCDEFGH"))).joinToString("\n")
        assertFalse(txt.contains("ABCDEFGH"))
        assertTrue(txt.contains("21:27:41.500"))
    }
}
