package io.github.shaonialife.cardeck.airplay

/**
 * B233-only, read-only correlation of virtual Network iAP2 CSM control events.
 *
 * Message IDs and phone-local monotonic timestamps are the only inputs. No
 * receiver identity, body bytes, network address or media data is retained.
 * An identification repeat is not evidence of a new RTSP session or audio EOF.
 */
internal class B233NetworkCsmProbe {
    data class Observation(val event: String, val detail: String, val monoMs: Long = -1L)

    private var identificationCount = 0
    private var lastIdentificationMs = -1L
    private var lastStopHidMs = -1L
    private var emitted = 0

    private fun elapsed(nowMs: Long, previousMs: Long): String =
        if (previousMs < 0 || nowMs < previousMs) "unknown"
        else (nowMs - previousMs).coerceAtMost(600_000L).toString()

    /**
     * Return bounded, allowlisted metadata only; never influence CSM handling.
     * A probe belongs to one VirtualIap2Link instance/epoch.
     */
    @Synchronized
    fun observe(messageId: Int, nowMs: Long): Observation? {
        if (nowMs < 0L) return null
        val observation = when (messageId) {
            0x1D01 -> {
                val sincePrevious = elapsed(nowMs, lastIdentificationMs)
                val sinceStop = elapsed(nowMs, lastStopHidMs)
                identificationCount = (identificationCount + 1).coerceAtMost(1000)
                lastIdentificationMs = nowMs
                Observation(
                    if (identificationCount == 1) "network_identify_first" else "network_identify_repeat",
                    "count=$identificationCount sincePrevMs=$sincePrevious sinceStopHidMs=$sinceStop",
                )
            }
            0x6803 -> {
                lastStopHidMs = nowMs
                Observation("network_stop_hid", "identifyCount=$identificationCount")
            }
            0x6800 -> Observation(
                "network_start_hid",
                "identifyCount=$identificationCount sinceStopHidMs=${elapsed(nowMs, lastStopHidMs)}",
            )
            else -> null
        } ?: return null
        if (emitted >= 16) return null
        emitted++
        // The monotonic instant is recorded at CSM receipt, not at a later
        // AudioFailureTrace creation/flush. Never use Gateway arrival time.
        return observation.copy(monoMs = nowMs)
    }
}
