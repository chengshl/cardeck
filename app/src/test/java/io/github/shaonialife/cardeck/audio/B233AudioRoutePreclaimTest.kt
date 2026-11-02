package io.github.shaonialife.cardeck.audio

import java.net.DatagramSocket
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233AudioRoutePreclaimTest {
    @Test fun missingVehicleNetworkSkipsClaimAndSetupWithoutKeepingSocketOpen() {
        var socket: DatagramSocket? = null
        var failures = 0
        assertFalse(preflightB233AudioRouteBeforeClaim(
            isBuickB233 = true,
            bindToVehicleNetwork = null,
            onFailure = { failures++ },
            newSocket = { DatagramSocket().also { socket = it } },
        ))
        assertTrue(socket!!.isClosed)
        assertEquals(0, failures) // No attempt to bind; not a remote rejection.
    }

    @Test fun rejectedVehicleBindSkipsClaimAndSetupAndClosesProbe() {
        var socket: DatagramSocket? = null
        var failures = 0
        assertFalse(preflightB233AudioRouteBeforeClaim(
            isBuickB233 = true,
            bindToVehicleNetwork = { throw SocketException("synthetic EPERM, not logged") },
            onFailure = { failures++ },
            newSocket = { DatagramSocket().also { socket = it } },
        ))
        assertTrue(socket!!.isClosed)
        assertEquals(1, failures)
    }

    @Test fun successfulLocalBindAllowsAttemptButAlwaysClosesProbe() {
        var socket: DatagramSocket? = null
        var bindCalls = 0
        assertTrue(preflightB233AudioRouteBeforeClaim(
            isBuickB233 = true,
            bindToVehicleNetwork = { bindCalls++ },
            onFailure = { throw AssertionError("unexpected failure") },
            newSocket = { DatagramSocket().also { socket = it } },
        ))
        assertEquals(1, bindCalls)
        assertTrue(socket!!.isClosed)
    }

    @Test fun legacyReceiverDoesNotAllocateOrBindNewProbe() {
        assertTrue(preflightB233AudioRouteBeforeClaim(
            isBuickB233 = false,
            bindToVehicleNetwork = { throw AssertionError("must not bind legacy") },
            onFailure = { throw AssertionError("must not fail legacy") },
            newSocket = { throw AssertionError("must not allocate legacy") },
        ))
    }

    @Test fun localSocketCreationFailureCannotProceedToAudioClaim() {
        var failures = 0
        assertFalse(preflightB233AudioRouteBeforeClaim(
            isBuickB233 = true,
            bindToVehicleNetwork = { throw AssertionError("must not bind") },
            onFailure = { failures++ },
            newSocket = { throw SocketException("synthetic local socket failure") },
        ))
        assertEquals(1, failures)
    }
}
