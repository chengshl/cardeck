package io.github.shaonialife.cardeck.bt

import org.junit.Assert.*
import org.junit.Test

class BuickB233ProfilePreserveTest {
    @Test fun exactHint() {
        assertTrue(BuickB233Route.isKnownReceiver("BUICK_2427"))
        assertTrue(BuickB233Route.isKnownReceiver(" buick_1234 "))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK"))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK_12345"))
        assertFalse(BuickB233Route.isKnownReceiver("AUDI_1234"))
        assertFalse(BuickB233Route.isKnownReceiver(null))
    }
    @Test fun perCandidateProtectionDoesNotExemptHeadsets() {
        assertTrue(BuickB233Route.shouldProtectProfiles("BUICK_9999", null, false))
        assertTrue(BuickB233Route.shouldProtectProfiles("RenamedCar", "BUICK_2427", true))
        assertFalse(BuickB233Route.shouldProtectProfiles("Headphones", "BUICK_2427", false))
        assertFalse(BuickB233Route.shouldProtectProfiles("Unknown", "BUICK_2427", false))
        assertFalse(BuickB233Route.shouldProtectProfiles(null, null, false))
    }
}
