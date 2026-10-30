package io.github.shaonialife.cardeck.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * T77: explicit opt-in low-amplitude local test tone, inserted into the PCM source before
 * the existing AAC/PCM encoding and RTP pipeline. No media recording or packet format changes.
 *
 * This is NOT a production playback source; the experiment never enables it by default.
 */
internal class DiagnosticPcmTone(
    private val sampleRate: Int,
    private val channels: Int = 2,
    private val frequencyHz: Int = 440,
    private val amplitude: Int = 1_000,
) {
    init {
        require(sampleRate > 0 && channels > 0 && frequencyHz > 0)
        require(amplitude in 1..8_192)
    }

    private val periodFrames: Int = sampleRate / gcd(sampleRate, frequencyHz)
    private val cycle: ShortArray = ShortArray(periodFrames) { frame ->
        (sin(2.0 * PI * frequencyHz * frame / sampleRate) * amplitude).toInt().toShort()
    }
    private var framePosition: Long = 0L

    fun fill(pcm: ByteArray, bytes: Int = pcm.size) {
        require(bytes in 0..pcm.size)
        val stride = channels * 2
        require(bytes % stride == 0) { "PCM buffer must contain whole stereo frames" }
        var offset = 0
        while (offset < bytes) {
            val sample = cycle[(framePosition % periodFrames).toInt()].toInt()
            for (channel in 0 until channels) {
                pcm[offset++] = (sample and 0xff).toByte()
                pcm[offset++] = ((sample ushr 8) and 0xff).toByte()
            }
            framePosition++
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val r = x % y
            x = y
            y = r
        }
        return x
    }
}
