package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickMainDisplayModeTest {
    @Test fun defaultRestoresRealDisplayAndTouchOnSonyB233() {
        val risk = SonyB233CrashGuard.useDisplaylessDiagnostic("Sony", "XQ-DQ72", 35, true)
        assertTrue(risk)
        assertFalse(BuickMainDisplayMode.useT28Displayless(risk, true))
    }

    @Test fun userCanExplicitlyReturnToT28Diagnostics() {
        assertTrue(BuickMainDisplayMode.useT28Displayless(true, false))
    }

    @Test fun allOtherHardwareRetainsNormalVirtualDesktop() {
        assertFalse(BuickMainDisplayMode.useT28Displayless(false, false))
        assertFalse(BuickMainDisplayMode.useT28Displayless(false, true))
    }
}
