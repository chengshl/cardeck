package io.github.shaonialife.cardeck.audio

/**
 * T70: common post-SETUP RECORD completion for initial and late-granted audio.
 *
 * A late MediaProjection grant must not just start RTP: on B233 the audio
 * RECORD acknowledgement also releases the RTCP sync gate. Keep this unit
 * deliberately protocol-neutral; actual transport stays in SessionController.
 */
internal object AudioStreamActivation {
    fun finish(
        isBuickB233: Boolean,
        record: () -> Boolean,
        onConfirmed: () -> Unit,
        onRejected: () -> Unit,
    ): Boolean {
        val accepted = record()
        if (isBuickB233) {
            if (accepted) onConfirmed() else onRejected()
        }
        return accepted
    }
}
