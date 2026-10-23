package io.github.shaonialife.cardeck.audio

/**
 * Buick-main release gate: CarPlay audio-forwarding has not passed stable
 * E5 testing. A2DP media playback is deliberately NOT controlled here.
 *
 * Test branch remains free to experiment separately.
 */
internal object BuickMainAudioPolicy {
    const val CARPLAY_AUDIO_SUPPORTED = false

    fun effectiveAudioEnabled(requested: Boolean): Boolean =
        CARPLAY_AUDIO_SUPPORTED && requested

    fun shouldClaimCarPlayAudio(requested: Boolean): Boolean =
        CARPLAY_AUDIO_SUPPORTED && requested
}
