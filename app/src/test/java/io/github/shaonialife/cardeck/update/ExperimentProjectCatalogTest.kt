package io.github.shaonialife.cardeck.update

import org.junit.Assert.*
import org.junit.Test

class ExperimentProjectCatalogTest {
    private fun sample(source: String, code: Long) = ExperimentalUpdates.Candidate(
        tag = "exp-buick-e5-$code",
        baseBranch = "Buick-E5",
        sourceBranch = source,
        versionName = "20261026164800",
        versionCode = code,
        builtAt = "2026-10-26T08:48:00Z",
        apkName = "CarDeck-Test.apk",
        apkUrl = "https://github.com/chengshl/cardeck/releases/download/a/a.apk",
        shaUrl = "https://github.com/chengshl/cardeck/releases/download/a/a.apk.sha256",
        notes = "test",
    )

    @Test fun aNewerUnrelatedProjectNeverReplacesSelectedAudioProject() {
        val audio = sample("experiment/buick-e5-t75-rtcp-observe", 101)
        val task = sample("experiment/buick-e5-e5-task-isolation", 999)
        assertEquals(audio, ExperimentProjectCatalog.latest(listOf(task, audio), audio.sourceBranch))
        assertNull(ExperimentProjectCatalog.latest(listOf(task, audio), null))
    }

    @Test fun projectListGroupsHistoryAndSelectsHighestWithinEachProject() {
        val source = "experiment/buick-e5-t75-rtcp-observe"
        val projects = ExperimentProjectCatalog.group(listOf(
            sample(source, 101), sample(source, 105),
            sample("experiment/buick-e5-e5-task-isolation", 999),
        ))
        assertEquals(2, projects.size)
        assertEquals(105L, projects.first { it.sourceBranch == source }.newest.versionCode)
        assertEquals("T75 · 音频 RTCP", ExperimentProjectCatalog.name(source))
    }

    @Test fun knownE5ExperimentsDisplayChineseWithoutChangingExactSubscriptionKeys() {
        val expected = mapOf(
            "experiment/buick-e5-bluetooth-a2dp-preserve" to "蓝牙音源保护（A2DP/HFP）",
            "experiment/buick-e5-detailed-diagnostics" to "详细故障诊断与日志",
            "experiment/buick-e5-iap2-stop-safety" to "iAP2 安全退出",
            "experiment/buick-e5-session-teardown" to "CarPlay 会话退出与重连",
            "experiment/buick-e5-video-firstframe-observability" to "视频首帧与停帧诊断（#28）",
            "experiment/buick-e5-iap2-stale-ack-guard-33" to "iAP2 重连 ACK 防误确认（#33）",
            "experiment/buick-e5-updater-b-minimal" to "更新器方案 B · 最小融合",
            "experiment/buick-e5-t78-updater-b-convergence" to "更新器融合 · T78 联调",
            "experiment/buick-e5-test-convergence" to "Test → E5 功能融合",
        )
        expected.forEach { (source, label) ->
            assertEquals(label, ExperimentProjectCatalog.name(source))
            val selected = ExperimentProjectCatalog.latest(
                listOf(sample(source, 101), sample("experiment/buick-e5-another-feature", 999)),
                source,
            )
            assertEquals(source, selected?.sourceBranch)
            assertEquals(101L, selected?.versionCode)
        }
    }

    @Test fun unfamiliarAndForeignProjectsCannotPretendToBeTrustedE5DisplayNames() {
        assertEquals("其他实验 · buick e5 unknown feature",
            ExperimentProjectCatalog.name("experiment/buick-e5-unknown-feature"))
        assertEquals("其他实验 · buick e5 session teardown extra",
            ExperimentProjectCatalog.name("experiment/buick-e5-session-teardown-extra"))
        assertEquals("其他实验 · buick test t76 aac causality observe",
            ExperimentProjectCatalog.name("experiment/buick-test-t76-aac-causality-observe"))
        assertEquals("任务隔离实验",
            ExperimentProjectCatalog.name("experiment/buick-e5-e5-task-isolation"))
    }

    @Test fun noExperimentAndLowerVersionAreExplicitStates() {
        assertTrue(ExperimentProjectCatalog.group(emptyList()).isEmpty())
        val audio = sample("experiment/buick-e5-t75-rtcp-observe", 100)
        assertTrue(ExperimentProjectCatalog.installState(110, audio).contains("重新构建"))
        assertTrue(ExperimentProjectCatalog.installState(90, audio).contains("可覆盖安装"))
    }

    @Test fun plannedAckGuardExperimentUsesExactChineseSourceAndCannotCrossSubscribe() {
        // Exact future release source; no release or device acceptance is claimed here.
        val source = "experiment/buick-e5-iap2-stale-ack-guard-33"
        val candidate = sample(source, 89210000)
        val unrelated = sample("experiment/buick-e5-video-firstframe-observability", 99999999)
        assertEquals("iAP2 重连 ACK 防误确认（#33）", ExperimentProjectCatalog.name(source))
        assertEquals(source, ExperimentProjectCatalog.group(listOf(candidate)).single().sourceBranch)
        assertEquals(candidate, ExperimentProjectCatalog.latest(listOf(unrelated, candidate), source))
        assertNull(ExperimentProjectCatalog.latest(listOf(candidate), source + "-extra"))
        assertEquals("其他实验 · buick e5 iap2 stale ack guard 33 extra",
            ExperimentProjectCatalog.name(source + "-extra"))
    }

    @Test fun officialE5VideoObservationReleaseDisplaysChineseButRetainsExactSource() {
        val source = "experiment/buick-e5-video-firstframe-observability"
        val released = sample(source, 89195447)
        assertEquals("视频首帧与停帧诊断（#28）", ExperimentProjectCatalog.name(source))
        assertEquals(source, ExperimentProjectCatalog.group(listOf(released)).single().sourceBranch)
        assertEquals(released, ExperimentProjectCatalog.latest(listOf(released), source))
        assertNull(ExperimentProjectCatalog.latest(listOf(released), "experiment/buick-e5-session-teardown"))
        assertEquals("其他实验 · buick e5 video firstframe observability extra",
            ExperimentProjectCatalog.name(source + "-extra"))
    }
}
