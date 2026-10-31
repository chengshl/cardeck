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
    /**
     * T89 isolated, single-wire-field hypothesis: B233 PCM/type100 media
     * currently advertises no PHONE-local reverse RTCP controlPort.
     * In T85 and T88 the receiver also returned no controlPort; neither
     * direction therefore had an active audio RTCP control endpoint.
     * Advertise a receive-only local port for this PCM media profile while
     * leaving high-accuracy timestamps, RTP, legacy 0xD4 sync and all
     * non-B233/compatibility profiles unchanged.
     *
     * This is NOT evidence that missing RTCP caused the 20s peer EOF.
     */
    fun advertiseReverseControlPort(
        isBuickB233: Boolean,
        profile: AudioProfile,
    ): Boolean =
        useModernSenderTiming(isBuickB233, profile) ||
            (isBuickB233 &&
                profile.streamType == 100L &&
                profile.codec == AudioCodec.PCM &&
                profile.audioType.equals("media", ignoreCase = true))

    /**
     * T90: explicitly advertise high-accuracy RTP timestamp support for B233
     * PCM/type100 media. T89 proved that the receiver consumes advancing PCM
     * while returning no remote controlPort; the phone-local reverse port was
     * advertised but received no packets. This changes only the SETUP boolean.
     *
     * Keep useModernSenderTiming() AAC-only: PCM still retains its existing
     * legacy 0xD4 sender policy if a receiver ever supplies a remote controlPort.
     */
    fun advertiseHighAccuracyTimestamps(
        isBuickB233: Boolean,
        profile: AudioProfile,
    ): Boolean =
        useModernSenderTiming(isBuickB233, profile) ||
            (isBuickB233 &&
                profile.streamType == 100L &&
                profile.codec == AudioCodec.PCM &&
                profile.audioType.equals("media", ignoreCase = true))

    fun useModernSenderTiming(
        isBuickB233: Boolean,
        profile: AudioProfile,
    ): Boolean =
        isBuickB233 &&
            profile.streamType == 102L &&
            profile.codec == AudioCodec.AAC_LC &&
            profile.audioType.equals("media", ignoreCase = true)
}
