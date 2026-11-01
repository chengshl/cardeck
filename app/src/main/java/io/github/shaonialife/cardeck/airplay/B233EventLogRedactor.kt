package io.github.shaonialife.cardeck.airplay

/**
 * Minimal B233 event-channel diagnostics. Peer headers, plist bytes, parser
 * notes, host addresses and exception messages must never enter upload logs.
 * Only bounded body lengths, fixed stages and exception class names are kept.
 */
internal object B233EventLogRedactor {
    enum class Stage(val label: String) {
        SEND("send"),
        READ("read"),
        CONNECT("connect"),
        DISPATCH("dispatch"),
    }

    fun malformedSignature(bodyBytes: Int): String = "B233:length:${Math.max(0, bodyBytes)}"

    fun malformedCommand(bodyBytes: Int): String =
        "EVENT: B233 command_decode_failed bodyBytes=${Math.max(0, bodyBytes)} redacted"

    fun unknownRequest(bodyBytes: Int): String =
        "EVENT: B233 unknown_request bodyBytes=${Math.max(0, bodyBytes)} redacted"

    fun recoveredCommand(bodyBytes: Int): String =
        "EVENT: B233 command_recovered bodyBytes=${Math.max(0, bodyBytes)} redacted"

    fun error(stage: Stage, error: Throwable): String =
        "EVENT: B233 ${stage.label}_error type=${error.javaClass.simpleName} redacted"
}
