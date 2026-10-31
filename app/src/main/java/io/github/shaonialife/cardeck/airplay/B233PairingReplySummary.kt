package io.github.shaonialife.cardeck.airplay

/**
 * B233 pairing responses may contain cryptographic handshake material.
 * Log only a fixed protocol status and payload length; never echo TLV bytes,
 * response reason phrases, identifiers, or arbitrary peer-supplied strings.
 * This does not affect RTSP parsing or pairing decisions.
 */
internal object B233PairingReplySummary {
    private val STATUS = Regex("^RTSP/1\\.0 ([1-5][0-9]{2})(?:\\s|$)")

    fun describe(statusLine: String, responseBody: ByteArray): String {
        val code = STATUS.find(statusLine)?.groupValues?.get(1) ?: "other"
        return "status=$code respBytes=" + responseBody.size + " redacted"
    }
}
