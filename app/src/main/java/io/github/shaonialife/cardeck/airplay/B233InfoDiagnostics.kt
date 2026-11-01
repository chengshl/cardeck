package io.github.shaonialife.cardeck.airplay

/**
 * B233 /info diagnostic evidence is bounded and numeric only.
 * No peer-supplied text, identifiers, raw payload, or plist keys are logged.
 * The parsed receiver capabilities used by the session are unchanged.
 */
internal object B233InfoDiagnostics {
    private fun number(value: Any?): String = (value as? Number)?.toLong()?.toString() ?: "?"

    fun response(statusLine: String, bytes: Int): String =
        "AIRPLAY: B233 /info status=" +
            B233ModeReplySummary.safeTransportStatus(statusLine.removePrefix("侧信道 "), true) +
            " bodyBytes=" + bytes + " redacted"

    fun parseFailure(error: Throwable, bytes: Int): String =
        "AIRPLAY: B233 /info plist parse failure type=" + error.javaClass.simpleName +
            " bodyBytes=" + bytes + " redacted"

    fun summary(root: Map<*, *>): List<String> {
        val formats = (root["audioFormats"] as? List<*>).orEmpty()
        val latencies = (root["audioLatencies"] as? List<*>).orEmpty()
        val displays = (root["displays"] as? List<*>).orEmpty()
        val hidCount = listOf("hidDevices", "hids", "hid").sumOf {
            (root[it] as? List<*>)?.size ?: 0
        }
        val lines = mutableListOf(
            "AIRPLAY: B233 /info shape fields=" + root.size + " displays=" + displays.size +
                " hidEntries=" + hidCount + " audioFormats=" + formats.size +
                " audioLatencies=" + latencies.size + " redacted",
        )
        formats.take(16).forEachIndexed { index, item ->
            val entry = item as? Map<*, *> ?: return@forEachIndexed
            val output = (entry["audioOutputFormats"] as? Number)?.toLong()
            val input = (entry["audioInputFormats"] as? Number)?.toLong()
            lines += "AIRPLAY: B233 audioFormat[" + index + "] type=" + number(entry["type"]) +
                " audioType=" + number(entry["audioType"]) +
                " output=" + (output?.toString(16) ?: "?") +
                " input=" + (input?.toString(16) ?: "?") + " redacted"
        }
        latencies.take(16).forEachIndexed { index, item ->
            val entry = item as? Map<*, *> ?: return@forEachIndexed
            lines += "AIRPLAY: B233 audioLatency[" + index + "] type=" + number(entry["type"]) +
                " audioType=" + number(entry["audioType"]) +
                " in=" + number(entry["inputLatencyMicros"]) +
                " out=" + number(entry["outputLatencyMicros"]) + " redacted"
        }
        return lines
    }
}
