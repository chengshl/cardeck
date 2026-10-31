package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPreviewSummaryTest {
    @Test fun duplicatedDatedTombstoneIsNeverLabeledNew() {
        val lines = listOf(
            "[上轮App1] LINK closed",
            "[阶段事件] APPLICATION_START",
            "[DropBox:SYSTEM_TOMBSTONE] 2026-10-24 14:07:18 SYSTEM_TOMBSTONE",
            "[当前系统] warning",
        )
        val id = "SYSTEM_TOMBSTONE@2026-10-24 14:07:18"
        assertEquals("历史证据 · 此前提交过相同崩溃记录",
            DiagnosticPreviewSummary.headline(id, true))
        val text = DiagnosticPreviewSummary.body(lines, id, true, true)
        assertTrue(text.contains("原始发生时间：2026-10-24 14:07:18"))
        assertTrue(text.contains("重复证据"))
        assertTrue(text.contains("不代表所有批次都已被服务器确认"))
        assertTrue(text.contains("上限256行，并非完整历史日志量"))
        assertTrue(text.contains("前次App 1 行"))
        assertTrue(text.contains("Shizuku 已就绪"))
    }

    @Test fun missingDatedCrashDoesNotClaimNoCrashOrNewCrash() {
        val text = DiagnosticPreviewSummary.body(listOf("[上轮App1] restart"),
            null, false, false)
        assertTrue(text.contains("不代表没有发生系统故障"))
        assertTrue(text.contains("Shizuku 未就绪"))
        assertEquals("尚未识别带发生时间的系统崩溃事件",
            DiagnosticPreviewSummary.headline(null, false))
    }

    @Test fun previouslyUnsubmittedRecordStillNotAssumedNew() {
        val text = DiagnosticPreviewSummary.body(emptyList(),
            "system_server_crash@2026-10-20 11:00:00", false, true)
        assertTrue(text.contains("不等于它是刚刚发生的新崩溃"))
    }

    // Selectively ported from Buick-Test: regression only, no runtime changes.
    @Test fun appStagesOnlyAreNotCountedAsNewSystemCrash() {
        val text = DiagnosticPreviewSummary.body(
            listOf("[阶段事件] stage=APPLICATION_START process=abcd"),
            null, false, false,
        )
        assertTrue(text.contains("阶段记录 1 行"))
        assertTrue(text.contains("不代表没有发生系统故障"))
    }

    @Test fun previouslyUnsubmittedRecordStillNotAssumedNew() {
        val text = DiagnosticPreviewSummary.body(emptyList(),
            "system_server_crash@2026-10-20 11:00:00", false, true)
        assertTrue(text.contains("不等于它是刚刚发生的新崩溃"))
    }

}
