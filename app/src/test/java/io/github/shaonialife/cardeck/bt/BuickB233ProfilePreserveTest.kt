package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickB233ProfilePreserveTest {
    private fun hint(vararg digits: Int) = "BUICK_" + digits.joinToString("")
    @Test fun narrowlyMatchesFourDigitBuickHint() {
        assertTrue(BuickB233Route.isKnownReceiver(hint(1, 2, 3, 4)))
        assertTrue(BuickB233Route.isKnownReceiver(" " + hint(9, 8, 7, 6).lowercase() + " "))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK"))
        assertFalse(BuickB233Route.isKnownReceiver(hint(1, 2, 3, 4, 5)))
        assertFalse(BuickB233Route.isKnownReceiver(hint(1, 2, 3)))
        assertFalse(BuickB233Route.isKnownReceiver("AUDI_" + "1234"))
        assertFalse(BuickB233Route.isKnownReceiver(null))
    }

    @Test fun protectsOnlyB233CandidateOrExplicitlyMatchedSelection() {
        assertTrue(BuickB233Route.shouldProtectProfiles(hint(9, 9, 9, 9), null, false))
        assertTrue(BuickB233Route.shouldProtectProfiles("RenamedCar", hint(1, 2, 3, 4), true))
        assertFalse(BuickB233Route.shouldProtectProfiles("Headphones", hint(1, 2, 3, 4), false))
        assertFalse(BuickB233Route.shouldProtectProfiles("OtherCar", hint(1, 2, 3, 4), false))
        assertFalse(BuickB233Route.shouldProtectProfiles(null, null, false))
        assertFalse(BuickB233Route.shouldProtectProfiles("BUICK", null, false))
    }
}
