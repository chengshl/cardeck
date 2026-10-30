package io.github.shaonialife.cardeck.airplay

/**
 * Safe, bounded diagnostics for one decoded changeModes request.
 *
 * Logs ONLY the numeric resource/app-state protocol fields. Never includes
 * reasonStr, raw plist, device names, identifiers, network addresses, or
 * unrecognized keys/values. No changes to RTSP responses or resource ownership.
 */
internal object B233ModeRequestSummary {
    private fun number(item: Map<*, *>?, key: String): String {
        val value = item?.get(key) as? Number ?: return "-"
        val n = value.toLong()
        return if (n in -1L..10000L) n.toString() else "out-of-range"
    }

    fun describe(command: Map<*, *>): String {
        if (command["type"] != "changeModes") return "not-changeModes"
        val params = command["params"] as? Map<*, *>
            ?: return "params=absent-or-unknown"
        val resources = params["resources"] as? List<*>
        val apps = params["appStates"] as? List<*>
        val initial = params["initialPermanentEntity"] as? List<*>
        fun listShape(items: List<*>?, keys: List<String>): String {
            if (items == null) return "absent"
            return items.take(4).joinToString(separator = ";", prefix = "count=${items.size}[", postfix = "]") { entry ->
                val map = entry as? Map<*, *>
                if (map == null) "unexpected" else keys.joinToString(",") { field ->
                    "$field=${number(map, field)}"
                }
            }
        }

        return "resources=" + listShape(
            resources,
            listOf("resourceID", "transferType", "transferPriority", "takeConstraint", "borrowConstraint", "unborrowConstraint"),
        ) + " appStates=" + listShape(
            apps,
            listOf("appStateID", "state", "speechMode"),
        ) + " initialPermanent=" + listShape(
            initial,
            listOf("resourceID", "permanentEntity", "takeConstraint", "borrowConstraint"),
        )
    }
}
