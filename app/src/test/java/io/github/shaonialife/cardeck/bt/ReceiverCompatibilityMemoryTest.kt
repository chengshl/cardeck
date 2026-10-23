package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverCompatibilityMemoryTest {
    private fun syntheticAddress(last: String): String =
        listOf("02", "00", "00", "00", "00", last).joinToString(":")

    @Test
    fun addressKeySurvivesNameAndLetterCaseChanges() {
        val address = syntheticAddress("AF")
        val key = ReceiverCompatibilityMemory.preferenceKey(address)
        assertNotNull(key)
        assertEquals(key, ReceiverCompatibilityMemory.preferenceKey(address.lowercase()))
        assertEquals(key, ReceiverCompatibilityMemory.preferenceKey("  $address  "))
        assertFalse(key!!.contains(address))
        assertEquals("observed_b233_".length + 64, key.length)
    }

    @Test
    fun invalidAddressesNeverBecomeReceiverProfiles() {
        assertNull(ReceiverCompatibilityMemory.preferenceKey(null))
        assertNull(ReceiverCompatibilityMemory.preferenceKey(""))
        assertNull(ReceiverCompatibilityMemory.preferenceKey("My Buick"))
        assertNull(ReceiverCompatibilityMemory.preferenceKey("02:00:00:00:00"))
        assertNotEquals(
            ReceiverCompatibilityMemory.preferenceKey(syntheticAddress("01")),
            ReceiverCompatibilityMemory.preferenceKey(syntheticAddress("02")),
        )
    }
}
