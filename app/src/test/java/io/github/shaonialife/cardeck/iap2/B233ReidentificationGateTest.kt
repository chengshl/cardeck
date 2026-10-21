package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233ReidentificationGateTest {
    private val known = "BUICK_2427 B233 Saic General Motors"

    @Test fun exactB233ReidentificationIsOneShot() {
        val g = B233ReidentificationGate()
        assertFalse(g.claim(Iap2MessageIds.MFI_RESPONSE, known))
        assertTrue(g.claim(Iap2MessageIds.IDENTIFICATION_INFORMATION, known))
        assertFalse(g.claim(Iap2MessageIds.IDENTIFICATION_INFORMATION, known))
    }

    @Test fun otherReceiversAreNeverAdopted() {
        val g = B233ReidentificationGate()
        assertFalse(g.claim(Iap2MessageIds.IDENTIFICATION_INFORMATION,
            "BMW B233 Other Manufacturer"))
        assertFalse(g.claim(Iap2MessageIds.IDENTIFICATION_INFORMATION,
            "BUICK_2427 B233 Unrecognized"))
        assertTrue(g.claim(Iap2MessageIds.IDENTIFICATION_INFORMATION, known))
    }
}
