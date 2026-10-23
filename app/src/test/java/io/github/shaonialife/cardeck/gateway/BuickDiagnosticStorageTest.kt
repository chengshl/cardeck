package io.github.shaonialife.cardeck.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuickDiagnosticStorageTest {
    @Test fun sharedNamesDoNotContainVariant() {
        assertEquals("buick-critical-events", BuickDiagnosticStorage.EVENTS)
        assertEquals("buick-display-crash-trail", BuickDiagnosticStorage.DISPLAY_TRAIL)
    }

    @Test fun legacyNamesCanBeReadByExistingInstallations() {
        assertEquals("buick-test-t52-critical-events",
            BuickDiagnosticStorage.legacyPrefs("io.github.buick.test.cardeck", "t52-critical-events"))
        assertEquals("buick-e5-t52-critical-events",
            BuickDiagnosticStorage.legacyPrefs("io.github.buick.e5.cardeck", "t52-critical-events"))
        assertEquals("buick-main-display-crash-trail",
            BuickDiagnosticStorage.legacyPrefs("io.github.buick.main.cardeck", "display-crash-trail"))
    }

    @Test fun invalidPackageOrPurposeIsRejected() {
        assertNull(BuickDiagnosticStorage.legacyPrefs("io.github.someapp", "t52-critical-events"))
        assertNull(BuickDiagnosticStorage.legacyPrefs("io.github.buick.test.cardeck", "../key"))
    }
}
