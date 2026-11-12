package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.*
import org.junit.Test

class WindowFullscreenToggleTest {
    @Test fun landscapeWindowButtonIsInsideMapTileLowerLeft() {
        val bounds = WindowFullscreenToggle.bounds(1160, 540, 126, false, 463, 488, 16, 16, false)!!
        assertTrue(bounds.left >= 126f + 16f)
        assertTrue(bounds.top >= 16f)
        assertTrue(bounds.bottom < 540f)
        assertTrue(bounds.contains(bounds.left + 10, bounds.top + 10))
        assertFalse(bounds.contains(20f, 500f))
    }

    @Test fun maximizedButtonMovesToCanvasLowerLeft() {
        val bounds = WindowFullscreenToggle.bounds(1160, 540, 126, false, 463, 488, 16, 16, true)!!
        assertTrue(bounds.left < 126f)
        assertTrue(bounds.bottom <= 540f)
        assertTrue(bounds.contains(bounds.left + 2, bounds.bottom - 2))
    }

    @Test fun portraitWindowButtonFollowsBottomOfTile() {
        val bounds = WindowFullscreenToggle.bounds(540, 1160, 540, true, 508, 472, 16, 16, false)!!
        assertTrue(bounds.left >= 16f)
        assertTrue(bounds.top > 16f)
        assertTrue(bounds.bottom < 16f + 472f)
    }

    @Test fun unavailableDisplayHidesControl() {
        assertNull(WindowFullscreenToggle.bounds(0, 540, 126, false, 463, 488, 16, 16, false))
    }
}
