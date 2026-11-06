package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import io.github.shaonialife.cardeck.audio.AudioSyncPacket

/** 计时服务：NTP 编码往返 + 请求应答构造（对照 catplay rtcp_timesync.rs 的线格式）。 */
class TimingServerTest {
    @Test
    fun ntpEncodingRoundTrips() {
        val values = listOf(
            -1_500_000_000L, -1L, 0L, 123_456_789L, 999_999_999L,
            1_000_000_000L, 12_345_678_901_234L,
        )
        for (nanos in values) {
            val packed = ByteBuffer.wrap(TimingServer.ntpFromNanos(nanos, useNtp1900Epoch = true)).long
            // NTP's high seconds bit is set for the 1900→1970 offset:
            // unsigned decoding is required, not arithmetic shr 32.
            val encodedSeconds = (packed ushr 32) and 0xFFFF_FFFFL
            val secs = encodedSeconds - TimingServer.NTP_UNIX_EPOCH_OFFSET_SECONDS
            val fracNanos = ((packed and 0xFFFF_FFFFL) * 1_000_000_000L) ushr 32
            val back = secs * 1_000_000_000L + fracNanos
            // NTP fixed-point fractions truncate at nanosecond precision.
            assertTrue("nanos=$nanos back=$back", Math.abs(back - nanos) <= 2)
        }
    }

    @Test
    fun ntpSecondsHaveExplicit1900EpochAndFraction() {
        val zero = TimingServer.ntpFromNanos(0, useNtp1900Epoch = true)
        assertArrayEquals(
            byteArrayOf(0x83.toByte(), 0xAA.toByte(), 0x7E, 0x80.toByte(), 0, 0, 0, 0),
            zero,
        )
        val oneAndHalfSeconds = TimingServer.ntpFromNanos(1_500_000_000L, useNtp1900Epoch = true)
        assertArrayEquals(
            byteArrayOf(0x83.toByte(), 0xAA.toByte(), 0x7E, 0x81.toByte(), 0x80.toByte(), 0, 0, 0),
            oneAndHalfSeconds,
        )
        // A naked monotonic timestamp must NOT accidentally pass this check.
        assertTrue(oneAndHalfSeconds[0] != 0.toByte())
    }

    @Test
    fun audioSyncPacketEmbedsExactSameNtpTimeBase() {
        val ntp = TimingServer.ntpFromNanos(12_345_678_901L, useNtp1900Epoch = true)
        val packet = AudioSyncPacket.build(0, 352, 704, ntp, first = true)
        assertArrayEquals(ntp, packet.copyOfRange(8, 16))
        assertEquals(20, packet.size)
        assertEquals(0xD4.toByte(), packet[1])
    }

