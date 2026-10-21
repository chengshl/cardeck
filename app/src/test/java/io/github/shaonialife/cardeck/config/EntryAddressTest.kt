package io.github.shaonialife.cardeck.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EntryAddressTest {
    @Test
    fun preservesCustomHostPortAndPath() {
        assertEquals("wss://gateway.example:8443/ws/device", EntryAddress.normalize(" wss://gateway.example:8443/ws/device "))
    }

    @Test
    fun rejectsInsecureOrCredentialBearingAddresses() {
        listOf(
            "ws://gateway.example/ws/device",
            "https://gateway.example/ws/device",
            "wss://user:password@gateway.example/ws/device",
            "wss://gateway.example/ws/device?token=secret",
            "wss://gateway.example/ws/device#secret",
            "wss://gateway.example:0/ws/device",
            "wss://gateway.example:65536/ws/device",
            "wss://gateway.example",
            "wss:///ws/device",
            "",
        ).forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { EntryAddress.normalize(address) }
        }
    }

    @Test
    fun loggingConfigurationDoesNotExposeToken() {
        assertFalse(EntryConfig("wss://gateway.example/ws/device", "sensitive-token").toString().contains("sensitive-token"))
    }
}
