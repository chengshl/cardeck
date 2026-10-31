package io.github.shaonialife.cardeck.airplay

/**
 * B233 pairing responses may contain cryptographic handshake material.
 * Log only a fixed protocol status and payload length; never echo TLV bytes,
 * response reason phrases, identifiers, or arbitrary peer-supplied strings.
 * This does not affect RTSP parsing or pairing decisions.
 */
internal object B233PairingReplySummary {
    private val STATUS = Regex("^RTSP/1\\.0 ([1-5][0-9]{2})(?:\\s|$)")

    fun safeStatus(statusLine: String): String =
        STATUS.find(statusLine)?.groupValues?.get(1) ?: "other"

    // Pairing acceptance must validate the complete RTSP status code, not
    // merely search for the digits "200" in a peer-controlled reason phrase.
    // Keep this B233-only: other receivers retain their existing policy.
    private val PAIRING_SUCCESS = Regex("^RTSP/1\\.0 200(?:[ \\t][^\\r\\n]*)?$")

    fun isSuccessfulPairingStatus(statusLine: String): Boolean =
        PAIRING_SUCCESS.matches(statusLine)

    fun describe(statusLine: String, responseBody: ByteArray): String {
        val code = safeStatus(statusLine)
        return "status=$code respBytes=" + responseBody.size + " redacted"
    }
}
