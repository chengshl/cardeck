package io.github.shaonialife.cardeck.audio

/**
 * A deterministic byte-accurate write window for one fixed-size PCM capture
 * packet. AudioRecord.READ_BLOCKING can still return fewer bytes than asked.
 *
 * Call AudioRecord.read(frame, offset, remaining, READ_BLOCKING), then accept
 * its result. Only a full frame can advance RTP sequence/timestamp/nonce.
 * This class owns no Android resource and never retains actual sample bytes.
 */
internal class PcmCaptureFrameWindow(private val frameBytes: Int) {
    init {
        require(frameBytes > 0)
        require(frameBytes % 2 == 0) // s16 PCM
    }

    var pendingBytes: Int = 0
        private set

    val offset: Int get() = pendingBytes
    val remaining: Int get() = frameBytes - pendingBytes

    /**
     * @return true exactly when [frameBytes] have been accumulated.
     * Caller may then transmit; state resets for the next packet.
     */
    fun accept(bytes: Int): Boolean {
        require(bytes >= 0 && bytes <= remaining) { "capture bytes outside remaining window" }
        pendingBytes += bytes
        if (pendingBytes != frameBytes) return false
        pendingBytes = 0
        return true
    }

    /** Device read error / new session: never join prior partial audio. */
    fun discard() {
        pendingBytes = 0
    }
}
