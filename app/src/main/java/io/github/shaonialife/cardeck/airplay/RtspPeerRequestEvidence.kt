package io.github.shaonialife.cardeck.airplay

/**
 * Read-only T87 diagnostic: distinguish an unsolicited peer TEARDOWN request
 * from an RTSP response before CSeq-based dispatch. An inbound request may
 * reuse a CSeq that happens to be pending locally, so inspecting only the
 * unmatched-response path would miss it.
 *
 * Return a fixed category only. Never retain or log the RTSP request target,
 * headers, payload, or receiver identifiers.
 */
internal object RtspPeerRequestEvidence {
    enum class Kind { TEARDOWN, OTHER_REQUEST, RESPONSE_OR_UNKNOWN }

    fun classify(startLine: String): Kind {
        val fields = startLine.trim().split(Regex("""\s+"""))
        if (fields.size != 3 || fields[2] != "RTSP/1.0") return Kind.RESPONSE_OR_UNKNOWN
        val method = fields[0]
        val target = fields[1]
        if (!method.matches(Regex("[A-Z_]+"))) return Kind.RESPONSE_OR_UNKNOWN
        if (!target.startsWith("/") && !target.startsWith("rtsp://")) return Kind.RESPONSE_OR_UNKNOWN
        return if (method == "TEARDOWN") Kind.TEARDOWN else Kind.OTHER_REQUEST
    }
}
