package io.github.shaonialife.cardeck.buick

import org.junit.Assert.assertEquals
import org.junit.Test

class BuickMapTileMarginsTest {
    @Test fun e5DashboardMapRespectsRealReportedTop() {
        // /info primary: 1972 x 520, safeArea=(278,44,1408,476)
        val safe = BuickViewport(278, 44, 1408, 476)
        assertEquals(60 to 16, BuickMapTileMargins.resolve(safe, 520, 16))
        assertEquals(444, 520 - 60 - 16)
    }

    @Test fun disconnectedOrDisabledSafeAreaKeepsPreviousLayout() {
        assertEquals(16 to 16, BuickMapTileMargins.resolve(null, 520, 16))
    }

    @Test fun malformedSafeRectsCannotCollapseMapTile() {
        assertEquals(16 to 16, BuickMapTileMargins.resolve(
            BuickViewport(0, 450, 100, 50), 520, 16,
        ))
    }
}
