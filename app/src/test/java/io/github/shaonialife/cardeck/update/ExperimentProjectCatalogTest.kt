package io.github.shaonialife.cardeck.update

import org.junit.Assert.*
import org.junit.Test

class ExperimentProjectCatalogTest {
    private fun sample(source: String, code: Long) = ExperimentalUpdates.Candidate(
        tag = "exp-buick-test-$code",
        baseBranch = "Buick-Test",
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
        val audio = sample("experiment/buick-test-t75-rtcp-observe", 101)
        val task = sample("experiment/buick-test-e5-task-isolation", 999)
        assertEquals(audio, ExperimentProjectCatalog.latest(listOf(task, audio), audio.sourceBranch))
        assertNull(ExperimentProjectCatalog.latest(listOf(task, audio), null))
    }

    @Test fun projectListGroupsHistoryAndSelectsHighestWithinEachProject() {
        val source = "experiment/buick-test-t75-rtcp-observe"
        val projects = ExperimentProjectCatalog.group(listOf(
            sample(source, 101), sample(source, 105),
            sample("experiment/buick-test-e5-task-isolation", 999),
        ))
        assertEquals(2, projects.size)
        assertEquals(105L, projects.first { it.sourceBranch == source }.newest.versionCode)
        assertEquals("T75 · 音频 RTCP", ExperimentProjectCatalog.name(source))
        assertEquals("CarPlay 音频 · 20秒断连实验",
            ExperimentProjectCatalog.name("experiment/buick-test-t76-aac-causality-observe"))
    }

    @Test fun noExperimentAndLowerVersionAreExplicitStates() {
        assertTrue(ExperimentProjectCatalog.group(emptyList()).isEmpty())
        val audio = sample("experiment/buick-test-t75-rtcp-observe", 100)
        assertTrue(ExperimentProjectCatalog.installState(110, audio).contains("重新构建"))
        assertTrue(ExperimentProjectCatalog.installState(90, audio).contains("可覆盖安装"))
    }
}
