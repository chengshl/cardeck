package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesTest {
    @Test fun showsChangelogFromUnifiedRelease() {
        val body = """
            versionName=20261020213745
            versionCode=88436265
            branch=Buick-main
            commit=3a2ae0cf786bfac3414340f909c5082075cb00ba

            ## 更新说明
            - 修复 AAC 音频同步端口错误后的恢复处理
            - 保留原有视频及触摸功能
        """.trimIndent()
        val displayed = UpdateNotes.extract(body)
        assertTrue(displayed.contains("• 修复 AAC 音频同步端口"))
        assertTrue(displayed.contains("• 保留原有视频"))
        assertFalse(displayed.contains("versionCode="))
        assertFalse(displayed.contains("commit="))
    }

    @Test fun oldReleasesHaveSafeFallback() {
        assertTrue(UpdateNotes.extract("versionCode=88436265\nbranch=main").contains("暂未提供"))
        assertTrue(UpdateNotes.extract("## 更新说明\n").contains("暂未提供"))
    }

    @Test fun veryLongReleaseNotesAreBoundedAndNextSectionsExcluded() {
        val text = UpdateNotes.extract("## 更新说明\n" + "长".repeat(2_000) + "\n## 附录\n不显示")
        assertEquals(1600, text.length)
        assertFalse(text.contains("不显示"))
    }
}
