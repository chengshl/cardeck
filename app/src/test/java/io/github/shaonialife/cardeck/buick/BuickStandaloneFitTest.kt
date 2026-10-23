package io.github.shaonialife.cardeck.buick

import org.junit.Assert.*
import org.junit.Test

class BuickStandaloneFitTest {
    @Test fun e5SafeModeKeepsRatioAndCornersProtected() {
        val fit = BuickStandaloneFit.resolve(BuickViewport(278,44,1408,476), 400, 1972, 520)!!
        assertEquals(400, fit.left)
        assertTrue(fit.top >= 44)
        assertTrue(fit.left + fit.width <= 1686)
        assertTrue(fit.top + fit.height <= 520)
        assertEquals(0.0, fit.width.toDouble()/fit.height - 1572.0/520.0, 0.01)
        assertEquals(Pair(0,0), fit.unmap(fit.left,fit.top,1572,520))
        val (maxX,maxY) = fit.unmap(fit.left+fit.width-1,fit.top+fit.height-1,1572,520)
        assertTrue(maxX in 1568..1571)
        assertTrue(maxY in 517..519)
    }

    @Test fun absentOrUnusableSafeAreaLeavesFullScreen() {
        assertNull(BuickStandaloneFit.resolve(null,400,1972,520))
        assertNull(BuickStandaloneFit.resolve(BuickViewport(400,0,120,80),400,1972,520))
    }
}
