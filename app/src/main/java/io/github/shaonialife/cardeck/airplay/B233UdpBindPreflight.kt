package io.github.shaonialife.cardeck.airplay

import java.net.DatagramSocket

/**
 * T89, manual in-app preflight only. Uses the exact AudioReverseControlPort
 * implementation and caller-supplied production vehicle Network.bindSocket.
 * No media, RTSP, iAP2, UDP transmit, VPN reconfiguration or network request.
 */
internal class B233UdpBindPreflight(
    private val bindNetwork: (DatagramSocket) -> Unit,
    private val log: (String) -> Unit,
    private val networkStillValid: () -> Boolean,
    private val onBindError: (Throwable) -> String = { "UNKNOWN" },
) {
    internal data class Result(
        val status: String,
        val errno: String,
        val heldMs: Long,
        val wasListening: Boolean,
        val listenerAliveAtEnd: Boolean,
    ) {
        val passed: Boolean get() = status == "pass" && listenerAliveAtEnd
    }

    fun run(holdMillis: Long = 25_000L): Result {
        require(holdMillis > 0L)
        var errno = "none"
        val receiver = AudioReverseControlPort(
            bindNetwork = { socket ->
                try {
                    bindNetwork(socket)
                } catch (e: Exception) {
                    errno = onBindError(e)
                    throw e
                }
            },
            log = log,
        )
        var listeningSinceNanos: Long? = null
        var started = false
        var endedAlive = false
        var status = "failed"
        try {
            if (!networkStillValid()) {
                status = "network_missing"
            } else {
                val port = receiver.start()
                started = port > 0
                if (!started) {
                    status = "bind_failed"
                } else {
                    listeningSinceNanos = System.nanoTime()
                    val deadlineNanos = checkNotNull(listeningSinceNanos) + holdMillis * 1_000_000L
                    status = "pass"
                    while (System.nanoTime() < deadlineNanos) {
                        if (!networkStillValid()) {
                            status = "network_changed"
                            break
                        }
                        if (receiver.port != port) {
                            status = "listener_exited"
                            break
                        }
                        val nanosLeft = deadlineNanos - System.nanoTime()
                        if (nanosLeft <= 0L) break
                        Thread.sleep(minOf(250L, (nanosLeft / 1_000_000L).coerceAtLeast(1L)))
                    }
                    endedAlive = receiver.port == port && networkStillValid()
                    if (status == "pass" && !endedAlive) status = "listener_exited"
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            status = "interrupted"
        } finally {
            receiver.close()
        }
        return Result(
            status = status,
            errno = errno,
            heldMs = listeningSinceNanos?.let {
                ((System.nanoTime() - it) / 1_000_000L).coerceAtLeast(0L)
            } ?: 0L,
            wasListening = started,
            listenerAliveAtEnd = endedAlive,
        )
    }
}
