package io.github.shaonialife.cardeck.buick

import org.junit.Assert.assertEquals
import org.junit.Test

class BuickSettingsInsetsTest {
    @Test fun e5SettingsInAppDisplayOnlyNeedRightAndTopInset() {
        val reported = BuickViewport(278, 44, 1408, 476)
        assertEquals(
            BuickInsets(0, 44, 286, 0),
            BuickSettingsInsets.forApp(reported, 1972, 520, 1574, 1574, 520),
        )
    }

    @Test fun higherDensitySettingActivityScalesInsetsWithoutVideoStretching() {
        val reported = BuickViewport(278, 44, 1408, 476)
        assertEquals(
            BuickInsets(0, 66, 429, 0),
            BuickSettingsInsets.forApp(reported, 1972, 520, 1574, 2361, 780),
        )
    }

    @Test fun disabledAndInvalidGeometryLeaveOriginalLayoutIntact() {
        assertEquals(BuickInsets.ZERO, BuickSettingsInsets.forApp(
            null, 1972, 520, 1574, 1574, 520,
        ))
        assertEquals(BuickInsets.ZERO, BuickSettingsInsets.forApp(
            BuickViewport(278, 44, 1408, 476), 1972, 520, 0, 1574, 520,
        ))
    }
}
