package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReverseControlPortTest {
    @Test
    fun opensEphemeralLocalUdpPortAndClosesIt() {
        val receiver = AudioReverseControlPort(bindNetwork = {}, log = {})
        val port = receiver.start()
        try {
            assertTrue(port in 1..65535)
            assertEquals(port, receiver.start())
            assertEquals(port, receiver.port)
        } finally {
            receiver.close()
        }
        assertEquals(0, receiver.port)
    }

    @Test
    fun failureToBindPreventsAdvertisingPort() {
        val receiver = AudioReverseControlPort(
            bindNetwork = { throw IllegalStateException("test bind failure") },
            log = {},
        )
        assertEquals(0, receiver.start())
        assertEquals(0, receiver.port)
        receiver.close()
    }
}
