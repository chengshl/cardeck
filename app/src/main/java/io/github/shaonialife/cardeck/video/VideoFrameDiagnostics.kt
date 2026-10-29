package io.github.shaonialife.cardeck.video

/**
 * E5 #28: read-only video-output evidence. Tracks successfully flushed VIDEO frames,
 * never RTSP feedback, screen keepAlive, encoder "running", or decoded car display.
 *
 * This state is owned by one VideoSender/drain loop and must never trigger teardown,
 * Bluetooth reconnect, or an additional thread. All times are monotonic milliseconds.
 */
internal class VideoFrameDiagnostics(private val encoderStartedAtMs: Long) {
    internal enum class Signal {
        FIRST_FRAME_FLUSHED,
        NO_FIRST_FRAME_6S,
        NO_FRAMES_15S,
    }

    private var lastFrameFlushedAtMs: Long? = null
    private var zeroFrameReported = false
    private var stalledReported = false

    /** Call only after the complete encrypted frame and header have been flushed. */
    fun frameFlushed(nowMs: Long): Signal? {
        val first = lastFrameFlushedAtMs == null
        lastFrameFlushedAtMs = nowMs
        stalledReported = false
        return if (first) Signal.FIRST_FRAME_FLUSHED else null
    }

    /**
     * Called on the existing once-per-second screen keepAlive path, after flush.
     * Warning is reported once per period, not repeatedly every second.
     */
    fun keepAliveFlushed(nowMs: Long): Signal? {
        val lastFrame = lastFrameFlushedAtMs
        if (lastFrame == null) {
            if (!zeroFrameReported && nowMs - encoderStartedAtMs >= 6_000L) {
                zeroFrameReported = true
                return Signal.NO_FIRST_FRAME_6S
            }
            return null
        }
        if (!stalledReported && nowMs - lastFrame >= 15_000L) {
            stalledReported = true
            return Signal.NO_FRAMES_15S
        }
        return null
    }

    fun ageMs(nowMs: Long): Long = (nowMs - encoderStartedAtMs).coerceAtLeast(0)
    fun gapMs(nowMs: Long): Long =
        (nowMs - (lastFrameFlushedAtMs ?: encoderStartedAtMs)).coerceAtLeast(0)
}
