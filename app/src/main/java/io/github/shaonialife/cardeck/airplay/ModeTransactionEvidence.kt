package io.github.shaonialife.cardeck.airplay

/**
 * Offline-only contract inspection for CarPlay resource transactions.
 *
 * IMPORTANT: no runtime callers. This does not acknowledge requests, grant or release
 * a resource, write RTSP, or change SETUP/RECORD. A syntactically plausible request
 * does NOT establish that B233 sent it or that a transfer was accepted.
 *
 * Resource ID 1 = main screen; 2 = main audio. transferType codes are intentionally
 * left uninterpreted until real B233 request parameters are available.
 */
internal object ModeTransactionEvidence {
    enum class RequestShape { NOT_CHANGE_MODES, MISSING_PARAMS, NO_RESOURCES, RESOURCES_LIST }
    enum class ResponseShape { EMPTY, PLIST_MAP, OTHER_PLIST, UNPARSEABLE }

    data class ResourceShape(
        val knownResourceId: Long?,
        val transferTypeCode: Long?,
        val hasBorrowConstraint: Boolean,
        val hasPermanentEntity: Boolean,
    )

    data class RequestEvidence(
        val shape: RequestShape,
        val resources: List<ResourceShape> = emptyList(),
        val hasAppStates: Boolean = false,
    ) {
        /** Only indicates a field is present; not that the car currently owns it. */
        val mentionsMainAudio: Boolean get() = resources.any { it.knownResourceId == 2L }
    }

    data class ResponseEvidence(
        val transportOk: Boolean,
        val bodyShape: ResponseShape,
        val statusField: Long?,
    ) {
        /** A 200 response alone never establishes success of a resource transaction. */
        val applicationAcceptanceVerified: Boolean get() = false
    }

    fun inspectRequest(command: Map<*, *>): RequestEvidence {
        if (command["type"] != "changeModes") {
            return RequestEvidence(RequestShape.NOT_CHANGE_MODES)
        }
        val params = command["params"] as? Map<*, *>
            ?: return RequestEvidence(RequestShape.MISSING_PARAMS)
        val hasAppStates = params["appStates"] is List<*>
        val resources = params["resources"] as? List<*>
            ?: return RequestEvidence(RequestShape.NO_RESOURCES, hasAppStates = hasAppStates)
        return RequestEvidence(
            shape = RequestShape.RESOURCES_LIST,
            resources = resources.map { entry ->
                val map = entry as? Map<*, *>
                val id = (map?.get("resourceID") as? Number)?.toLong()
                val transferType = (map?.get("transferType") as? Number)?.toLong()
                ResourceShape(
                    knownResourceId = id?.takeIf { it == 1L || it == 2L },
                    // Keep a bounded numeric field, not an invented borrow/take interpretation.
                    transferTypeCode = transferType?.takeIf { it in 0L..4L },
                    hasBorrowConstraint = map?.containsKey("borrowConstraint") == true,
                    hasPermanentEntity = map?.containsKey("permanentEntity") == true,
                )
            },
            hasAppStates = hasAppStates,
        )
    }

    fun inspectResponse(statusLine: String, body: ByteArray): ResponseEvidence {
        val ok = statusLine.startsWith("RTSP/1.0 200 ")
        if (body.isEmpty()) return ResponseEvidence(ok, ResponseShape.EMPTY, null)
        val parsed = runCatching { BinaryPlist.parse(body) }.getOrNull()
            ?: return ResponseEvidence(ok, ResponseShape.UNPARSEABLE, null)
        val map = parsed as? Map<*, *>
            ?: return ResponseEvidence(ok, ResponseShape.OTHER_PLIST, null)
        val status = (map["status"] as? Number)?.toLong()
        // Do not infer any success/failure semantics from an unverified status value.
        return ResponseEvidence(ok, ResponseShape.PLIST_MAP, status)
    }
}
