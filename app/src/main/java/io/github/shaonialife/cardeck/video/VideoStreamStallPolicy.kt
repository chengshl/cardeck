package io.github.shaonialife.cardeck.video

/** Monotonic uptime-based video-data-port progress check, independent from RTSP control. */
internal object VideoStreamStallPolicy {
    fun isStalled(nowMs: Long, lastProgressMs: Long, timeoutMs: Long, running: Boolean): Boolean =
        running && timeoutMs > 0L && lastProgressMs > 0L &&
            nowMs >= lastProgressMs && nowMs - lastProgressMs >= timeoutMs
}
