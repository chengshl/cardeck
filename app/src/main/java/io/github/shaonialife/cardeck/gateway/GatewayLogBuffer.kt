package io.github.shaonialife.cardeck.gateway

import java.util.ArrayDeque

/** No disk backlog. New app lines only; oldest lines drop under backpressure. */
class GatewayLogBuffer(private val redactor: LogRedactor, private val capacity: Int = 200) {
    private val lines = ArrayDeque<String>()
    @Volatile var dropped: Long = 0
        private set
    init { require(capacity > 0) }

    @Synchronized
    fun offer(raw: String) {
        // A multiline stack is bounded before splitting, so producers cannot grow the queue unboundedly.
        val safeLines = if (raw.length > 8192) listOf("[超长日志已省略]") else {
            val split = raw.lineSequence().take(33).toList()
            val bounded = if (split.size > 32) split.take(31) + "[多行日志超限，已省略余下内容]" else split
            bounded.map(redactor::line)
        }
        for (line in safeLines.filter { it.isNotBlank() }) {
            if (lines.size >= capacity) { lines.removeFirst(); dropped++ }
            lines.addLast(line)
        }
    }

    @Synchronized
    fun take(): List<String> = buildList {
        repeat(minOf(8, lines.size)) { add(lines.removeFirst()) }
    }

    @Synchronized
    fun retry(batch: List<String>) {
        // Preserve fresh lines if the queue filled during a failed transfer.
        for (line in batch.asReversed()) {
            if (lines.size >= capacity) { dropped++; continue }
            lines.addFirst(line)
        }
    }

    @Synchronized
    fun clear() { lines.clear() }
}
