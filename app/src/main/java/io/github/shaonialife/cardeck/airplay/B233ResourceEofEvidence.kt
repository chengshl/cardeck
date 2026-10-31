package io.github.shaonialife.cardeck.airplay

/**
 * B233-only read-only account of *requests*, not granted resources.
 *
 * A modesChanged RTSP 200 proves transport acknowledgement only. Likewise,
 * a receiver changeModes carrying resourceID=1 is not evidence that
 * resourceID=2 was refused. Never retain plist bytes, names or IDs.
 *
 * All methods are synchronized because event, feedback and RTSP reader
 * threads are independent. Keep counters bounded even during long sessions.
 */
internal class B233ResourceEofEvidence {
    private enum class ClaimReply { NONE, PENDING, RTSP_200, RTSP_NON_200, IO_ERROR }

    private var claims = 0
    // Pair each RTSP response with its request; stale completions cannot overwrite newer evidence.
    private var claimGeneration = 0L
    private var claimAtMillis: Long = -1
    private var lastReply: ClaimReply = ClaimReply.NONE
    private var replyBodyShape = "absent"
    private var incomingScreenOnly = 0
    private var incomingMainAudio = 0

    @Synchronized
    fun claimMainAudio(nowMillis: Long): Long {
        claimGeneration = if (claimGeneration == Long.MAX_VALUE) 1L else claimGeneration + 1L
        claims = (claims + 1).coerceAtMost(1000)
        claimAtMillis = nowMillis.coerceAtLeast(0)
        lastReply = ClaimReply.PENDING
        replyBodyShape = "absent"
        return claimGeneration
    }

    @Synchronized
    fun claimReply(generation: Long, statusLine: String, bodyHasBytes: Boolean) {
        if (generation != claimGeneration || lastReply != ClaimReply.PENDING) return
        lastReply = if (statusLine.startsWith("RTSP/1.0 200 ")) ClaimReply.RTSP_200
            else ClaimReply.RTSP_NON_200
        replyBodyShape = if (bodyHasBytes) "nonempty" else "empty"
    }

    @Synchronized
    fun claimIoError(generation: Long) {
        if (generation == claimGeneration && lastReply == ClaimReply.PENDING) lastReply = ClaimReply.IO_ERROR
    }

    @Synchronized
    fun receiverChangeModes(command: Map<*, *>) {
        if (command["type"] != "changeModes") return
        val entries = (command["params"] as? Map<*, *>)?.get("resources") as? List<*> ?: return
        // Only the fact that the protocol request *mentions* a known ID is
        // retained. Do not interpret entity, transferType or reasonStr.
        val ids = entries.take(16).mapNotNull { (it as? Map<*, *>)?.get("resourceID") as? Number }
            .map { it.toLong() }
        if (2L in ids) incomingMainAudio = (incomingMainAudio + 1).coerceAtMost(1000)
        else if (1L in ids) incomingScreenOnly = (incomingScreenOnly + 1).coerceAtMost(1000)
    }

    @Synchronized
    fun summaryAtPeerEof(nowMillis: Long): String {
        val age = if (claimAtMillis < 0 || nowMillis < claimAtMillis) "unknown"
            else (nowMillis - claimAtMillis).coerceIn(0L, 600_000L).toString()
        return "mainAudioClaimAttempts=$claims lastClaimReply=${lastReply.name.lowercase()} " +
            "lastReplyBody=$replyBodyShape lastClaimAgeMs=$age " +
            "incomingChangeModesScreenOnly=$incomingScreenOnly " +
            "incomingChangeModesMentionsAudio=$incomingMainAudio ownership=unverified"
    }
}
