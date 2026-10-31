package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频档位记忆的排序规则（[AudioProfileMemory.order]）。
 *
 * 背景（2026-10-19 Honda HFT / Alpine 实测）：选「视频+音频」后会话能建立、视频正常，
 * 但音频起流约 6s 后车机拆掉整条 AirPlay 会话；只推视频则稳定。车机不会说明是哪一档
 * 音频不兼容，只能按「起流后的存活时间」反推，并把结论按车机持久化，下次连接换档。
 */
class AudioProfileMemoryTest {
    // 这台车机的真实声明：media 档只有 44.1k，compatibility 档 48k/44.1k 都有
    private val hondaCandidates =
        listOf(
            AudioProfile("media", 1L shl 11, 44_100, 250),
            AudioProfile("compatibility", 1L shl 15, 48_000, 250),
            AudioProfile("compatibility", 1L shl 11, 44_100, 250),
        )

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(hondaCandidates, AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry()))
    }

    @Test
    fun workingProfileGoesFirst() {
        val compat48 = hondaCandidates[1].key
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(working = compat48))
        assertEquals(compat48, ordered.first().key)
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun rejectedProfileGoesLastButStaysAsFallback() {
        val media44 = hondaCandidates[0].key
        val ordered =
            AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = setOf(media44)))
        assertEquals(media44, ordered.last().key)
        // 仍然保留在候选里：换固件后还有机会用
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun allRejectedKeepsTryingInsteadOfGoingSilent() {
        val all = hondaCandidates.map { it.key }.toSet()
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = all))
        // 全被判死说明与记忆矛盾（换车机/固件升级沿用同一身份键）——照常全试，避免永久无声
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun disabledMeansNoAudioAtAll() {
        assertTrue(
            AudioProfileMemory
                .order(hondaCandidates, AudioProfileMemory.Entry(disabled = true))
                .isEmpty(),
        )
        assertFalse(AudioProfileMemory.order(emptyList(), AudioProfileMemory.Entry()).isNotEmpty())
    }

    @Test
    fun profileKeyIgnoresLatencySoRelearningWorks() {
        val a = AudioProfile("media", 1L shl 11, 44_100, 250)
        val b = AudioProfile("media", 1L shl 11, 44_100, 400)
        assertEquals(a.key, b.key)
        // 大小写差异（/info 里出现过 Media/media）不能造成两套记忆；
        // 键里同时带流类型与编码：同格式在 102/AAC 与 100/PCM 上是完全不同的通路
        assertEquals("100|media|2048|PCM", AudioProfile("Media", 1L shl 11, 44_100, 250).key)
        assertEquals(
            "102|media|4194304|AAC_LC",
            AudioProfile(
                "media",
                1L shl 22,
                44_100,
                250,
                streamType = 102L,
                codec = AudioCodec.AAC_LC,
            ).key,
        )
    }

    @Test
    fun receiverKeyIsStableAcrossCasingAndPunctuation() {
        val a = AudioProfileMemory.receiverKey("Alpine Electronics (China) Co., Ltd. 8A250-30A")
        val b = AudioProfileMemory.receiverKey("alpine electronics china co ltd 8a250 30a")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        assertFalse(a == AudioProfileMemory.receiverKey("AUDI 3260 DELPHI"))
    }

    @Test
    fun setupFailureAloneNeverDisablesAudio() {
        // SETUP 被拒/超时可能只是车机资源仲裁或网络抖动：只降级排序，不能判死整台车机
        val id = "test receive setup-fail"
        AudioProfileMemory.recordRejected(null, id, "media|2048", setOf("media|2048"), markExhausted = false)
        val entry = AudioProfileMemory.load(null, id)
        assertTrue(entry.rejected.contains("media|2048"))
        assertFalse(entry.disabled)
    }

    @Test
    fun b233LocalReverseRtcpBindFailureCannotPersistReceiverRejection() {
        val id = "b233-pre-setup-local-EPERM"
        val pcmMedia = AudioProfile("media", 1L shl 15, 48_000, 250)
        val pcmCompat = AudioProfile("compatibility", 1L shl 15, 48_000, 250)
        AudioProfileMemory.reset(null, id)
        try {
            // T94: the first profile was never sent to the receiver;
            // the second profile actually completed SETUP and played.
            assertFalse(AudioProfileMemory.shouldRecordRejectedAfterAttempt(
                isBuickB233 = true, setupRequestAttempted = false))
            if (AudioProfileMemory.shouldRecordRejectedAfterAttempt(true, false)) {
                AudioProfileMemory.recordRejected(
                    null, id, pcmMedia.key, setOf(pcmMedia.key, pcmCompat.key),
                    markExhausted = false)
            }
            val clean = AudioProfileMemory.load(null, id)
            assertFalse(clean.rejected.contains(pcmMedia.key))
            assertFalse(clean.disabled)
            assertEquals(listOf(pcmMedia, pcmCompat),
                AudioProfileMemory.orderForBuickB233(listOf(pcmMedia, pcmCompat), clean))

            // Receiver-facing SETUP refusal is still a valid weak rejection.
            assertTrue(AudioProfileMemory.shouldRecordRejectedAfterAttempt(
                isBuickB233 = true, setupRequestAttempted = true))
            if (AudioProfileMemory.shouldRecordRejectedAfterAttempt(true, true)) {
                AudioProfileMemory.recordRejected(
                    null, id, pcmMedia.key, setOf(pcmMedia.key, pcmCompat.key),
                    markExhausted = false)
            }
            val rejected = AudioProfileMemory.load(null, id)
            assertTrue(rejected.rejected.contains(pcmMedia.key))
            assertFalse(rejected.disabled)
            assertEquals(pcmCompat.key,
                AudioProfileMemory.orderForBuickB233(
                    listOf(pcmMedia, pcmCompat), rejected).first().key)

            // Non-B233 historical behavior must remain exactly unchanged.
            assertTrue(AudioProfileMemory.shouldRecordRejectedAfterAttempt(
                isBuickB233 = false, setupRequestAttempted = false))
        } finally {
            AudioProfileMemory.reset(null, id)
        }
    }

    @Test
    fun b233RestoresAdvertisedAac48MediaFirstAndPcmFallback() {
        val pcm48 = AudioProfile("media", 1L shl 15, 48_000, 250)
        val aac48 = AudioProfile(
            "media", 1L shl 23, 48_000, 250,
            streamType = 102L, codec = AudioCodec.AAC_LC,
        )
        val aac44 = AudioProfile(
            "media", 1L shl 22, 44_100, 250,
            streamType = 102L, codec = AudioCodec.AAC_LC,
        )
        val input = listOf(pcm48, aac44, aac48) // T10's old PCM-first result
        val ordered = AudioProfileMemory.orderForBuickB233(input, AudioProfileMemory.Entry())
        assertEquals(listOf(aac48, aac44, pcm48), ordered)
        // Default non-B233 policy remains entirely untouched.
        assertEquals(input, AudioProfileMemory.order(input, AudioProfileMemory.Entry()))
    }

    @Test
    fun b233TrulyRejectedAacRemainsFallbackWithoutDisablingPcm() {
        val pcm48 = AudioProfile("media", 1L shl 15, 48_000, 250)
        val aac48 = AudioProfile(
            "media", 1L shl 23, 48_000, 250,
            streamType = 102L, codec = AudioCodec.AAC_LC,
        )
        val candidates = listOf(pcm48, aac48)
        val rejected = AudioProfileMemory.Entry(rejected = setOf(aac48.key))
        assertEquals(
            listOf(pcm48, aac48),
            AudioProfileMemory.orderForBuickB233(candidates, rejected),
        )
        assertEquals(
            listOf(pcm48),
            AudioProfileMemory.orderForBuickB233(listOf(pcm48), AudioProfileMemory.Entry()),
        )
    }

    @Test
    fun b233T16OnlyReopensLegacyAacRtcpRejection() {
        val id = "BUICK B233 T16 targeted AAC-RTCP migration"
        val aac = AudioProfile(
            "media", 1L shl 23, 48_000, 250,
            streamType = 102L, codec = AudioCodec.AAC_LC,
        )
        val pcm = AudioProfile("media", 1L shl 15, 48_000, 250)
        val other = AudioProfile("compatibility", 1L shl 15, 48_000, 250)
        AudioProfileMemory.reset(null, id)
        AudioProfileMemory.recordTransportFailure(null, id, aac.key)
        AudioProfileMemory.recordRejected(
            null, id, other.key, setOf(pcm.key, other.key, aac.key),
            markExhausted = false,
        )
        assertEquals(pcm.key, AudioProfileMemory.orderForBuickB233(
            listOf(pcm, aac, other), AudioProfileMemory.load(null, id),
        ).first().key)

        AudioProfileMemory.reopenRejectedProfiles(null, id, setOf(aac.key))
        val after = AudioProfileMemory.load(null, id)
        assertFalse(after.rejected.contains(aac.key))
        assertTrue("unrelated rejected profile preserved", after.rejected.contains(other.key))
        assertFalse(after.disabled)
        assertEquals(aac.key, AudioProfileMemory.orderForBuickB233(
            listOf(pcm, aac, other), after,
        ).first().key)
        AudioProfileMemory.reset(null, id)
    }

    @Test
    fun b233ExplicitTransportFailureInvalidatesRememberedAacSuccess() {
        val id = "BUICK B233 audio-transport-unit-test"
        val aac = AudioProfile(
            "media", 1L shl 23, 48_000, 250,
            streamType = 102L, codec = AudioCodec.AAC_LC,
        )
        val pcm = AudioProfile("media", 1L shl 15, 48_000, 250)
        AudioProfileMemory.reset(null, id)
        AudioProfileMemory.recordWorking(null, id, aac.key)
        assertEquals(aac.key, AudioProfileMemory.load(null, id).working)

        AudioProfileMemory.recordTransportFailure(null, id, aac.key)
        val entry = AudioProfileMemory.load(null, id)
        assertEquals(null, entry.working)
        assertTrue(entry.rejected.contains(aac.key))
        assertFalse("one refused port must not disable all audio", entry.disabled)
        val preferred = AudioProfileMemory.order(listOf(pcm, aac), entry)
        assertEquals(pcm.key, preferred.first().key)
        assertEquals(aac.key, preferred.last().key)
        AudioProfileMemory.reset(null, id)
    }

    @Test
    fun teardownOfEveryProfileDisablesAudio() {
        // 只有「起流后整链被拆」这种确定性证据才允许把车机音频判死
        val id = "test receive teardown-all"
        val all = setOf("media|2048", "compatibility|32768")
        AudioProfileMemory.recordRejected(null, id, "media|2048", all)
        AudioProfileMemory.recordRejected(null, id, "compatibility|32768", all)
        assertTrue(AudioProfileMemory.load(null, id).disabled)
        assertTrue(AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.load(null, id)).isEmpty())
    }
}
