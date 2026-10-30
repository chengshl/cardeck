package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233UdpBindPreflightTest {
    @Test
    fun holdsRealReceiveOnlyListenerWithoutSendingPackets() {
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        var binds = 0
        val result = B233UdpBindPreflight(
            bindNetwork = { binds++ },
            log = { logs.add(it) },
            networkStillValid = { true },
        ).run(25L)
        assertEquals(1, binds)
        assertEquals("pass", result.status)
        assertTrue(result.wasListening)
        assertTrue(result.listenerAliveAtEnd)
        assertTrue(result.heldMs >= 25L)
        assertTrue(logs.any { it.contains("UDP 已监听") })
        assertFalse(logs.any { it.contains("反向RTCP包 #") })
    }

    @Test
    fun reportsBindEpermAsInvalidExperimentWithoutOpeningPort() {
        val result = B233UdpBindPreflight(
            bindNetwork = { throw java.net.SocketException("synthetic EPERM") },
            log = {},
            networkStillValid = { true },
            onBindError = { "EPERM" },
        ).run(25L)
        assertEquals("bind_failed", result.status)
        assertEquals("EPERM", result.errno)
        assertFalse(result.wasListening)
        assertFalse(result.listenerAliveAtEnd)
        assertFalse(result.passed)
    }

    @Test
    fun refusesWhenProductionVehicleNetworkIsNotPresent() {
        var binds = 0
        val result = B233UdpBindPreflight(
            bindNetwork = { binds++ },
            log = {},
            networkStillValid = { false },
        ).run(25L)
        assertEquals("network_missing", result.status)
        assertEquals(0, binds)
        assertFalse(result.wasListening)
    }

    @Test
    fun networkLossDuringHoldDoesNotCountAsPassed() {
        var checks = 0
        val result = B233UdpBindPreflight(
            bindNetwork = {},
            log = {},
            networkStillValid = { ++checks <= 2 },
        ).run(25L)
        assertEquals("network_changed", result.status)
        assertFalse(result.passed)
    }
}
