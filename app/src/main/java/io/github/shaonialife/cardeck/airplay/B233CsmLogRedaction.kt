package io.github.shaonialife.cardeck.airplay

/**
 * On Buick B233 never echo raw inbound iAP2/EA bytes into the Gateway logs.
 * The legacy non-B233 debug path is preserved to minimize unrelated changes.
 */
internal object B233CsmLogRedaction {
    fun payload(isBuickB233: Boolean, bytes: ByteArray, maxChars: Int): String =
        if (isBuickB233) "[redacted]"
        else bytes.joinToString(" ") { "%02x".format(it) }.take(maxChars)

    fun params(isBuickB233: Boolean, description: String): String =
        if (isBuickB233) "[redacted]" else description
}
