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
    fun t89OnlyB233PcmMediaAdvertisesReceiveOnlyReversePort() {
        val pcmMedia = AudioProfile("media", 1L shl 15, 48_000, 250)
        assertTrue(BuickAudioTimingPolicy.advertiseReverseControlPort(true, pcmMedia))
        assertTrue(
            BuickAudioTimingPolicy.advertiseReverseControlPort(
                true, pcmMedia.copy(audioType = "MEDIA"),
            ),
        )
        assertFalse(BuickAudioTimingPolicy.advertiseReverseControlPort(false, pcmMedia))
        assertFalse(
            BuickAudioTimingPolicy.advertiseReverseControlPort(
                true, pcmMedia.copy(audioType = "compatibility"),
            ),
        )
        assertFalse(
            BuickAudioTimingPolicy.advertiseReverseControlPort(
                true, pcmMedia.copy(streamType = 102L),
            ),
        )
        assertFalse(
            BuickAudioTimingPolicy.advertiseReverseControlPort(
                true, pcmMedia.copy(codec = AudioCodec.AAC_LC),
            ),
        )
        // T89 kept the high-accuracy flag off; T90 opts in explicitly,
        // while preserving the existing PCM legacy-RTCP sender policy.
        assertFalse(BuickAudioTimingPolicy.useModernSenderTiming(true, pcmMedia))
        assertTrue(BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(true, pcmMedia))
    }

    @Test
    fun t89PreservesExistingAacMediaReversePortAndModernTiming() {
        assertTrue(BuickAudioTimingPolicy.advertiseReverseControlPort(true, aacMedia))
        assertTrue(BuickAudioTimingPolicy.useModernSenderTiming(true, aacMedia))
        assertFalse(BuickAudioTimingPolicy.advertiseReverseControlPort(false, aacMedia))
        assertFalse(
            BuickAudioTimingPolicy.advertiseReverseControlPort(
                true, aacMedia.copy(audioType = "compatibility"),
            ),
        )
    }

    @Test
    fun t90HighAccuracyTimestampIsLimitedToB233PcmMedia() {
        val pcmMedia = AudioProfile("media", 1L shl 15, 48_000, 250)
        assertTrue(BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(true, pcmMedia))
        assertTrue(
            BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(
                true, pcmMedia.copy(audioType = "MEDIA"),
            ),
        )
        assertFalse(BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(false, pcmMedia))
        assertFalse(
            BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(
                true, pcmMedia.copy(audioType = "compatibility"),
            ),
        )
        assertFalse(
            BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(
                true, pcmMedia.copy(streamType = 101L),
            ),
        )
        assertFalse(
            BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(
                true, pcmMedia.copy(codec = AudioCodec.AAC_LC),
            ),
        )
        assertFalse(
            BuickAudioTimingPolicy.advertiseHighAccuracyTimestamps(
                true, AudioProfile("compatibility", 1L shl 15, 48_000, 250),
            ),
        )
        // High-accuracy advertisement must not silently disable legacy 0xD4 sending.
        assertFalse(BuickAudioTimingPolicy.useModernSenderTiming(true, pcmMedia))
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
