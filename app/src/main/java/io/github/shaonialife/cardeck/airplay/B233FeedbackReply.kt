package io.github.shaonialife.cardeck.airplay

/**
 * Feedback RTSP reply interpretation for B233 diagnostics and sample-time liveness.
 *
 * A peer-controlled reason phrase containing "200" is not a successful response.
 * Strict transport 200 still does not prove audio resource ownership or playback.
 * No RTSP request, retry, timing, or audio routing behavior is changed.
 */
internal object B233FeedbackReply {
    fun isTransportAccepted(statusLine: String, isBuickB233: Boolean): Boolean =
        B233ModeReplySummary.isTransportAccepted(statusLine, isBuickB233)

    fun safeStatus(statusLine: String, isBuickB233: Boolean): String =
        B233ModeReplySummary.safeTransportStatus(statusLine, isBuickB233)
}
