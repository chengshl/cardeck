package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AudioReverseControlPortTest {
    @Test
    fun readsOnlyControlHeaderMetadata() {
        val example = byteArrayOf(0x80.toByte(), 0xD3.toByte(), 0x00, 0x07)
        val parsed = AudioReverseControlPort.inspectHeader(example, example.size)
        assertEquals(2, parsed?.version)
        assertEquals(0xD3, parsed?.packetType)
        assertEquals(32, parsed?.declaredBytes)
    }

    @Test
    fun d5RequestSequenceIsNotReportedAsRtcpLength() {
        val request = byteArrayOf(
            0x80.toByte(), 0xD5.toByte(), 0x00, 0x01, 0x03, 0x80.toByte(), 0x00, 0x02,
        )
        val header = AudioReverseControlPort.inspectHeader(request, request.size)
        assertEquals(2, header?.version)
        assertEquals(0xD5, header?.packetType)
        assertNull(header?.declaredBytes)
    }

    @Test
    fun rejectsMissingOrOutOfBoundsControlHeader() {
        assertNull(AudioReverseControlPort.inspectHeader(byteArrayOf(0x80.toByte()), 1))
        assertNull(AudioReverseControlPort.inspectHeader(byteArrayOf(0x80.toByte(), 0xD3.toByte()), 3))
        val shortHeader = AudioReverseControlPort.inspectHeader(
            byteArrayOf(0x80.toByte(), 0xD3.toByte()), 2,
        )
        assertEquals(2, shortHeader?.version)
        assertEquals(0xD3, shortHeader?.packetType)
        assertNull(shortHeader?.declaredBytes)
    }

    @Test
    fun opensEphemeralLocalUdpPortAndClosesIt() {
        val receiver = AudioReverseControlPort(bindNetwork = {}, log = {})
        val port = receiver.start()
        try {
            assertTrue(port in 1..65535)
            assertEquals(port, receiver.start())
            assertEquals(port, receiver.port)
        } finally {
            receiver.close()
        }
        assertEquals(0, receiver.port)
    }

    /**
     * T89 B233 PCM reverse-RTCP is only a valid protocol experiment when its
     * phone-local UDP listener actually binds. Both T85 and T88 previously
     * logged Android Network.bindSocket EPERM with VPN enabled. Without that
     * bind, the existing runAudioStream() path returns before audio SETUP:
     * never mistake a fallback profile or absent audio for a negative trial.
     */
    @Test
    fun t89VpnEpermCannotAdvertiseControlPort() {
        val lines = mutableListOf<String>()
        val receiver = AudioReverseControlPort(
            bindNetwork = { throw java.net.SocketException("EPERM in synthetic WiFi bind") },
            log = { lines += it },
        )
        try {
            assertEquals("failed bind must not claim a usable UDP port", 0, receiver.start())
            assertEquals(0, receiver.port)
            assertTrue(lines.any { it.contains("网络绑定失败") && it.contains("SocketException") })
            assertTrue(lines.none { it.contains("已监听") })
        } finally {
            receiver.close()
        }
    }

    /**
     * runAudioStream creates a NEW listener for each negotiation attempt.
     * Reusing one listener in this regression would test a retry path that
     * production does not actually take after EPERM. Model two separate
     * attempts instead: the first fails closed; a later fresh attempt can
     * advertise a port only if its own synthetic network bind succeeds.
     * This does not establish that Android VPN/Wi-Fi policy will recover.
     */
    @Test
    fun t89FreshNegotiationAfterEpermCannotReusePhantomPort() {
        var attempts = 0
        val lines = mutableListOf<String>()
        val bind: (java.net.DatagramSocket) -> Unit = {
            attempts++
            if (attempts == 1) throw java.net.SocketException("synthetic EPERM")
        }
        val first = AudioReverseControlPort(bindNetwork = bind, log = { lines += it })
        try {
            assertEquals(0, first.start())
            assertEquals(0, first.port)
            assertTrue(lines.none { it.contains("已监听") })
        } finally {
            first.close()
        }

        val second = AudioReverseControlPort(bindNetwork = bind, log = { lines += it })
        val recoveredPort = second.start()
        try {
            assertEquals(2, attempts)
            assertTrue(recoveredPort in 1..65535)
            assertEquals(0, first.port)
            assertEquals(recoveredPort, second.port)
            assertEquals(recoveredPort, second.start())
            assertEquals(2, attempts)
            assertEquals(1, lines.count { it.contains("已监听") })
        } finally {
            second.close()
        }
        assertEquals(0, second.port)
    }

    /**
     * T89 offline socket-path preflight: verify the receive-only listener
     * actually accepts a synthetic RTCP datagram on its advertised port.
     * Loopback success is NOT proof that Android Network.bindSocket succeeds
     * on the B233 Wi-Fi network when a user VPN is active.
     */
    @Test
    fun t89LoopbackDatagramReachesReceiveOnlyListener() {
        val received = java.util.concurrent.CountDownLatch(1)
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val listener = AudioReverseControlPort(
            bindNetwork = {},
            log = { line ->
                lines.add(line)
                if (line.contains("反向RTCP包 #1")) received.countDown()
            },
        )
        val port = listener.start()
        try {
            assertTrue("local listener must bind an ephemeral port", port in 1..65535)
            val packet = ByteArray(32)
            packet[0] = 0x80.toByte()
            packet[1] = 0xD3.toByte()
            packet[3] = 0x07 // RTCP length: (7 + 1) * 4 = 32 bytes
            java.net.DatagramSocket().use { sender ->
                sender.send(
                    java.net.DatagramPacket(
                        packet,
                        packet.size,
                        java.net.InetAddress.getByName("127.0.0.1"),
                        port,
                    ),
                )
                assertTrue(
                    "local reverse UDP datagram must reach receive-only listener",
                    received.await(3, java.util.concurrent.TimeUnit.SECONDS),
                )
            }
            assertTrue(lines.any { it.contains("pt=211") && it.contains("declaredBytes=32") })
        } finally {
            listener.close()
        }
        assertEquals(0, listener.port)
    }

    @Test
    fun failureToBindPreventsAdvertisingPort() {
        val receiver = AudioReverseControlPort(
            bindNetwork = { throw IllegalStateException("test bind failure") },
            log = {},
        )
        assertEquals(0, receiver.start())
        assertEquals(0, receiver.port)
        receiver.close()
    }
}
