package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuickAudioTimingPolicyTest {
    private val aacMedia = AudioProfile(
        audioType = "media", formatBit = 1L shl 23, sampleRate = 48_000,
        latencyMs = 250, streamType = 102L, codec = AudioCodec.AAC_LC,
    )

    @Test
    fun b233ModernAacMediaUsesPassiveRtcpMode() {
        assertTrue(BuickAudioTimingPolicy.useModernSenderTiming(true, aacMedia))
        assertTrue(
            BuickAudioTimingPolicy.useModernSenderTiming(
                true, aacMedia.copy(audioType = "MEDIA"),
            ),
        )
    }

    @Test
    fun otherReceiversKeepExistingRtcpSendBehavior() {
        assertFalse(BuickAudioTimingPolicy.useModernSenderTiming(false, aacMedia))
    }

    @Test
    fun b233PcmAndCompatibilityKeepExistingRtcpBehavior() {
        assertFalse(
            BuickAudioTimingPolicy.useModernSenderTiming(
                true,
                AudioProfile("media", 1L shl 15, 48_000, 250),
            ),
        )
        assertFalse(
            BuickAudioTimingPolicy.useModernSenderTiming(
                true, aacMedia.copy(audioType = "compatibility"),
            ),
        )
        assertFalse(
            BuickAudioTimingPolicy.useModernSenderTiming(
                true, aacMedia.copy(streamType = 100L),
            ),
        )
    }
}
