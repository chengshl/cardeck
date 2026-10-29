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
     * Isolated L3 lifecycle regression: a receive-thread exception must close
     * its UDP socket and invalidate the advertised port. The second receive
     * verifies that start() can create a fresh, usable listener afterwards.
     * This is synthetic loopback, not Android VPN / Wi-Fi bind validation.
     */
    @Test
    fun unexpectedReceiveExceptionFailsClosedAndAllowsFreshListener() {
        val faultOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val errorLogged = java.util.concurrent.CountDownLatch(1)
        val secondReceived = java.util.concurrent.CountDownLatch(1)
        val listener = AudioReverseControlPort(
            bindNetwork = {},
            log = { line ->
                if (line.contains("反向RTCP包 #1") && faultOnce.compareAndSet(false, true)) {
                    throw IllegalStateException("synthetic receive callback failure")
                }
                if (line.contains("反向 RTCP 接收异常")) errorLogged.countDown()
                if (line.contains("反向RTCP包 #2")) secondReceived.countDown()
            },
        )
        fun send(port: Int) {
            val packet = byteArrayOf(0x80.toByte(), 0xD3.toByte(), 0x00, 0x00)
            java.net.DatagramSocket().use { sender ->
                sender.send(
                    java.net.DatagramPacket(
                        packet,
                        packet.size,
                        java.net.InetAddress.getByName("127.0.0.1"),
                        port,
                    ),
                )
            }
        }
        try {
            val firstPort = listener.start()
            assertTrue(firstPort in 1..65535)
            send(firstPort)
            assertTrue("synthetic receiver failure must be observed", errorLogged.await(
                3, java.util.concurrent.TimeUnit.SECONDS,
            ))
            // Catch logging can precede the receive thread's finally cleanup.
            for (attempt in 1..100) {
                if (listener.port == 0) break
                Thread.sleep(10)
            }
            assertEquals("dead receiver must not advertise a live UDP port", 0, listener.port)
            val secondPort = listener.start()
            assertTrue("new listener must bind", secondPort in 1..65535)
            send(secondPort)
            assertTrue("replacement listener must receive", secondReceived.await(
                3, java.util.concurrent.TimeUnit.SECONDS,
            ))
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
