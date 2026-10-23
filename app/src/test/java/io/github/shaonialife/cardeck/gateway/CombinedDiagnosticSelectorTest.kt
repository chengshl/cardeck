package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.*
import org.junit.Test

class CombinedDiagnosticSelectorTest {
    @Test fun retainsDatedCrashHeaderAndReservesRecentSystemWarnings() {
        val crashes = listOf(
            "[阶段事件] 2026-10-23 22:08:19 stage=CREATE_DISPLAY",
            "[DropBox:system_server_crash] 2026-10-23 22:08:26 system_server_crash",
            "[DropBox:system_server_crash] java.lang.NullPointerException",
            "[DropBox:system_server_crash] at RootWindowContainer.onDisplayAdded",
        ) + (1..252).map { "[DropBox:system_server_crash] stack $it" }
        val warnings = (1..100).map { "10-23 22:08:30 E ActivityManager: warning $it" }
        val result = CombinedDiagnosticSelector.merge(crashes, warnings, true)
        assertEquals(256, result.size)
        assertEquals(208, result.count { !it.startsWith("[当前系统]") })
        assertEquals(48, result.count { it.startsWith("[当前系统]") })
        assertTrue(result.any { it.contains("22:08:26") && it.contains("system_server_crash") })
        assertTrue(result.any { it.contains("NullPointerException") })
        assertTrue(result.any { it.contains("onDisplayAdded") })
        assertTrue(result.any { it.contains("warning 100") })
        assertFalse(result.any { it.contains("warning 1\n") })
        assertEquals("system_server_crash@2026-10-23 22:08:26",
            CrashEvidenceSelector.datedCrashId(result))
        assertTrue(result.all { it.length <= 256 })
    }

    @Test fun keepsAllShortEvidenceAndMakesRoomForWarnings() {
        val evidence = listOf("[阶段事件] stage=A", "[取证状态] 无新崩溃")
        val result = CombinedDiagnosticSelector.merge(evidence, (1..80).map { "warning $it" }, true)
        assertEquals(82, result.size)
        assertEquals(evidence, result.take(2))
        assertEquals(80, result.count { it.startsWith("[当前系统]") })
    }

    @Test fun worksWithoutShizukuAndDoesNotInventSystemLogs() {
        val evidence = listOf("[上轮App1] SESSION_BEGIN", "[取证状态] Shizuku未运行")
        val result = CombinedDiagnosticSelector.merge(evidence, listOf("secret system warning"), false)
        assertEquals(evidence, result)
    }

    @Test fun emptyWarningsDoNotTruncateCrashEvidence() {
        val evidence = (1..256).map { "[DropBox:system_app_crash] frame $it" }
        assertEquals(evidence, CombinedDiagnosticSelector.merge(evidence, emptyList(), true))
    }
}
