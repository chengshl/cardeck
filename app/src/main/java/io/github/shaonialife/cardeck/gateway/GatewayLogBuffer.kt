package io.github.shaonialife.cardeck.gateway

import java.util.ArrayDeque

/** No disk backlog. Vehicle association is captured when a log enters the queue. */
class GatewayLogBuffer(
    private val redactor: LogRedactor,
    private val capacity: Int = 200,
    private val vehicleProvider: () -> String? = { null },
) {
    data class TaggedLine(val text: String, val vehicleId: String?, val batchId: String? = null)

    private val lines = ArrayDeque<TaggedLine>()
    @Volatile var dropped: Long = 0
        private set
    init { require(capacity > 0) }

    @Synchronized
    fun offer(raw: String) {
        val associatedVehicle = vehicleProvider()
        for (line in raw.lineSequence().map(redactor::line).filter { it.isNotBlank() }) {
            // An unsendable row is counted explicitly; never upload a truncated substitute.
            if (!GatewayLogProtocol.fits(listOf(line), "app", associatedVehicle)) { dropped++; continue }
            if (lines.size >= capacity) { lines.removeFirst(); dropped++ }
            lines.addLast(TaggedLine(line, associatedVehicle))
        }
    }

    /** One batch never mixes different vehicles, including unassigned logs. */
    @Synchronized
    fun takeTagged(): List<TaggedLine> = buildList {
        val first = lines.peekFirst()?.vehicleId
        val pendingBatch = lines.peekFirst()?.batchId
        while (size < 8 && lines.isNotEmpty() && lines.peekFirst().vehicleId == first &&
            lines.peekFirst().batchId == pendingBatch) {
            val candidate = map { it.text } + lines.peekFirst().text
            if (!GatewayLogProtocol.fits(candidate, "app", first)) break
            add(lines.removeFirst())
        }
    }

    @Synchronized
    fun retryTagged(batch: List<TaggedLine>) {
        for (line in batch.asReversed()) {
            if (lines.size >= capacity) { dropped++; continue }
            lines.addFirst(line)
        }
    }

    /** Kept for legacy UI/unit tests that do not track vehicle identity. */
    fun take(): List<String> = takeTagged().map { it.text }

    fun retry(batch: List<String>) = retryTagged(batch.map { TaggedLine(it, null) })

    @Synchronized
    fun clear() { lines.clear() }
}
