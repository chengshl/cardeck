package io.github.shaonialife.cardeck.airplay

/**
 * B233-only strict RTSP transport verdict for the receiver's audio SETUP.
 *
 * The receiver can include untrusted free-form reason text. Transport status
 * 200 is necessary but does not prove audio resource ownership, accepted
 * format, UDP port availability or audible playback.
 *
 * Reuse the audited strict parser from B233 pairing/modesChanged. Preserve
 * legacy non-B233 wire/diagnostic/acceptance behavior exactly.
 */
internal object B233AudioSetupReply {
    fun isTransportAccepted(statusLine: String, isBuickB233: Boolean): Boolean =
        B233ModeReplySummary.isTransportAccepted(statusLine, isBuickB233)

    fun safeStatus(statusLine: String, isBuickB233: Boolean): String =
        B233ModeReplySummary.safeTransportStatus(statusLine, isBuickB233)

    fun safeError(error: Throwable, isBuickB233: Boolean): String =
        B233ModeReplySummary.safeTransportError(error, isBuickB233)
}
