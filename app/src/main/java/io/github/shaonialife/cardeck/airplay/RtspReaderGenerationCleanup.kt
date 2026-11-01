package io.github.shaonialife.cardeck.airplay

/**
 * Retire only the pending RTSP responses owned by the current reader generation.
 *
 * Caller MUST hold the same lock used when incrementing readerGeneration and
 * registering a new pending CSeq. Checking the generation before acquiring that
 * lock is unsafe: a new session can register a waiter before the old reader clears it.
 *
 * null = stale reader, do not emit current-reader exit diagnostics.
 * empty list = current reader exited without any pending request.
 */
internal object RtspReaderGenerationCleanup {
    /** Caller holds rtspWriteLock; drain must be atomic with reader-generation changes. */
    fun <T> drainAll(pending: MutableMap<Int, T>): List<T> {
        val waiting = pending.values.toList()
        pending.clear()
        return waiting
    }

    /**
     * Used while holding rtspWriteLock for B233 reader dispatch and ACK.
     * A generation match alone is insufficient when a new socket replaces the
     * reader; reference identity prevents old frames reaching the new session.
     */
    /** The caller holds rtspWriteLock for B233 request/reader ownership. */
    fun isCurrentSocket(captured: Any, published: Any?): Boolean = captured === published

    fun isCurrentReader(
        sourceGeneration: Int,
        activeGeneration: Int,
        sourceReader: Any,
        activeReader: Any?,
    ): Boolean = sourceGeneration == activeGeneration && isCurrentSocket(sourceReader, activeReader)

    /**
     * A terminated B233 reader must retire its own published socket, not only
     * its response waiters. Caller holds rtspWriteLock and clears the socket,
     * reader and cipher only when this returns a non-null list.
     * The extra socket identity check also covers publication before the new
     * reader has started (and incremented readerGeneration).
     */
    fun <T> retireIfCurrent(
        exitingGeneration: Int,
        activeGeneration: Int,
        exitingSocket: Any,
        activeReader: Any?,
        publishedSocket: Any?,
        pending: MutableMap<Int, T>,
    ): List<T>? {
        if (!isCurrentReader(exitingGeneration, activeGeneration, exitingSocket, activeReader)) return null
        if (!isCurrentSocket(exitingSocket, publishedSocket)) return null
        return drainAll(pending)
    }

    fun <T> drainIfCurrent(
        exitingGeneration: Int,
        activeGeneration: Int,
        pending: MutableMap<Int, T>,
    ): List<T>? {
        if (exitingGeneration != activeGeneration) return null
        return drainAll(pending)
    }
}
