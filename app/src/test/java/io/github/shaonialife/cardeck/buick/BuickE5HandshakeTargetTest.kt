package io.github.shaonialife.cardeck.buick

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickE5HandshakeTargetTest {
    @Test fun disabledNeverOverrides() {
        assertFalse(BuickE5HandshakeTarget.shouldForce(false, null, "receiver-alpha", "BUICK_1234"))
        assertFalse(BuickE5HandshakeTarget.shouldForce(false, "receiver-alpha", "receiver-alpha", "other"))
    }
    @Test fun selectedPeerOverridesEvenWithUnknownName() {
        assertTrue(BuickE5HandshakeTarget.shouldForce(true, "receiver-alpha", "receiver-alpha", "VCS-screen"))
        assertTrue(BuickE5HandshakeTarget.shouldForce(true, "receiver-alpha", "receiver-alpha", null))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, "receiver-alpha", "receiver-beta", "BUICK_1234"))
    }
    @Test fun automaticModeRestrictedToBuickNames() {
        assertTrue(BuickE5HandshakeTarget.shouldForce(true, null, "receiver-alpha", "Buick Curve Display"))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, null, "receiver-alpha", "HEADPHONES"))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, null, "receiver-alpha", null))
        assertFalse(BuickE5HandshakeTarget.shouldForce(true, null, null, "BUICK_1234"))
    }
}
