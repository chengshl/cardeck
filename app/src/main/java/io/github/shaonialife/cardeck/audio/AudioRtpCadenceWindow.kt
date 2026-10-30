package io.github.shaonialife.cardeck.audio

/**
 * Bounded, read-only RTP send cadence diagnostics. Records only successful UDP
 * sends (not PCM/AAC payloads, keys, addresses, or individual packet contents).
 * Each completed window reports sample frames, maximum inter-send gap, and
 * maximum DatagramSocket.send() call duration; averages alone hide bursts.
 *
 * The first sample starts a new one-second window. A gap crossing two windows
 * is reported once, in the window containing the later successful send.
 * Not a protocol pacing controller: never sleeps, queues, or modifies RTP.
 */
internal class AudioRtpCadenceWindow(
    private val reportEveryNanos: Long = 1_000_000_000L,
) {
    internal data class Snapshot(
        val elapsedMs: Long,
        val packets: Int,
        val sampleFrames: Long,
        val maxGapMs: Long,
        val maxSendUs: Long,
    )

    private var firstSuccessNanos: Long? = null
    private var windowStartNanos: Long? = null
    private var lastSuccessNanos: Long? = null
    private var windowPackets = 0
    private var windowSampleFrames = 0L
    private var maxGapNanos = 0L
    private var maxSendNanos = 0L

    init {
        require(reportEveryNanos > 0L)
    }

    fun successfulSend(
        sendBeginNanos: Long,
        sendEndNanos: Long,
        sampleFrames: Long,
    ): Snapshot? {
        require(sendEndNanos >= sendBeginNanos)
        require(sampleFrames > 0L)
        if (firstSuccessNanos == null) firstSuccessNanos = sendEndNanos
        if (windowStartNanos == null) windowStartNanos = sendEndNanos
        val preceding = lastSuccessNanos
        if (preceding != null) {
            maxGapNanos = maxOf(maxGapNanos, (sendEndNanos - preceding).coerceAtLeast(0L))
        }
        lastSuccessNanos = sendEndNanos
        maxSendNanos = maxOf(maxSendNanos, sendEndNanos - sendBeginNanos)
        windowPackets++
        windowSampleFrames += sampleFrames

        val duration = sendEndNanos - checkNotNull(windowStartNanos)
        if (duration < reportEveryNanos) return null
        val snapshot = Snapshot(
            elapsedMs = ((sendEndNanos - checkNotNull(firstSuccessNanos)) / 1_000_000L).coerceAtLeast(0L),
            packets = windowPackets,
            sampleFrames = windowSampleFrames,
            maxGapMs = maxGapNanos / 1_000_000L,
            maxSendUs = maxSendNanos / 1_000L,
        )
        windowStartNanos = sendEndNanos
        windowPackets = 0
        windowSampleFrames = 0L
        maxGapNanos = 0L
        maxSendNanos = 0L
        return snapshot
    }
}
