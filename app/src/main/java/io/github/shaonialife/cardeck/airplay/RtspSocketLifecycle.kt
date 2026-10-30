package io.github.shaonialife.cardeck.airplay

/**
 * Owns the lifetime of a single RTSP connection publication epoch.
 *
 * Connecting to the peer is intentionally done outside this lock. A close()
 * invalidates any connection attempt which began before it. Publishing and
 * retiring the shared socket reference are serialized through this gate.
 *
 * This does not assert why B233 closed an existing RTSP session.
 */
internal class RtspSocketLifecycle {
    private val lock = Any()
    private var epoch = 0L

    fun snapshot(): Long = synchronized(lock) { epoch }

    fun tryPublish(
        expectedEpoch: Long,
        publish: () -> Boolean,
    ): Boolean = synchronized(lock) {
        expectedEpoch == epoch && publish()
    }

    fun retire(retireCurrent: () -> Unit) {
        synchronized(lock) {
            epoch++
            retireCurrent()
        }
    }
}
