package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.*
import org.junit.Test

class BuickDisplaySafetyPlanTest {
    @Test fun sonyB233StablePathNeedsOnlyDockAndApp() {
        assertFalse(BuickDisplaySafetyPlan.optionalScreensEnabled(true))
        assertTrue(BuickDisplaySafetyPlan.optionalScreensEnabled(false))
    }

    @Test fun noDestructiveResidualRecoveryOnSonyB233() {
        assertFalse(BuickDisplaySafetyPlan.mayStartWithResidualScreens(true, 1))
        assertTrue(BuickDisplaySafetyPlan.mayStartWithResidualScreens(true, 0))
        assertTrue(BuickDisplaySafetyPlan.mayStartWithResidualScreens(false, 1))
    }

    @Test fun protectedPhoneCannotFallbackToAnotherCreatePath() {
        assertFalse(BuickDisplaySafetyPlan.allowPublicFallback(true))
        assertTrue(BuickDisplaySafetyPlan.allowPublicFallback(false))
    }
}
