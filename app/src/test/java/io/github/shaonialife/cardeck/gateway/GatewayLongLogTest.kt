package io.github.shaonialife.cardeck.gateway

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayLongLogTest {
    @Test fun byteAwareFramesPreserveChineseEmojiEscapesAndOrder() {
        val lines = listOf("完整原行;".repeat(700), "😀中文;".repeat(700), "\"\\".repeat(3000))
        val plan = GatewayLogProtocol.plan(lines, "system", null)
        assertEquals(lines, plan.flatten())
        assertTrue(plan.size > 1)
        plan.forEach {
            val text = GatewayLogProtocol.message(it, "system", null, "00000000-0000-4000-8000-000000000000")
            assertTrue(text.toByteArray(Charsets.UTF_8).size <= 16384)
            assertEquals(2, JSONObject(text).getInt("schema_version"))
        }
        assertFalse(GatewayLogProtocol.fits(listOf("😀".repeat(3073)), "system", null))
        assertFalse(GatewayLogProtocol.fits(listOf("\"".repeat(12288)), "system", null))
    }

    @Test fun unifiedHttpV2PreservesRawStringsAndFixedContract() {
        val id = "00000000-0000-4000-8000-000000000000"
        val raw = listOf("token=very-sensitive-value", "192.168.10.20 password=hello",
            "2026-11-19 12:34:56.780 😀 \"quoted\" and slash\\")
        val json = JSONObject(GatewayLogProtocol.httpMessage(raw, "system", id, id))
        assertEquals(setOf("schema_version", "batch_id", "client_id", "source",
            "user_confirmed", "lines"), json.keys().asSequence().toSet())
        assertEquals(2, json.getInt("schema_version"))
        assertEquals(true, json.getBoolean("user_confirmed"))
        assertEquals(raw, (0 until json.getJSONArray("lines").length()).map {
            json.getJSONArray("lines").getString(it)
        })
        assertEquals(raw, GatewayLogProtocol.planHttp(raw, "system").flatten())
        assertFalse(GatewayLogProtocol.fitsHttp(listOf("😀".repeat(3073)), "app"))
    }

    @Test fun lineAndForensicPathsKeepLongContextWithoutTruncation() {
        val text = "[12:34:56.789] context;" + "长栈信息;".repeat(600)
        assertEquals(text, LogRedactor().line(text))
        assertEquals(listOf(text), LogRedactor().forensicChunks(text))
        val selected = DetailedDiagnosticSelector.merge(emptyList(), emptyList(), listOf(text), emptyList(), false)
        assertEquals("[App历史] " + text, selected.single())
    }

    @Test fun bufferHasNoEightKbOrThirtyTwoLineSilentReplacement() {
        val buffer = GatewayLogBuffer(LogRedactor(), 100)
        val longLine = "长日志;".repeat(900)
        buffer.offer(longLine)
        assertEquals(listOf(longLine), buffer.take())
        val many = (1..50).map { "日志第${it}行" }
        buffer.offer(many.joinToString("\n"))
        val all = mutableListOf<String>()
        repeat(7) { all.addAll(buffer.take()) }
        assertEquals(many, all)
        buffer.offer("长".repeat(4097))
        assertTrue(buffer.take().isEmpty())
        assertEquals(1L, buffer.dropped)
    }
}
