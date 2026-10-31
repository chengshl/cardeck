package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.DatagramSocket

/** Regression: a failed audio RTP UDP connect must not leak its native socket. */
class AudioDatagramSocketOwnershipTest {
    @Test
    fun failedConnectReleasesNewlyCreatedSocketBeforeRethrowing() {
        val client = DatagramSocket()
        try {
            connectOwnedAudioDatagramSocket(client, "127.0.0.1", -1)
            throw AssertionError("invalid UDP destination port unexpectedly accepted")
        } catch (_: IllegalArgumentException) {
            assertTrue("failed RTP connect leaked UDP socket", client.isClosed)
        } finally {
            client.close()
        }
    }

    @Test
    fun successfulConnectTransfersSameOpenSocketToAudioSender() {
        val client = DatagramSocket()
        try {
            val connected = connectOwnedAudioDatagramSocket(client, "127.0.0.1", 6000)
            assertSame("RTP socket identity changed", client, connected)
            assertFalse("successful RTP socket was prematurely closed", client.isClosed)
            assertTrue("RTP socket was not connected", client.isConnected)
        } finally {
            client.close()
        }
    }
}
