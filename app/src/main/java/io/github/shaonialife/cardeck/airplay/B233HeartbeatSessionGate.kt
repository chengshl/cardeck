package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.atomic.AtomicLong

/**
 * B233 heartbeat workers must belong to the RTSP session that started them.
 *
 * The old feedback/UDP keepAlive loops only checked socket?.isClosed. After
 * close() and a quick reconnect on the same AirPlayClient, that expression can
 * become true again, letting an old worker send into the replacement session
 * (or keep pinging the retired host/port). Capture a token before scheduling
 * the worker; close() invalidates all tokens before retiring the socket.
 *
 * runIfCurrent() serializes the final network action with retire(): close()
 * cannot retire the generation midway through an old worker's socket capture/write.
 * This gate does not alter heartbeat intervals, packets or network routing.
 */
internal class B233HeartbeatSessionGate {
    private val generation = AtomicLong(0L)

    fun snapshot(): Long = generation.get()

    @Synchronized
    fun retire(): Long = generation.incrementAndGet()

    fun isCurrent(token: Long): Boolean = token == generation.get()

    @Synchronized
    fun runIfCurrent(token: Long, action: () -> Unit): Boolean {
        if (!isCurrent(token)) return false
        action()
        return true
    }
}
