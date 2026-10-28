package io.github.shaonialife.cardeck.gateway

/** Bounded, already-redacted exception-neighborhood recovery.
 * Keeps up to 4 prior warning lines and 12 subsequent lines around a *critical* event.
 * No unredacted bytes are persisted here; the caller always runs forensicChunks first.
 */
internal class RescueTraceContext {
    private val before = ArrayDeque<List<String>>()
    private var after = 0

    fun accept(raw: String, safeChunks: List<String>): List<Pair<String, Boolean>> {
        if (safeChunks.isEmpty()) return emptyList()
        val critical = RescueTracePolicy.critical(raw)
        val selected = RescueTracePolicy.keep(raw)
        val result = mutableListOf<Pair<String, Boolean>>()
        if (critical) {
            // Duplicate crash context into the fsync-protected critical lane. All bytes were redacted upstream.
            before.forEach { chunks -> chunks.forEach { result.add(it to true) } }
            before.clear()
            safeChunks.forEach { result.add(it to true) }
            after = 12
        } else if (selected || after > 0) {
            // Preserve the first 12 lines of the fatal stack in critical storage as well.
            safeChunks.forEach { result.add(it to (after > 0)) }
            if (after > 0) after--
        } else {
            before.addLast(safeChunks)
            while (before.size > 4) before.removeFirst()
        }
        return result
    }
}
