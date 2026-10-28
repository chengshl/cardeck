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

    @Test fun knownE5ExperimentNamesAreChineseWithoutChangingSourceIdentifiers() {
        val names = mapOf(
            "experiment/buick-e5-bluetooth-a2dp-preserve" to "蓝牙音源保护（A2DP/HFP）",
            "experiment/buick-e5-detailed-diagnostics" to "详细故障诊断与日志",
            "experiment/buick-e5-iap2-stop-safety" to "iAP2 安全退出",
            "experiment/buick-e5-session-teardown" to "CarPlay 会话退出与重连",
            "experiment/buick-e5-updater-b-minimal" to "更新器方案 B · 最小融合",
            "experiment/buick-e5-t78-updater-b-convergence" to "更新器融合 · T78 联调",
            "experiment/buick-e5-test-convergence" to "Test → E5 功能融合",
        )
        names.forEach { (source, label) ->
            assertEquals(label, ExperimentProjectCatalog.name(source))
        }
        assertEquals("其他实验 · buick e5 unknown feature",
            ExperimentProjectCatalog.name("experiment/buick-e5-unknown-feature"))
    }

    @Test fun noExperimentAndLowerVersionAreExplicitStates() {
        assertTrue(ExperimentProjectCatalog.group(emptyList()).isEmpty())
        val audio = sample("experiment/buick-e5-t75-rtcp-observe", 100)
        assertTrue(ExperimentProjectCatalog.installState(110, audio).contains("重新构建"))
        assertTrue(ExperimentProjectCatalog.installState(90, audio).contains("可覆盖安装"))
    }
}
