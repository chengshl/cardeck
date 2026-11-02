package io.github.shaonialife.cardeck.audio

import java.net.DatagramSocket
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVehicleNetworkBindGuardTest {
    @Test
    fun b233AudioDiagnosticsDoNotExposePeerOrPlatformExceptionText() {
        val peer = audioDiagnosticPeer("192.0.2.41", 6011, true)
        assertEquals("vehicle_port=6011", peer)
        assertFalse(peer.contains("192.0.2.41"))
        val failure = audioDiagnosticFailure(
            SocketException("sendto failed 192.0.2.41 path=/private/device"),
            true,
        )
        assertEquals("SocketException", failure)
        assertFalse(failure.contains("192.0.2.41"))
        assertFalse(failure.contains("/private/device"))
    }

    @Test
    fun legacyAudioDiagnosticsPreserveOriginalDetail() {
        assertEquals("192.0.2.41:6011", audioDiagnosticPeer("192.0.2.41", 6011, false))
        assertEquals("legacy detail", audioDiagnosticFailure(SocketException("legacy detail"), false))
    }

    @Test
    fun b233PcmRequiresLocalRtcpTransportBeforeRtpWhenControlPortAdvertised() {
        assertTrue(requiresB233AudioSyncStartup(true, true, 6011))
        assertFalse(requiresB233AudioSyncStartup(true, false, 6011)) // B233 modern AAC
        assertFalse(requiresB233AudioSyncStartup(true, true, 0)) // no negotiated port
        assertFalse(requiresB233AudioSyncStartup(false, true, 6011)) // legacy
        assertFalse(requiresB233AudioSyncStartup(false, false, 6011))
    }

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
    fun b233AbsentVehicleNetworkClosesSocketInsteadOfUsingDefaultRoute() {
        val socket = DatagramSocket()
        var failures = 0
        val accepted = bindOwnedAudioDatagramSocket(
            socket, null, failClosed = true,
            onFailure = { failures++ },
        )
        assertFalse(accepted)
        assertTrue(socket.isClosed)
        assertEquals(0, failures) // No binder was attempted; distinct from a rejected bind.
    }

    @Test
    fun legacyAbsentVehicleBinderPreservesBestEffortSocket() {
        DatagramSocket().use { socket ->
            assertTrue(bindOwnedAudioDatagramSocket(
                socket, null, failClosed = false,
                onFailure = { throw AssertionError("no bind attempted") },
            ))
            assertFalse(socket.isClosed)
        }
    }
}
