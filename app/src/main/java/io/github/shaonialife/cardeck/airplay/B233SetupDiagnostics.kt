package io.github.shaonialife.cardeck.airplay

/**
 * B233 initial SETUP diagnostics: bounded evidence without device identities,
 * peer-supplied plist keys, host addresses or exception messages.
 * Does not participate in RTSP negotiation or alter its wire behavior.
 */
internal object B233SetupDiagnostics {
    fun attempt(bodyBytes: Int, timingPort: Int, encrypted: Boolean): String =
        "AIRPLAY: B233 initial SETUP body=${bodyBytes}B timingPort=$timingPort encrypted=$encrypted identity=redacted"

    fun failure(error: Throwable): String =
        "AIRPLAY: B233 initial SETUP failure type=${error.javaClass.simpleName} redacted"

    fun rejection(statusLine: String, responseBytes: Int, candidateCount: Int): String =
        "AIRPLAY: B233 initial SETUP rejected status=${B233ModeReplySummary.safeTransportStatus(statusLine, true)} " +
            "responseBytes=$responseBytes candidateCount=$candidateCount redacted"

    fun forbiddenHint(statusLine: String): String? =
        if (B233ModeReplySummary.safeTransportStatus(statusLine, true) == "403") {
            "AIRPLAY: B233 initial SETUP 403: check receiver device filter/trust settings (identity redacted)"
        } else null
}
