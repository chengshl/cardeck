package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyB233CrashGuardTest {
    @Test fun t28ProtectsReportedSonyAndroid15BuickCombination() {
        assertTrue(SonyB233CrashGuard.useDisplaylessDiagnostic("Sony", "XQ-DQ72", 35, true))
        assertTrue(SonyB233CrashGuard.useDisplaylessDiagnostic("Sony Mobile", "XQ_DQ72", 35, true))
        assertTrue(SonyB233CrashGuard.useDisplaylessDiagnostic("SONY", "XQ-DQ72", 36, true))
    }

    @Test fun t28DoesNotChangeOtherCarsOrPhones() {
        assertFalse(SonyB233CrashGuard.useDisplaylessDiagnostic("Sony", "XQ-DQ72", 35, false))
        assertFalse(SonyB233CrashGuard.useDisplaylessDiagnostic("Sony", "XQ-DQ72", 34, true))
        assertFalse(SonyB233CrashGuard.useDisplaylessDiagnostic("Sony", "XQ-DQ54", 35, true))
        assertFalse(SonyB233CrashGuard.useDisplaylessDiagnostic("Google", "Pixel 9", 35, true))
        assertFalse(SonyB233CrashGuard.useDisplaylessDiagnostic("Samsung", "XQ-DQ72", 35, true))
    }
}
