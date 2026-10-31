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
     * Only B233: recognize a syntactically valid absolute-URI peer request.
     * The existing RTSP response queue must not consume this as a response
     * when the receiver independently reuses one of our pending CSeq values.
     * Legacy path-form handling is intentionally unchanged.
     */
    fun isB233AbsoluteUriRequest(startLine: String, isBuickB233: Boolean): Boolean {
        if (!isBuickB233) return false
        val fields = startLine.trim().split(Regex("""\s+"""))
        return fields.size == 3 &&
            fields[1].startsWith("rtsp://") &&
            fields[1].length > "rtsp://".length &&
            classify(startLine) != Kind.RESPONSE_OR_UNKNOWN
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
