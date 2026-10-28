package io.github.shaonialife.cardeck.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * T76: observational-only monotonic timeline for one B233 audio attempt.
 *
 * Every event uses System.nanoTime() within the same phone boot. This does not
 * alter iAP2/RTSP/RTP timing, retries or teardown. No addresses, media samples,
 * payload bytes or keys are logged.
 */
internal class AudioFailureTrace(private val log: (String) -> Unit) {
    companion object {
        private val nextId = AtomicLong()
    }

    private val id = nextId.incrementAndGet()
    private val beganNanos = System.nanoTime()
    private val firstRtpNanos = AtomicLong(0L)

    fun event(name: String, reason: String = "") {
        val now = System.nanoTime()
        val first = firstRtpNanos.get()
        val rtpAge = if (first == 0L) "not_started" else ((now - first) / 1_000_000L).toString()
        val safeReason = reason.replace(Regex("[\\r\\n\\t]"), " ").take(96)
        log(
            "AUDIO T76: trace=$id monoMs=${now / 1_000_000L} " +
                "sinceSetupMs=${(now - beganNanos) / 1_000_000L} sinceFirstRtpMs=$rtpAge " +
                "thread=${Thread.currentThread().name} event=$name" +
                if (safeReason.isEmpty()) "" else " detail=$safeReason",
        )
    }

    fun firstRtpSent() {
        val now = System.nanoTime()
        if (firstRtpNanos.compareAndSet(0L, now)) {
            event("first_rtp_sent")
        }
    }
}
