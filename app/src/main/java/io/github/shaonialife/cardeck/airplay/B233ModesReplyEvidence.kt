package io.github.shaonialife.cardeck.airplay

/**
 * B233-only, read-only fingerprint of a modesChanged RTSP reply.
 *
 * RTSP 200 means transport acceptance, not ownership granted. Never log
 * arbitrary plist strings, keys, byte arrays or receiver identifiers.
 */
internal object B233ModesReplyEvidence {
    private const val MAX_BODY_BYTES = 4_096
    private val BPLIST_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    fun summarize(body: ByteArray): String {
        val prefix = "bodyBytes=${body.size}"
        if (body.isEmpty()) return "$" + "{prefix} format=empty ownership=unverified"
        if (body.size > MAX_BODY_BYTES) return "$" + "{prefix} format=oversize ownership=unverified"
        if (body.size < BPLIST_MAGIC.size ||
            !body.copyOfRange(0, BPLIST_MAGIC.size).contentEquals(BPLIST_MAGIC)
        ) return "$" + "{prefix} format=non_bplist ownership=unverified"

        val decoded = try {
            BinaryPlist.parseRecovered(body).value
        } catch (_: Exception) {
            return "$" + "{prefix} format=bplist_invalid ownership=unverified"
        }
        val root = decoded as? Map<*, *>
            ?: return "$" + "{prefix} format=bplist_non_map ownership=unverified"
        val params = root["params"] as? Map<*, *> ?: root
        val resources = params["resources"] as? List<*>
        val status = (params["status"] ?: root["status"]) as? Number
        val statusTag = status?.toLong()?.takeIf { it in -2_048L..2_048L }?.toString() ?: "unknown"
        val count = resources?.size?.coerceAtMost(16)?.toString() ?: "absent"
        val ids = resources?.take(16)?.mapNotNull { resource ->
            (resource as? Map<*, *>)?.get("resourceID") as? Number
        }?.mapNotNull { number ->
            when (number.toLong()) {
                1L -> "screen"
                2L -> "audio"
                else -> null
            }
        }?.distinct()?.joinToString(",")?.ifEmpty { "none" } ?: "absent"
        // Even an apparent resourceID=2 is only a parsed field, not a grant.
        return "$" + "{prefix} format=bplist_map resources=$" + "{count} resourceIds=$" + "{ids} statusNumber=$" + "{statusTag} ownership=unverified"
    }
}
