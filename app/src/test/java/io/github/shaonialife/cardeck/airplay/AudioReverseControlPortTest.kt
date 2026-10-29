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

    /**
     * Deterministic race regression: diagnostic logging can block after a
     * receive exception. The dying socket must already be invalidated, and
     * the old thread must not clear the replacement listener afterwards.
     */
    @Test
    fun blockedFailureLogDoesNotAdvertiseDeadPortOrLoseReplacement() {
        val failureLogEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFailureLog = java.util.concurrent.CountDownLatch(1)
        val replacementReceived = java.util.concurrent.CountDownLatch(1)
        val faultOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = AudioReverseControlPort(
            bindNetwork = {},
            log = { line ->
                if (line.contains("反向RTCP包 #1") && faultOnce.compareAndSet(false, true)) {
                    throw IllegalStateException("synthetic callback failure")
                }
                if (line.contains("反向 RTCP 接收异常")) {
                    failureLogEntered.countDown()
                    check(releaseFailureLog.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        "test diagnostic callback was not released"
                    }
                }
                if (line.contains("反向RTCP包 #2")) replacementReceived.countDown()
            },
        )
        fun send(port: Int) {
            val bytes = byteArrayOf(0x80.toByte(), 0xD3.toByte(), 0x00, 0x00)
            java.net.DatagramSocket().use { sender ->
                sender.send(java.net.DatagramPacket(
                    bytes, bytes.size, java.net.InetAddress.getByName("127.0.0.1"), port,
                ))
            }
        }
        try {
            val originalPort = listener.start()
            assertTrue(originalPort in 1..65535)
            send(originalPort)
            assertTrue("failure log must be reached", failureLogEntered.await(
                3, java.util.concurrent.TimeUnit.SECONDS,
            ))
            // The original receive thread is deliberately held in its error
            // logger, so finally has not yet run.
            assertEquals("dead receiver must be invalid before logging", 0, listener.port)
            val replacementPort = listener.start()
            assertTrue("fresh receiver must bind", replacementPort in 1..65535)
            send(replacementPort)
            assertTrue("replacement must receive while old logger is blocked",
                replacementReceived.await(3, java.util.concurrent.TimeUnit.SECONDS))
            releaseFailureLog.countDown()
            // Give the old thread a chance to complete its finally cleanup.
            Thread.sleep(100)
            assertEquals("old cleanup must not clear replacement", replacementPort, listener.port)
        } finally {
            releaseFailureLog.countDown()
            listener.close()
        }
        assertEquals(0, listener.port)
    }

    /**
     * Startup diagnostics execute after the socket is published but before
     * its receive thread exists. A throwing callback must not leave a
     * nonzero controlPort that SETUP could advertise without a receiver.
     */
    @Test
    fun startupDiagnosticExceptionFailsClosedAndFreshStartReceives() {
        val throwOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        val received = java.util.concurrent.CountDownLatch(1)
        val listener = AudioReverseControlPort(
            bindNetwork = {},
            log = { line ->
                if (line.contains("UDP 已监听") && throwOnce.compareAndSet(true, false)) {
                    throw IllegalStateException("synthetic startup diagnostic failure")
                }
                if (line.contains("反向RTCP包 #1")) received.countDown()
            },
        )
        try {
            assertEquals("startup diagnostic failure must not advertise a port", 0, listener.start())
            assertEquals(0, listener.port)
            val recoveredPort = listener.start()
            assertTrue("fresh start must bind after fail-closed", recoveredPort in 1..65535)
            assertEquals(recoveredPort, listener.port)
            val bytes = byteArrayOf(0x80.toByte(), 0xD3.toByte(), 0x00, 0x00)
            java.net.DatagramSocket().use { sender ->
                sender.send(java.net.DatagramPacket(
                    bytes, bytes.size, java.net.InetAddress.getByName("127.0.0.1"), recoveredPort,
                ))
            }
            assertTrue("fresh receiver must process UDP", received.await(
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
