package io.github.shaonialife.cardeck.airplay

/**
 * B233-only, read-only evidence for /command modesChanged RTSP replies.
 *
 * HTTP 200 alone does not prove the receiver granted requested resources.
 * Do NOT infer accepted ownership from a missing/unknown response field.
 * Never include payload bytes, free-form strings, keys, UUIDs, IPs or names.
 */
internal object B233ModeReplySummary {
    private const val MAX_PLIST_BYTES = 4096
    private const val MAX_ITEMS = 4

    private fun safeNumber(value: Any?): String {
        val n = (value as? Number)?.toLong() ?: return "absent"
        return if (n in -1L..10000L) n.toString() else "out-of-range"
    }

    private fun safeBoolean(value: Any?): String =
        when (value) {
            true -> "true"
            false -> "false"
            else -> "absent"
        }

    private fun safeShape(value: Any?): String {
        val values = value as? List<*> ?: return "absent"
        val items = values.take(MAX_ITEMS).joinToString(separator = ";") { raw ->
            val entry = raw as? Map<*, *> ?: return@joinToString "unexpected"
            "resourceID=${safeNumber(entry["resourceID"])}," +
                "entity=${safeNumber(entry["entity"])}," +
                "transferType=${safeNumber(entry["transferType"])}," +
                "transferPriority=${safeNumber(entry["transferPriority"])}"
        }
        return "count=${values.size}[$items]"
    }

    fun describe(body: ByteArray): String {
        if (body.isEmpty()) return "body=empty ownership=unverified"
        if (body.size > MAX_PLIST_BYTES) return "body=over-limit ownership=unverified"
        val root = try {
            BinaryPlist.parse(body) as? Map<*, *>
        } catch (_: Throwable) {
            null
        } ?: return "body=unrecognized ownership=unverified"
        val type = when (root["type"]) {
            "modesChanged" -> "modesChanged"
            "changeModes" -> "changeModes"
            else -> "other-or-absent"
        }
        val params = root["params"] as? Map<*, *>
        val success = safeBoolean(root["success"])
        val accepted = safeBoolean(root["accepted"])
        // The response is only evidence; never treat accepted=true as a
        // protocol guarantee unless its semantics have been independently
        // confirmed on the target receiver.
        return "body=plist type=$type params=${if (params == null) "absent" else "present"} " +
            "topStatus=${safeNumber(root["status"])} topSuccess=$success " +
            "topAccepted=$accepted resources=${safeShape(params?.get("resources"))} " +
            "ownership=unverified"
    }
}
