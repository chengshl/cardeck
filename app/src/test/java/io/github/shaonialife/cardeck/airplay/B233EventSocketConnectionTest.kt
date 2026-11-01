package io.github.shaonialife.cardeck.airplay

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class B233EventSocketConnectionTest {
    @Test fun addressResolutionFailureClosesUnpublishedSocket() {
        val candidate = Socket()
        val failure = UnknownHostException("synthetic-resolution-failure")
        try {
            B233EventSocketConnection.connect(candidate, 500) { throw failure }
            fail("Expected resolution failure")
        } catch (actual: UnknownHostException) {
            assertSame(failure, actual)
        }
        assertTrue(candidate.isClosed)
    }

    @Test fun tcpConnectFailureClosesUnpublishedSocket() {
        val failure = IOException("synthetic-connect-failure")
        val candidate = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                throw failure
            }
        }
        try {
            B233EventSocketConnection.connect(candidate, 500) {
                InetSocketAddress(InetAddress.getLoopbackAddress(), 12345)
            }
            fail("Expected connect failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        assertTrue(candidate.isClosed)
    }

    @Test fun successfulTcpConnectTransfersLiveSocketToCaller() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 2000
            Socket().use { candidate ->
                val returned = B233EventSocketConnection.connect(candidate, 2000) {
                    InetSocketAddress(loopback, server.localPort)
                }
                server.accept().use { accepted ->
                    assertSame(candidate, returned)
                    assertTrue(returned.isConnected)
                    assertFalse(returned.isClosed)
                    assertTrue(accepted.isConnected)
                }
            }
        }
    }
    @Test fun sessionCloseRetiresPublishedEventSocket() {
        val owner = B233EventSocketOwner()
        val socket = Socket()
        owner.publish(socket)
        assertFalse(socket.isClosed)
        assertTrue(owner.retire())
        assertTrue(socket.isClosed)
        assertFalse(owner.retire())
    }

    @Test fun reconnectClosesOldSocketWithoutRetiringReplacement() {
        val owner = B233EventSocketOwner()
        val old = Socket()
        val replacement = Socket()
        owner.publish(old)
        owner.publish(replacement)
        assertTrue(old.isClosed)
        assertFalse(replacement.isClosed)
        assertFalse(owner.retireIfCurrent(old))
        assertFalse(replacement.isClosed)
        assertTrue(owner.retire())
        assertTrue(replacement.isClosed)
    }

    @Test fun activeReaderExitReleasesItsOwnedSocket() {
        val owner = B233EventSocketOwner()
        val socket = Socket()
        owner.publish(socket)
        assertTrue(owner.retireIfCurrent(socket))
        assertTrue(socket.isClosed)
        assertFalse(owner.retireIfCurrent(socket))
    }
}
