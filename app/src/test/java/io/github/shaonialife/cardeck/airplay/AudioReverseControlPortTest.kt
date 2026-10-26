package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AudioReverseControlPortTest {
    @Test
    fun readsOnlyControlHeaderMetadata() {
        val example = byteArrayOf(0x80.toByte(), 0xD3.toByte(), 0x00, 0x07)
        val parsed = AudioReverseControlPort.inspectHeader(example, example.size)
        assertEquals(2, parsed?.version)
        assertEquals(0xD3, parsed?.packetType)
        assertEquals(32, parsed?.declaredBytes)
    }

    @Test
    fun rejectsMissingOrOutOfBoundsControlHeader() {
        assertNull(AudioReverseControlPort.inspectHeader(byteArrayOf(0x80.toByte()), 1))
        assertNull(AudioReverseControlPort.inspectHeader(byteArrayOf(0x80.toByte(), 0xD3.toByte()), 3))
        val shortHeader = AudioReverseControlPort.inspectHeader(
            byteArrayOf(0x80.toByte(), 0xD3.toByte()), 2,
        )
        assertEquals(2, shortHeader?.version)
        assertEquals(0xD3, shortHeader?.packetType)
        assertNull(shortHeader?.declaredBytes)
    }

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
