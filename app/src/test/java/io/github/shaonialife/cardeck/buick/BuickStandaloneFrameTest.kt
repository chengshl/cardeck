package io.github.shaonialife.cardeck.buick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickStandaloneFrameTest {
    @Test fun unifiedOuterFrameMatchesSettingsButKeepsMapAspect() {
        val safe = BuickViewport(278, 44, 1408, 476)
        val outer = BuickStandaloneFrame.resolve(safe, 400, 1972, 520)!!
        val inner = BuickStandaloneFit.resolve(safe, 400, 1972, 520)!!
        // 相同安全容器：来源于接收端，不二次乘 Activity 密度。
        assertEquals(BuickViewport(400, 44, 1286, 476), outer)
        assertEquals(outer.left, inner.left)
        assertEquals(outer.width, inner.width)
        assertTrue(inner.top >= outer.top)
        assertTrue(inner.top + inner.height <= outer.top + outer.height)
        assertEquals(0.0, inner.width.toDouble() / inner.height - 1572.0 / 520.0, 0.01)
        // 触摸反变换依然是老的拟合范围，装饰边缘不应该劫持触摸。
        assertEquals(Pair(0, 0), inner.unmap(inner.left, inner.top, 1572, 520))
        val bottomRight = inner.unmap(inner.left + inner.width - 1, inner.top + inner.height - 1, 1572, 520)
        assertTrue(bottomRight.first in 1567..1571)
        assertTrue(bottomRight.second in 516..519)
    }

    @Test fun invalidSafeAreaOrPortraitDoesNotGenerateDecorativeFrame() {
        assertNull(BuickStandaloneFrame.resolve(null, 400, 1972, 520))
        assertNull(BuickStandaloneFrame.resolve(BuickViewport(0, 0, 100, 100), 400, 1972, 520))
        assertNull(BuickStandaloneFrame.resolve(BuickViewport(278, 44, 1408, 476), 1972, 1972, 520))
    }
}
