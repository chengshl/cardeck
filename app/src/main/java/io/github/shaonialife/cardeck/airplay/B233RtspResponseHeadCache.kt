package io.github.shaonialife.cardeck.airplay

/**
 * B233-only bounded storage for RTSP error response headers.
 *
 * The legacy AirPlayClient responseHeads map keeps every successful /feedback
 * header forever (roughly one per second), although callers only inspect the
 * header when SETUP returns a non-200 status. Preserve that error diagnostic
 * while preventing long-running sessions from retaining unlimited raw heads.
 *
 * This is in-memory only: no response headers are uploaded or newly logged.
 */
internal class B233RtspResponseHeadCache(
    private val maxEntries: Int = 16,
) {
    init {
        require(maxEntries > 0)
    }

    private val heads = LinkedHashMap<Int, String>()

    fun recordIfError(
        cseq: Int,
        statusLine: String,
        head: String,
        matchedPending: Boolean,
    ) {
        if (!matchedPending || !statusLine.startsWith("RTSP/")) return
        val code = statusLine.substringAfter(' ', "").substringBefore(' ', "")
        if (code == "200") return
        synchronized(heads) {
            heads[cseq] = head
            while (heads.size > maxEntries) {
                val oldest = heads.keys.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    /** One-shot lookup after a failed request; stale diagnostics are discarded. */
    fun take(cseq: Int): String? = synchronized(heads) { heads.remove(cseq) }

    internal fun retainedCount(): Int = synchronized(heads) { heads.size }
}
