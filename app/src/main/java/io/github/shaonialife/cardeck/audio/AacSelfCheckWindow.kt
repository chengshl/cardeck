package io.github.shaonialife.cardeck.audio

/**
 * Collects only a short, bounded stretch of consecutive encoded frames after
 * nonzero PCM has first appeared. The result is used by an off-thread decoder;
 * it never delays or changes the live RTP packets.
 */
internal class AacSelfCheckWindow(
    private val maxFrames: Int = 12,
    private val maxBytes: Int = 64 * 1024,
    private val maxDurationNanos: Long = 1_000_000_000L,
) {
    private var armedAt: Long = 0L
    private var bytes = 0
    private val frames = ArrayList<ByteArray>(maxFrames)

    var completed = false
        private set

    val armed: Boolean get() = armedAt != 0L

    /** null = still collecting; empty list = nonzero PCM seen but encoder supplied no AAC in time. */
    fun accept(nonzeroPcm: Boolean, encoded: List<ByteArray>, nowNanos: Long): List<ByteArray>? {
        if (completed) return null
        if (!armed && !nonzeroPcm) return null
        if (!armed) armedAt = nowNanos.coerceAtLeast(1L)

        var limitReached = false
        for (frame in encoded) {
            if (frame.isEmpty()) continue
            if (frames.size >= maxFrames || bytes + frame.size > maxBytes) {
                limitReached = true
                break
            }
            frames += frame.copyOf()
            bytes += frame.size
        }
        if (!limitReached && frames.size < maxFrames && nowNanos - armedAt < maxDurationNanos) return null
        completed = true
        return frames.toList()
    }
}
