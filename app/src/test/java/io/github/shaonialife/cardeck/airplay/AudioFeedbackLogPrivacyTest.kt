package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic payloads only: feedback diagnostics must never disclose raw peer data. */
class AudioFeedbackLogPrivacyTest {
    @Test fun validClockRemainsVisibleWithoutIdentifiersOrUnknownFields() {
        val payload = BinaryPlist.encode(mapOf(
            "streams" to listOf(mapOf(
                "type" to 100L, "sr" to 48000L, "sampleTime" to 918960L,
                "timestamp" to 1234L, "timestampRawNs" to 5555L,
                "streamConnectionID" to "PRIVATE-CONNECTION-ID",
                "futureSecret" to "PRIVATE-UNKNOWN-FIELD",
            )),
            "privateRoot" to "PRIVATE-ROOT",
        ))
        val lines = safeAudioFeedbackLogLines(payload)
        assertEquals(1, lines.size)
        assertEquals(
            "AIRPLAY: feedback.stream[0] type=100 sr=48000 sampleTime=918960 ts=1234 tsRawNs=5555",
            lines.single(),
        )
        assertFalse(lines.single().contains("PRIVATE"))
        assertFalse(lines.single().contains("connId"))
    }

    @Test fun malformedPayloadNeverDumpsHexOrRawBytes() {
        val payload = "PRIVATE-RAW-BYTES".toByteArray()
        val summary = safeAudioFeedbackLogLines(payload).joinToString("\n")
        assertEquals("AIRPLAY: feedback body 解析失败（17B）", summary)
        assertFalse(summary.contains("PRIVATE"))
        assertFalse(summary.contains("hex="))
    }

    @Test fun missingStreamsDoesNotExposeRootKeysOrValues() {
        val payload = BinaryPlist.encode(mapOf("PRIVATE-KEY" to "PRIVATE-VALUE"))
        val summary = safeAudioFeedbackLogLines(payload).single()
        assertTrue(summary.startsWith("AIRPLAY: feedback 无 streams（"))
        assertFalse(summary.contains("PRIVATE"))
        assertFalse(summary.contains("keys="))
    }

    @Test fun malformedFieldsNeverUseArbitraryToString() {
        val payload = BinaryPlist.encode(mapOf(
            "streams" to listOf(mapOf(
                "type" to "PRIVATE-TYPE",
                "sr" to "PRIVATE-SR",
                "sampleTime" to Double.NaN,
                "timestamp" to "PRIVATE-TS",
                "timestampRawNs" to "PRIVATE-RAW-NS",
                "streamConnectionID" to "PRIVATE-CONN",
            )),
        ))
        val summary = safeAudioFeedbackLogLines(payload).single()
        assertEquals(
            "AIRPLAY: feedback.stream[0] type=invalid sr=invalid sampleTime=invalid ts=invalid tsRawNs=invalid",
            summary,
        )
    }

    @Test fun excessiveStreamsAreBoundedAndNoUnknownPayloadIsLogged() {
        val streams = (0 until 12).map { index ->
            mapOf("type" to 100L, "sampleTime" to index.toLong(), "unknown" to "PRIVATE-$index")
        }
        val lines = safeAudioFeedbackLogLines(BinaryPlist.encode(mapOf("streams" to streams)))
        assertEquals(9, lines.size)
        assertTrue(lines.last().contains("truncated count=12"))
        assertFalse(lines.joinToString("\n").contains("PRIVATE"))
        assertFalse(lines.joinToString("\n").contains("stream[8]"))
    }
}
