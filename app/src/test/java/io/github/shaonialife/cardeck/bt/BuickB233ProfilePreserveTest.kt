package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickB233ProfilePreserveTest {
    @Test fun narrowlyMatchesFourDigitBuickHint() {
        assertTrue(BuickB233Route.isKnownReceiver("BUICK_1234"))
        assertTrue(BuickB233Route.isKnownReceiver(" buick_9876 "))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK"))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK_12345"))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK_123"))
        assertFalse(BuickB233Route.isKnownReceiver("AUDI_1234"))
        assertFalse(BuickB233Route.isKnownReceiver(null))
    }

    @Test fun protectsOnlyB233CandidateOrExplicitlyMatchedSelection() {
        assertTrue(BuickB233Route.shouldProtectProfiles("BUICK_9999", null, false))
        assertTrue(BuickB233Route.shouldProtectProfiles("RenamedCar", "BUICK_1234", true))
        assertFalse(BuickB233Route.shouldProtectProfiles("Headphones", "BUICK_1234", false))
        assertFalse(BuickB233Route.shouldProtectProfiles("OtherCar", "BUICK_1234", false))
        assertFalse(BuickB233Route.shouldProtectProfiles(null, null, false))
        assertFalse(BuickB233Route.shouldProtectProfiles("BUICK", null, false))
    }
}
