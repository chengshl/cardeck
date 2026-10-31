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

    /** Stable, privacy-safe summary: never accept or include a request URI or headers. */
    fun diagnosticTag(kind: Kind, pendingCseqMatch: Boolean, legacyAckEligible: Boolean): String? =
        if (kind == Kind.RESPONSE_OR_UNKNOWN) null
        else "kind=${kind.name} pendingCseqMatch=$pendingCseqMatch legacyAckEligible=$legacyAckEligible"

    /**
     * B233 unmatched RTSP diagnostics must not echo peer-controlled request
     * targets, reason phrases or malformed lines. Keep only fixed request
     * categories and a validated three-digit response code.
     *
     * This changes logging only: dispatch and ACK decisions still use the
     * original unmodified start line.
     */
    fun safeUnmatchedStartLine(startLine: String): String {
        val kind = classify(startLine)
        if (kind == Kind.TEARDOWN) return "peer_request=TEARDOWN"
        if (kind == Kind.OTHER_REQUEST) return "peer_request=OTHER"

        val fields = startLine.trim().split(Regex("""\s+"""))
        val code = fields.getOrNull(1)
        return if (fields.firstOrNull() == "RTSP/1.0" &&
            code != null && code.matches(Regex("""[1-5][0-9]{2}"""))
        ) {
            "response_status=$code"
        } else {
            "unrecognized"
        }
    }

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
