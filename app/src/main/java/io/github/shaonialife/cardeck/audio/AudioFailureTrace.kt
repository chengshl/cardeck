package io.github.shaonialife.cardeck.audio

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * T76: observational-only monotonic timeline for one B233 audio attempt.
 *
 * Every event uses System.nanoTime() within the same phone boot. This does not
 * alter iAP2/RTSP/RTP timing, retries or teardown. No addresses, media samples,
 * payload bytes or keys are logged.
 * Monotonic origin is arbitrary; null means no successful RTP send yet.
 */
class AudioFailureTrace(
    private val log: (String) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    companion object {
        private val nextId = AtomicLong()
    }

    private val id = nextId.incrementAndGet()
    private val beganNanos = nowNanos()
    private val firstRtpNanos = AtomicReference<Long?>(null)

    fun event(name: String, reason: String = "") {
        val now = nowNanos()
        val first = firstRtpNanos.get()
        val rtpAge = if (first == null) "not_started" else ((now - first) / 1_000_000L).toString()
        val safeReason = reason.replace(Regex("[\\r\\n\\t]"), " ").take(96)
        log(
            "AUDIO T76: trace=$id monoMs=${now / 1_000_000L} " +
                "sinceSetupMs=${(now - beganNanos) / 1_000_000L} sinceFirstRtpMs=$rtpAge " +
                "thread=${Thread.currentThread().name} event=$name" +
                if (safeReason.isEmpty()) "" else " detail=$safeReason",
        )
    }

    fun firstRtpSent() {
        val now = nowNanos()
        if (firstRtpNanos.compareAndSet(null, now)) {
            event("first_rtp_sent")
        }
    }
}
