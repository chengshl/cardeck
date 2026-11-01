package io.github.shaonialife.cardeck.audio

import java.net.DatagramSocket
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVehicleNetworkBindGuardTest {
    @Test
    fun b233RejectedVehicleBindClosesSocketInsteadOfUsingDefaultRoute() {
        val socket = DatagramSocket()
        var failures = 0
        val accepted = bindOwnedAudioDatagramSocket(
            socket,
            { throw SocketException("synthetic bind rejection") },
            failClosed = true,
            onFailure = { failures++ },
        )
        assertFalse(accepted)
        assertTrue(socket.isClosed)
        assertEquals(1, failures)
    }

    @Test
    fun legacyRejectedVehicleBindPreservesBestEffortFallback() {
        DatagramSocket().use { socket ->
            var failures = 0
            val accepted = bindOwnedAudioDatagramSocket(
                socket,
                { throw SocketException("synthetic bind rejection") },
                failClosed = false,
                onFailure = { failures++ },
            )
            assertTrue(accepted)
            assertFalse(socket.isClosed)
            assertEquals(1, failures)
        }
    }

    @Test
    fun successfulBindKeepsB233SocketAvailable() {
        DatagramSocket().use { socket ->
            var bound = false
            val accepted = bindOwnedAudioDatagramSocket(
                socket,
                { bound = true },
                failClosed = true,
                onFailure = { throw AssertionError("successful bind cannot fail") },
            )
            assertTrue(accepted)
            assertTrue(bound)
            assertFalse(socket.isClosed)
        }
    }

    @Test
    fun absentExplicitBinderDoesNotCloseSocket() {
        DatagramSocket().use { socket ->
            assertTrue(bindOwnedAudioDatagramSocket(
                socket, null, failClosed = true,
                onFailure = { throw AssertionError("no bind attempted") },
            ))
            assertFalse(socket.isClosed)
        }
    }
}
