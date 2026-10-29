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
     * B233-only, path-form peer requests must be handled before looking up a
     * locally pending CSeq. A peer may independently reuse our CSeq number.
     * Keep absolute-URI handling and all other receiver profiles unchanged;
     * those require separate evidence and a separate protocol experiment.
     */
    fun prioritizePathRequestBeforePendingCseq(startLine: String, isBuickB233: Boolean): Boolean {
        if (!isBuickB233) return false
        val fields = startLine.split(" ")
        return fields.size >= 2 &&
            fields[1].startsWith("/") &&
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
