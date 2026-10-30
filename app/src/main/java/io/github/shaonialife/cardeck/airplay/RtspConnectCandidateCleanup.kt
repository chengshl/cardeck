package io.github.shaonialife.cardeck.airplay

/**
 * Owns a newly allocated RTSP TCP candidate until publication succeeds.
 *
 * B233 connect retries must close a candidate when TCP connect throws or the
 * endpoint changes. A successfully published socket is owned by AirPlayClient
 * and must remain open. No network/protocol work happens in this helper.
 */
internal object RtspConnectCandidateCleanup {
    fun <T : java.io.Closeable> publishOrClose(
        candidate: T,
        cleanupOnFailure: Boolean,
        attempt: () -> Boolean,
    ): Boolean {
        var published = false
        try {
            published = attempt()
            return published
        } finally {
            if (cleanupOnFailure && !published) {
                runCatching { candidate.close() }
            }
        }
    }
}
