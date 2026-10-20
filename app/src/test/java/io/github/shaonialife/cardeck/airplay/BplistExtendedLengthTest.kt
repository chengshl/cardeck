package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression: Apple bplist extended lengths >= 128 are unsigned, not signed integers. */
class BplistExtendedLengthTest {
    @Test
    fun extendedData200BytesRoundTrips() {
        val value = ByteArray(200) { (it and 0xFF).toByte() }
        val original = linkedMapOf<String, Any?>("type" to "iAPSendMessage", "data" to value)
        val decoded = BinaryPlist.parse(BinaryPlist.encode(original)) as Map<*, *>
        assertArrayEquals(value, decoded["data"] as ByteArray)
    }

    @Test
    fun extendedAscii150CharactersRoundTrips() {
        val text = "x".repeat(150)
        assertEquals(text, BinaryPlist.parse(BinaryPlist.encode(text)))
    }
}
