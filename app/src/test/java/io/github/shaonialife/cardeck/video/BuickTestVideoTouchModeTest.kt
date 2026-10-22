package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickTestVideoTouchModeTest {
    @Test fun testBranchRestoresVirtualDesktopAndTouchForSonyB233() {
        val risk = SonyB233CrashGuard.useDisplaylessDiagnostic("Sony", "XQ-DQ72", 35, true)
        assertTrue(risk)
        assertTrue(BuickTestVideoTouchMode.RESTORE_FULL_DESKTOP_AND_TOUCH)
        assertFalse(BuickTestVideoTouchMode.useT28Displayless(risk))
    }

    @Test fun unrelatedHardwareIsNotForcedToDiagnostic() {
        assertFalse(BuickTestVideoTouchMode.useT28Displayless(false))
    }
}
