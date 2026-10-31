package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch

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

    @Test
    fun retiredSessionRejectsLateRtpSocketAndClosesIt() {
        val gate = AudioDatagramSessionGate()
        gate.retire { }
        val socket = DatagramSocket()
        var installed = false
        try {
            assertFalse(gate.publish(socket, { true }) { installed = true })
            assertFalse("retired socket was installed", installed)
            assertTrue("late RTP socket leaked after stop", socket.isClosed)
        } finally {
            socket.close()
        }
    }

    @Test
    fun publishedRtcpSocketIsClosedAndLateCaptureCannotRestart() {
        val gate = AudioDatagramSessionGate()
        val socket = DatagramSocket()
        var published: DatagramSocket? = null
        var captureStarted = false
        try {
            assertTrue(gate.publish(socket, { true }) { published = socket })
            gate.retire {
                published?.close()
                published = null
            }
            assertTrue("published RTCP socket survived stop", socket.isClosed)
            assertFalse(gate.runIfActive({ true }) { captureStarted = true })
            assertFalse("retired session restarted AudioRecord", captureStarted)
        } finally {
            socket.close()
        }
    }

    @Test
    fun concurrentPublicationAndRetirementNeverLeaveAnOpenSocket() {
        repeat(32) {
            val gate = AudioDatagramSessionGate()
            val socket = DatagramSocket()
            val start = CountDownLatch(1)
            var installed: DatagramSocket? = null
            val publisher = Thread {
                start.await()
                gate.publish(socket, { true }) { installed = socket }
            }
            val stopper = Thread {
                start.await()
                gate.retire {
                    installed?.close()
                    installed = null
                }
            }
            try {
                publisher.start()
                stopper.start()
                start.countDown()
                publisher.join(5_000)
                stopper.join(5_000)
                assertFalse("publisher remained blocked", publisher.isAlive)
                assertFalse("stopper remained blocked", stopper.isAlive)
                assertTrue("concurrent stop/start leaked UDP socket", socket.isClosed)
            } finally {
                start.countDown()
                publisher.join(5_000)
                stopper.join(5_000)
                socket.close()
            }
        }
    }

    @Test
    fun stoppedFlagRejectsLateSocketAndEncoderBeforeRetireCompletes() {
        val gate = AudioDatagramSessionGate()
        val socket = DatagramSocket()
        var encoderPublished = false
        var socketPublished = false
        try {
            // stop() flips its atomic flag before it reaches the socket cleanup.
            assertFalse(gate.publish(socket, { false }) { socketPublished = true })
            assertTrue("late socket must close before retire() runs", socket.isClosed)
            assertFalse(gate.runIfActive({ false }) { encoderPublished = true })
            assertFalse(socketPublished)
            assertFalse("late AAC encoder/capture was published after stop", encoderPublished)
        } finally {
            socket.close()
        }
    }
}
