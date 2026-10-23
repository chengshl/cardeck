package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.*
import org.junit.Test

class CrashEvidenceSelectorTest {
    private val redact = LogRedactor()

    @Test fun showsPreviousAppAndSystemRebootEvidence() {
        val previous = "VIDEO: 帧 #2 25B 已发送\nBT-ADAPTER: 手机蓝牙开关状态 ON -> OFF\n"
        val boot = "E/AndroidRuntime: FATAL EXCEPTION system_server\n at android.server.WindowContextListenerImpl.update\n"
        val dropbox = mapOf("SYSTEM_RESTART" to "SYSTEM_RESTART\nProcess system_server died\n")
        val found = CrashEvidenceSelector.select(previous, boot, dropbox, true, redact)
        assertTrue(found.any { it.contains("蓝牙开关") })
        assertTrue(found.any { it.contains("WindowContextListenerImpl") })
        assertTrue(found.any { it.contains("DropBox:SYSTEM_RESTART") })
        assertFalse(found.any { it.contains("VIDEO: 帧") })
        assertTrue(found.size <= 64)
    }

    @Test fun shizukuAbsentDoesNotClaimCrashEvidence() {
        val found = CrashEvidenceSelector.select("LINK closed", null, emptyMap(), false, redact)
        assertTrue(found.any { it.contains("上轮App") })
        assertTrue(found.any { it.contains("Shizuku未运行") })
        assertFalse(found.any { it.contains("上次启动crash") })
    }

    @Test fun noSystemEvidenceExplainsUncertainty() {
        val found = CrashEvidenceSelector.select(null, "", emptyMap(), true, redact)
        assertTrue(found.any { it.contains("不能据此判定无崩溃") })
    }

    @Test fun keepsDatedDropBoxExceptionAndFirstFrames() {
        val raw = """
            Drop box contents: 115 entries
            2026-10-22 18:00:01 system_server_crash (text, 400 bytes)
            FATAL EXCEPTION: main
            java.lang.IllegalStateException: OLD_CRASH
            at old.SomeClass.method(Old.kt:1)
            2026-10-23 20:19:50 system_server_crash (text, 1200 bytes)
            Process: system_server, PID: 123
            java.lang.IllegalArgumentException: NEW_DISPLAY_CRASH
            at com.android.server.wm.WindowContainer.addChild(WindowContainer.java:805)
            at com.android.server.wm.RootWindowContainer.onDisplayAdded(RootWindowContainer.java:2898)
            Caused by: java.lang.IllegalStateException: duplicate display
            at android.hardware.display.DisplayManagerGlobal.dispatch(DisplayManagerGlobal.java:1)
        """.trimIndent()
        val found = CrashEvidenceSelector.select(null, "", mapOf("system_server_crash" to raw), true, redact)
        assertTrue(found.any { it.contains("2026-10-23 20:19:50") })
        assertTrue(found.any { it.contains("NEW_DISPLAY_CRASH") })
        assertTrue(found.any { it.contains("Caused by:") })
        assertTrue(found.any { it.contains("WindowContainer.addChild") })
        assertFalse(found.any { it.contains("OLD_CRASH") })
        assertEquals("system_server_crash@2026-10-23 20:19:50", CrashEvidenceSelector.datedCrashId(found))
        assertTrue(found.size <= 64)
    }

    @Test fun undatedDropBoxNotMislabelledAsNewDatedCrash() {
        val found = CrashEvidenceSelector.select(null, "", mapOf(
            "system_server_crash" to "at com.android.server.wm.RootWindowContainer.onDisplayAdded(Foo.java:1)"
        ), true, redact)
        assertNull(CrashEvidenceSelector.datedCrashId(found))
        assertTrue(found.any { it.contains("未识别到事件时间") })
    }

    @Test fun redactSensitiveAndBoundUpload() {
        val previous = (1..100).joinToString("\n") { "SESSION: secret foo password=123 $it" }
        val drop = mapOf("system_server_crash" to (1..100).joinToString("\n") {
            "FATAL Exception url=http://private.example/$it"
        })
        val result = CrashEvidenceSelector.select(previous, "FATAL ".repeat(100), drop, true, redact)
        assertTrue(result.size <= 64)
        assertTrue(result.all { it.length <= 256 })
        assertTrue(result.none { it.contains("private.example") || it.contains("password=123") })
    }
}
