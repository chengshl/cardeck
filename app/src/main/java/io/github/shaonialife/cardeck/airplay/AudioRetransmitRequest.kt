package io.github.shaonialife.cardeck.airplay

/**
 * Read-only analysis of a RAOP-style 0xD5 (marker + payload type 85) resend request.
 *
 * Wire format, network byte order:
 *  [0] RTP flags, [1] 0xD5, [2..3] request sequence,
 *  [4..5] first missing RTP sequence, [6..7] number requested.
 *
 * The 16-bit value at [2..3] is NOT an RTCP length. Parsing this packet does
 * not imply B233 requires a particular 0xD6 response envelope.
 */
internal object AudioRetransmitRequest {
    const val PACKET_BYTES = 8
    const val TYPE = 0xD5

    /** Safety cap for diagnostics only; not evidence of a B233 protocol limit. */
    const val MAX_DIAGNOSTIC_COUNT = 256

    data class Fields(
        val requestSeq: Int,
        val firstMissingSeq: Int,
        val count: Int,
    ) {
        val lastMissingSeq: Int get() = (firstMissingSeq + count - 1) and 0xFFFF
        val wrapsRtpSequence: Boolean get() = lastMissingSeq < firstMissingSeq
    }

    sealed interface Parse {
        data class Valid(val fields: Fields) : Parse
        data class Invalid(val reason: String) : Parse
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    fun inspect(bytes: ByteArray, length: Int): Parse {
        if (length < 0 || length > bytes.size || length != PACKET_BYTES) {
            return Parse.Invalid("invalid_length")
        }
        if ((bytes[0].toInt() and 0xC0) != 0x80 ||
            (bytes[1].toInt() and 0xFF) != TYPE
        ) {
            return Parse.Invalid("not_d5_v2")
        }
        val count = u16(bytes, 6)
        if (count == 0) return Parse.Invalid("zero_count")
        if (count > MAX_DIAGNOSTIC_COUNT) return Parse.Invalid("count_exceeds_diagnostic_cap")
        return Parse.Valid(Fields(u16(bytes, 2), u16(bytes, 4), count))
    }

    /**
     * The current AudioSender starts sequence at 0 and never FLUSH-resets it.
     * A monotonically counted >65536 packet session cannot be disambiguated
     * from a 16-bit request without per-packet history, so report unknown.
     *
     * sentCount reflects successful UDP sends only. No media bytes are stored.
     */
    fun coverage(fields: Fields, sentCount: Long?): String {
        if (sentCount == null) return "sender_unavailable"
        if (sentCount <= 0L) return "not_sent_yet"
        if (sentCount > 65_536L) return "ambiguous_after_wrap"
        val lastSent = ((sentCount - 1L).toInt() and 0xFFFF)
        val behindLast = (lastSent - fields.firstMissingSeq) and 0xFFFF
        val isInside = behindLast.toLong() < sentCount && (fields.count - 1) <= behindLast
        return if (isInside) "within_sent" else "outside_sent"
    }
}
