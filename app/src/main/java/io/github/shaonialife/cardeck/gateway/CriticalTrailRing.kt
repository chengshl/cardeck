package io.github.shaonialife.cardeck.gateway

/** Pure 50-event ring. Events contain only fixed phase names, not user data. */
internal data class CriticalEvent(
    val wallMs: Long,
    val elapsedMs: Long,
    val session: String,
    val phase: String,
)

internal object CriticalTrailRing {
    private const val MAX = 50
    private val phasePattern = Regex("^[A-Z][A-Z0-9_]{1,55}$")
    private val sessionPattern = Regex("^[0-9a-f]{8,16}$")

    fun validPhase(phase: String) = phasePattern.matches(phase)

    fun append(previous: List<CriticalEvent>, event: CriticalEvent): List<CriticalEvent> =
        (previous + event).takeLast(MAX)

    fun encode(events: List<CriticalEvent>): String =
        events.takeLast(MAX).joinToString("\n") {
            listOf(it.wallMs, it.elapsedMs, it.session, it.phase).joinToString("\t")
        }

    fun decode(text: String): List<CriticalEvent> =
        text.lineSequence().mapNotNull { line ->
            val columns = line.split('\t')
            if (columns.size != 4 || !sessionPattern.matches(columns[2]) ||
                !validPhase(columns[3])) return@mapNotNull null
            val wall = columns[0].toLongOrNull() ?: return@mapNotNull null
            val elapsed = columns[1].toLongOrNull() ?: return@mapNotNull null
            if (wall < 0 || elapsed < 0) return@mapNotNull null
            CriticalEvent(wall, elapsed, columns[2], columns[3])
        }.toList().takeLast(MAX)

    /** A system_server-only restart does not reset Android elapsed realtime. */
    fun possibleDeviceReboot(before: CriticalEvent, after: CriticalEvent) =
        after.elapsedMs < before.elapsedMs

    fun newAppProcess(before: CriticalEvent, after: CriticalEvent) =
        before.session != after.session
}
