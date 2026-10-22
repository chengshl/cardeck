package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickB233RouteTest {
    @Test
    fun knownBuickB233MayUseOutboundWithoutSdp() {
        assertTrue(BuickB233Route.isKnownReceiver("BUICK_" + "1234"))
        assertTrue(BuickB233Route.isKnownReceiver( " + "BUICK_" + "1234" + " "))
    }

    @Test
    fun otherPairedDevicesKeepTheirOriginalDiscoveryAndInboundBehavior() {
        assertFalse(BuickB233Route.isKnownReceiver(null))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK_" + "123"))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK_" + "12345"))
        assertFalse(BuickB233Route.isKnownReceiver("BUICK"))
        assertFalse(BuickB233Route.isKnownReceiver("DiPlay"))
        assertFalse(BuickB233Route.isKnownReceiver("Audi MIB3"))
        assertFalse(BuickB233Route.isKnownReceiver("Wireless Headset"))
    }
}
