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
        if (expectedEpoch != epoch) {
            false
        } else {
            val accepted = publish()
            // A successful publication consumes this epoch. Concurrent TCP
            // attempts which started from the same snapshot must not replace
            // the already-published socket and orphan its reader/transport.
            if (accepted) epoch++
            accepted
        }
    }

    /**
     * Keep a reader's socket-identity check and installation atomic with
     * socket publication and retirement. No network connect/read happens here.
     * A request that captured a retired socket must not install a reader over
     * the replacement connection.
     */
    fun runIfCurrent(
        isCurrent: () -> Boolean,
        action: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!isCurrent()) {
            false
        } else {
            action()
            true
        }
    }

    fun retire(retireCurrent: () -> Unit) {
        synchronized(lock) {
            epoch++
            retireCurrent()
        }
    }
}


/**
 * Separates a retired RTSP reader's late packets from its successor's pending
 * CSeq replies and unsolicited-request acknowledgement path.
 *
 * The caller must use the SAME lock as reader replacement/retirement and the
 * RTSP request writer. The callback is intentionally part of this critical
 * section; otherwise a reader can pass the ownership check and be superseded
 * before it delivers the old packet to the new session.
 */
internal object RtspReaderDispatchGate {
    fun dispatchIfCurrent(
        readerLock: Any,
        isCurrentReader: () -> Boolean,
        dispatch: () -> Unit,
    ): Boolean = synchronized(readerLock) {
        if (!isCurrentReader()) {
            false
        } else {
            dispatch()
            true
        }
    }
}


/**
 * Request-side ownership check, evaluated under rtspWriteLock immediately
 * before CSeq registration and cipher encryption. A request may have captured
 * a socket before close/reconnect even when its earlier startReader succeeded.
 * Rejecting it prevents a stale request from consuming the replacement
 * cipher's outbound nonce or registering a waiter on the new session.
 *
 * This helper does not acquire the lock: the caller already holds it for
 * CSeq allocation, cipher encryption and the socket write.
 */
internal object RtspRequestWriteOwnership {
    fun isCurrent(
        requestSocket: Any,
        publishedSocket: Any?,
        activeReaderSocket: Any?,
        requestSocketClosed: Boolean,
    ): Boolean =
        requestSocket === publishedSocket &&
            requestSocket === activeReaderSocket &&
            !requestSocketClosed
}
