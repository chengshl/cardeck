package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseBuildTimeTest {
    @Test fun formatsTrueBuildCompletionInBeijingTime() {
        val text = ReleaseBuildTime.label(
            "versionName=20261026104500\nbuiltAt=2026-10-26T02:48:30Z\n",
            "20261026104500",
        )
        assertEquals("构建完成：2026-10-26 10:48:30（北京时间）", text)
    }

    @Test fun doesNotMisrepresentOldVersionTimestampAsBuildCompletion() {
        val text = ReleaseBuildTime.label("branch=Buick-Test", "20261026104500")
        assertTrue(text.contains("版本时间：2026-10-26 10:45:00"))
        assertTrue(text.contains("未提供构建完成时间"))
    }

    @Test fun neverInventsMissingOrMalformedTimestamp() {
        assertEquals("构建时间：尚未提供", ReleaseBuildTime.label("builtAt=unknown", "T75"))
    }
}
