package io.github.shaonialife.cardeck.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * T82: read-only per-AirPlay-connection event and audio-feedback health counters.
 *
 * No socket operation, protocol output, media payload, host/address or secret is retained.
 * Monotonic time is injectable for offline tests; 0 means not observed.
 */
internal class AudioControlLiveness(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val receivedEvents = AtomicLong(0)
    private val acknowledgedEvents = AtomicLong(0)
    private val failedEventAcks = AtomicLong(0)
    private val lastEventNanos = AtomicLong(0)
    private val lastAckNanos = AtomicLong(0)
    private val feedbackReplies = AtomicLong(0)
    private val lastFeedbackNanos = AtomicLong(0)
    private val firstAudioSampleTime = AtomicLong(-1)
    private val lastAudioSampleTime = AtomicLong(-1)
    private val audioSampleAdvances = AtomicLong(0)
    private val audioSampleStalls = AtomicLong(0)

    fun onEventRequest() {
        receivedEvents.incrementAndGet()
        lastEventNanos.set(nowNanos())
    }

    fun onEventAck(success: Boolean) {
        if (success) {
            acknowledgedEvents.incrementAndGet()
            lastAckNanos.set(nowNanos())
        } else {
            failedEventAcks.incrementAndGet()
        }
    }

    /** Update after a successfully parsed RTSP /feedback response; null = stream clock absent. */
    fun onFeedback(sampleTime: Long?) {
        feedbackReplies.incrementAndGet()
        lastFeedbackNanos.set(nowNanos())
        if (sampleTime == null || sampleTime < 0L) return
        val prior = lastAudioSampleTime.getAndSet(sampleTime)
        firstAudioSampleTime.compareAndSet(-1L, sampleTime)
        if (prior >= 0L) {
            when {
                sampleTime > prior -> audioSampleAdvances.incrementAndGet()
                sampleTime == prior -> audioSampleStalls.incrementAndGet()
                // A playback-clock reset is neither advancing nor stalled.
            }
        }
    }

    fun summary(): String {
        val now = nowNanos()
        fun age(last: Long): String =
            if (last <= 0L) "never" else ((now - last).coerceAtLeast(0L) / 1_000_000L).toString()
        return "eventRx=${receivedEvents.get()} eventAck=${acknowledgedEvents.get()}" +
            " eventAckFail=${failedEventAcks.get()} eventAgeMs=${age(lastEventNanos.get())}" +
            " ackAgeMs=${age(lastAckNanos.get())} feedback=${feedbackReplies.get()}" +
            " feedbackAgeMs=${age(lastFeedbackNanos.get())}" +
            " sampleFirst=${firstAudioSampleTime.get()} sampleLast=${lastAudioSampleTime.get()}" +
            " sampleAdvance=${audioSampleAdvances.get()} sampleStall=${audioSampleStalls.get()}"
    }
}
