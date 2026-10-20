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

    /**
     * Reactive replies only: the receiver must first request a subscription.
     * Empty payloads match the upstream's documented MIB3 initial/idle state,
     * but do not send any Audi-only unsolicited location/status traffic.
     */
    fun replyToSubscription(requestId: Int): Pair<Int, ByteArray>? = when (requestId) {
        0x5000 -> 0x5001 to rawBody(
            "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                "0500100000050012000005001a8000250001000500000000080001000000000005" +
                "000500000500060000050007000005001000",
        )

        0x4157 -> 0x4158 to rawBody(
            "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                "00000500050100050006010005000700000500080000050009000005000a000005" +
                "000b000005000c010005000d000005000e000005000f0000050010000005001100",
        )

        0x4154 -> 0x4155 to Iap2Csm.param(0x0002, byteArrayOf(0))
        0x4170 -> 0x4171 to (
            Iap2Csm.param(0x0000, byteArrayOf(0)) +
                Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                Iap2Csm.param(0x0005, byteArrayOf(0)) +
                Iap2Csm.param(0x0007, byteArrayOf(0, 0))
        )

        else -> null
    }

    private fun rawBody(frameHex: String): ByteArray {
        val bytes = ByteArray(frameHex.length / 2) { i ->
            frameHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        require(bytes.size >= Iap2Csm.HEADER_BYTES)
        val declaredLength = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        require(declaredLength == bytes.size) { "Invalid upstream CSM frame length" }
        return bytes.copyOfRange(Iap2Csm.HEADER_BYTES, bytes.size)
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