    @Test
    fun respondsToTimesyncRequestEchoingT3() {
        val request = ByteArray(32)
        request[0] = (2 shl 6).toByte()
        request[1] = 0xD2.toByte()
        request[2] = 0
        request[3] = 7
        val clientNanos = 987_654_321L
        val packed = ByteBuffer.wrap(TimingServer.ntpFromNanos(clientNanos)).long
        for (index in 0 until 8) {
            request[24 + index] = (packed ushr (8 * (7 - index))).toByte()
        }

        val response = TimingServer.buildResponse(request, nowNanos = 5_000_000_000L, useNtp1900Epoch = true)
        assertNotNull(response)
        response!!
        assertEquals(32, response.size)
        assertEquals((2 shl 6).toByte(), response[0])
        assertEquals(0xD3.toByte(), response[1])
        assertEquals(7, ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF))
        // t1 = 回显请求的 t3
        assertArrayEquals(request.copyOfRange(24, 32), response.copyOfRange(8, 16))
        // t2 = t3 = 本地 5s 的 NTP 编码
        val expected = TimingServer.ntpFromNanos(5_000_000_000L, useNtp1900Epoch = true)
        assertArrayEquals(expected, response.copyOfRange(16, 24))
        assertArrayEquals(expected, response.copyOfRange(24, 32))
    }

    @Test
    fun legacyReceiversKeepUnchangedMonotonicNtpBytes() {
        val oldZero = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        assertArrayEquals(oldZero, TimingServer.ntpFromNanos(0))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x80.toByte(), 0, 0, 0),
            TimingServer.ntpFromNanos(1_500_000_000L),
        )
        assertArrayEquals(
            oldZero,
            TimingServer.ntpFromNanos(0, useNtp1900Epoch = false),
        )
        // T103 B233 opt-in must change the same input wire bytes.
        assertTrue(!TimingServer.ntpFromNanos(0, useNtp1900Epoch = true)
            .contentEquals(TimingServer.ntpFromNanos(0)))
    }

    @Test
    fun timingResponseAndAudioSyncShareEachReceiversChosenNtpPolicy() {
        val request = ByteArray(32).apply {
            this[0] = (2 shl 6).toByte()
            this[1] = 0xD2.toByte()
            this[3] = 7
        }
        for (b233 in listOf(false, true)) {
            val encoded = TimingServer.ntpFromNanos(2_500_000_000L, useNtp1900Epoch = b233)
            val timing = TimingServer.buildResponse(request, 2_500_000_000L, useNtp1900Epoch = b233)!!
            val sync = AudioSyncPacket.build(1, 352, 704, encoded, first = true)
            assertArrayEquals(encoded, timing.copyOfRange(16, 24))
            assertArrayEquals(encoded, timing.copyOfRange(24, 32))
            assertArrayEquals(encoded, sync.copyOfRange(8, 16))
            assertArrayEquals(request.copyOfRange(24, 32), timing.copyOfRange(8, 16))
        }
    }

    @Test
    fun socketSendAuditReportsOnlyLocalReturnAndRedactsReceiver() {
        var attempts = 0
        assertTrue(TimingServer.socketSendReturned { attempts++ })
        assertFalse(TimingServer.socketSendReturned {
            attempts++
            throw java.net.SocketException("private-address-should-never-be-logged")
        })
        assertEquals(2, attempts)

        val ok = TimingServer.safeSendOutcomeLine(1, 1, 0, returned = true, ntp1900 = true)
        assertTrue(ok.contains("socketSendReturned=1"))
        assertTrue(ok.contains("lastSocketSend=returned"))
        assertTrue(ok.contains("ntpEpoch=1900"))
        assertTrue(ok.contains("peerDelivery=unknown"))
        val failed = TimingServer.safeSendOutcomeLine(2, 1, 1, returned = false, ntp1900 = false)
        assertTrue(failed.contains("socketSendFailed=1"))
        assertTrue(failed.contains("lastSocketSend=exception"))
        assertTrue(failed.contains("ntpEpoch=legacy"))
        assertTrue(failed.contains("peerDelivery=unknown"))
        for (msg in listOf(ok, failed)) {
            assertFalse(msg.contains("private-address"))
            assertFalse(msg.contains("192.168."))
            assertFalse(msg.contains("hostAddress"))
        }
    }

    @Test
    fun socketSendAuditFirstThreeAndThirtySecondHeartbeatAreBounded() {
        assertTrue(TimingServer.shouldLogSendOutcome(true, 1, 0, 100, 100))
        assertTrue(TimingServer.shouldLogSendOutcome(true, 3, 0, 200, 100))
        assertFalse(TimingServer.shouldLogSendOutcome(true, 4, 0, 400, 100))
        assertTrue(TimingServer.shouldLogSendOutcome(false, 4, 1, 400, 100))
        assertTrue(TimingServer.shouldLogSendOutcome(false, 4, 3, 500, 100))
        assertFalse(TimingServer.shouldLogSendOutcome(false, 4, 4, 600, 100))
        assertTrue(TimingServer.shouldLogSendOutcome(false, 4, 4, 30_100, 100))
    }
    @Test
    fun rejectsNonTimesyncPackets() {
        assertNull(TimingServer.buildResponse(ByteArray(8), 0))
        assertNull(TimingServer.buildResponse(ByteArray(32), 0)) // 全零：version=0

        val badVersion = ByteArray(32)
        badVersion[0] = (3 shl 6).toByte()
        badVersion[1] = 0xD2.toByte()
        assertNull(TimingServer.buildResponse(badVersion, 0))

        val responsePt = ByteArray(32)
        responsePt[0] = (2 shl 6).toByte()
        responsePt[1] = 0xD3.toByte() // 应答类型不该被再应答
        assertNull(TimingServer.buildResponse(responsePt, 0))
    }
}
