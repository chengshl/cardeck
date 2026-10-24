package io.github.shaonialife.cardeck.audio

/**
 * T71 isolated B233 type=102/media send-side timing experiment.
 *
 * catplay modern transmitter requests high-accuracy timestamps and does not
 * actively transmit the classic 0xD4 sync packet on the returned controlPort.
 * This is NOT equivalent to T14's "keep RTP after RTCP ECONNREFUSED":
 * T71 does not send the first datagram to that rejected endpoint at all.
 *
 * Deliberately leave PCM, compatibility streams, other receivers and
 * AudioSyncPacket wire encoding unchanged. A successful SETUP/RECORD or
 * lasting video connection alone does not prove audio playback works.
 */
internal object BuickAudioTimingPolicy {
    fun useModernSenderTiming(
        isBuickB233: Boolean,
        profile: AudioProfile,
    ): Boolean =
        isBuickB233 &&
            profile.streamType == 102L &&
            profile.codec == AudioCodec.AAC_LC &&
            profile.audioType.equals("media", ignoreCase = true)
}
