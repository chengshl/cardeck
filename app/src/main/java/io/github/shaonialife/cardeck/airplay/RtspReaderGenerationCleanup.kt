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
    fun isCurrentReader(
        sourceGeneration: Int,
        activeGeneration: Int,
        sourceReader: Any,
        activeReader: Any?,
    ): Boolean = sourceGeneration == activeGeneration && sourceReader === activeReader

    fun <T> drainIfCurrent(
        exitingGeneration: Int,
        activeGeneration: Int,
        pending: MutableMap<Int, T>,
    ): List<T>? {
        if (exitingGeneration != activeGeneration) return null
        return drainAll(pending)
    }
}
