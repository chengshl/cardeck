package io.github.shaonialife.cardeck.iap2

import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Buick B233 post-authentication Wi-Fi iAP2 messages.
 *
 * Unlike the Audi-specific upstream MIB3 burst, only send message IDs explicitly
 * advertised as receivable in the virtual link's 0x1D01 param 7. This keeps the
 * proven Buick AirPlay / video handshake intact while testing the 30s teardown.
 */
internal object BuickB233VirtualPostAuth {
    private const val WIRELESS_SESSION_ID = 0x4E0C
    private const val AUX_STATUS = 0xA100
    private const val DEVICE_LANGUAGE = 0x4E0A
    private const val DEVICE_TIME = 0x4E0B
    private const val DEVICE_INFO = 0x4E09

    fun receiveIds(param7: ByteArray?): Set<Int> {
        if (param7 == null || param7.size % 2 != 0) return emptySet()
        return param7.asList().chunked(2).map { bytes ->
            ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        }.toSet()
    }

    fun burst(
        acceptedIds: Set<Int>,
        atMillis: Long = System.currentTimeMillis(),
        sessionId: String = UUID.randomUUID().toString().uppercase(Locale.ROOT),
    ): List<Pair<Int, ByteArray>> {
        val candidates = listOf(
            WIRELESS_SESSION_ID to Iap2Csm.param(0, sessionId.toByteArray(Charsets.UTF_8)),
            AUX_STATUS to Iap2Csm.param(0x0006, ByteArray(0)),
            DEVICE_LANGUAGE to Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)),
            DEVICE_TIME to deviceTimeParams(atMillis),
            DEVICE_INFO to Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)),
        )
        return candidates.filter { (id, _) -> id in acceptedIds }
    }

    private fun deviceTimeParams(atMillis: Long): ByteArray {
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(atMillis) / 60_000
        val daylight = if (zone.inDaylightTime(java.util.Date(atMillis))) 1 else 0
        val seconds = atMillis / 1000L
        val timeBytes = ByteArray(8) { i -> (seconds ushr ((7 - i) * 8)).toByte() }
        val offsetBytes = byteArrayOf((offsetMin shr 8).toByte(), offsetMin.toByte())
        return Iap2Csm.param(0, timeBytes) +
            Iap2Csm.param(1, offsetBytes) +
            Iap2Csm.param(2, byteArrayOf(daylight.toByte()))
    }
}
