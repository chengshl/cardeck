package io.github.shaonialife.cardeck.buick

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickE5HandshakeTargetTest {
    @Test fun disabledNeverOverrides() {
        assertFalse(BuickE5HandshakeTarget.shouldForce(false, null, "11:22", "BUICK_1234"))
        assertFalse(BuickE5HandshakeTarget.shouldForce(false, "11:22", "11:22", "other"))
    }
    @Test fun selectedPeerOverridesEvenWithUnknownName() {
        assertTrue(BuickE5HandshakeTarget.shouldForce(true, "11:22", "11:22", "VCS-screen"))
        assertTrue(BuickE5HandshakeTarget.shouldForce(true, "11:22", "11:22", null))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, "11:22", "11:33", "BUICK_1234"))
    }
    @Test fun automaticModeRestrictedToBuickNames() {
        assertTrue(BuickE5HandshakeTarget.shouldForce(true, null, "11:22", "Buick Curve Display"))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, null, "11:22", "HEADPHONES"))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, null, "11:22", null))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, null, null, "BUICK_1234"))
    }
}
