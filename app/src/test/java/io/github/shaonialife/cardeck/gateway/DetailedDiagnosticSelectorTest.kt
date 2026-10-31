package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailedDiagnosticSelectorTest {
    @Test fun preservesCrashAndLatestSystemEventsWithinBound() {
        val crash = listOf("[DropBox:system_server_crash] 2026-10-26 18:51:05 crash")
        val events = listOf("[事件轨迹] 2026-10-26 18:50:00 stage=RECORD")
        val app = (1..500).map { "18:51:00 VIDEO status " + it }
        val system = (1..4000).map { "10-26 18:51:01 E MediaCodec: event " + it }
        val lines = DetailedDiagnosticSelector.merge(crash, events, app, system, true)
        assertEquals(3000, lines.size)
        assertEquals(crash[0], lines[0])
        assertTrue(lines.any { it.contains("stage=RECORD") })
        assertTrue(lines.any { it.contains("VIDEO status 500") })
        assertTrue(lines.last().contains("event 4000"))
        assertFalse(lines.any { it.contains("event 1") && it.endsWith("event 1") })
        assertEquals(256, DetailedDiagnosticSelector.reviewSample(lines).size)
        assertEquals(lines[0], DetailedDiagnosticSelector.reviewSample(lines).first())
        assertEquals(lines.last(), DetailedDiagnosticSelector.reviewSample(lines).last())
    }

    @Test fun withoutShizukuNeverPretendsToHaveSystemLogs() {
        val lines = DetailedDiagnosticSelector.merge(
            listOf("critical"), emptyList(), listOf("app"), listOf("secret system"), false,
        )
        assertEquals(listOf("critical", "[App历史] app"), lines)
        assertFalse(lines.any { it.contains("secret system") })
    }

    @Test fun skipsExplicitSecretRedactionPlaceholders() {
        val lines = DetailedDiagnosticSelector.merge(
            listOf("[已隐藏含凭证或密钥的日志行]", "evidence"),
            emptyList(), emptyList(), emptyList(), true,
        )
        assertEquals(listOf("evidence"), lines)
    }
    @Test fun ignoresTrailingPlaceholdersBeforeAllocatingNewestSystemLogSlots() {
        val hidden = "[已隐藏含凭证或密钥的日志行]"
        val system = (1..3200).map { "valid event $it" } +
            List(3500) { if (it % 2 == 0) hidden else "   " }
        val lines = DetailedDiagnosticSelector.merge(
            emptyList(),
            listOf("stage=RECORD", hidden, ""),
            listOf("last app event", "  ", hidden),
            system,
            true,
        )
        assertEquals(3000, lines.size)
        assertEquals("[阶段事件] stage=RECORD", lines[0])
        assertEquals("[App历史] last app event", lines[1])
        assertEquals("[当前系统] valid event 3200", lines.last())
        assertTrue(lines.any { it == "[当前系统] valid event 203" })
        assertFalse(lines.any { it.contains(hidden) || it.isBlank() || it.endsWith("] ") })
    }

}
