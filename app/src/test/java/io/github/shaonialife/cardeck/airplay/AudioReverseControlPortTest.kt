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
     * An EPERM from a previous attempt must not leave a phantom advertised
     * port or permanently poison the listener. This tests only a synthetic
     * bind callback; it does not prove Android VPN/Wi-Fi routing will recover.
     */
    @Test
    fun t89TransientEpermCanRetryWithoutAdvertisingStalePort() {
        var attempts = 0
        val lines = mutableListOf<String>()
        val receiver = AudioReverseControlPort(
            bindNetwork = {
                attempts++
                if (attempts == 1) throw java.net.SocketException("synthetic EPERM")
            },
            log = { lines += it },
        )
        assertEquals(0, receiver.start())
        assertEquals(0, receiver.port)
        assertTrue(lines.none { it.contains("已监听") })

        val recoveredPort = receiver.start()
        try {
            assertEquals(2, attempts)
            assertTrue(recoveredPort in 1..65535)
            assertEquals(recoveredPort, receiver.port)
            assertEquals(recoveredPort, receiver.start())
            assertEquals(2, attempts)
            assertEquals(1, lines.count { it.contains("已监听") })
        } finally {
            receiver.close()
        }
        assertEquals(0, receiver.port)
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
