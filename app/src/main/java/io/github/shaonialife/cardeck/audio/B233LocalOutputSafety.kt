package io.github.shaonialife.cardeck.audio

/**
 * B233 phone-speaker suppression safety policy, intentionally NOT wired to platform audio controls.
 *
 * This is a pure decision model for a future opt-in experiment, not evidence that
 * Android PlaybackCapture remains nonzero after changing STREAM_MUSIC volume.
 * No production caller is permitted until the same-session phone capture and
 * car speaker checks are independently proven on the target device.
 */
internal object B233LocalOutputSafety {
    internal enum class TrialBlock {
        NONE, NOT_OPTED_IN, NO_CAPTURE, CAR_AUDIO_UNCONFIRMED,
        CALL_ACTIVE, VOLUME_UNAVAILABLE, ALREADY_MUTED,
    }

    internal fun trialBlock(
        optedIn: Boolean,
        capturedPeak: Int,
        carAudioConfirmed: Boolean,
        callActive: Boolean,
        currentVolume: Int,
        maxVolume: Int,
    ): TrialBlock = when {
        !optedIn -> TrialBlock.NOT_OPTED_IN
        capturedPeak <= 0 -> TrialBlock.NO_CAPTURE
        !carAudioConfirmed -> TrialBlock.CAR_AUDIO_UNCONFIRMED
        callActive -> TrialBlock.CALL_ACTIVE
        currentVolume < 0 || maxVolume <= 0 || currentVolume > maxVolume ->
            TrialBlock.VOLUME_UNAVAILABLE
        currentVolume == 0 -> TrialBlock.ALREADY_MUTED
        else -> TrialBlock.NONE
    }

    /**
     * A trial is never a successful fix until capture is verified AGAIN after
     * suppression, while the receiver is still audibly playing.
     */
    internal fun keepTrialSuppression(
        postSuppressionCapturePeak: Int,
        carAudioStillConfirmed: Boolean,
        callActive: Boolean,
    ): Boolean = postSuppressionCapturePeak > 0 && carAudioStillConfirmed && !callActive

    /**
     * Only a still-owned mute may be restored. If the user has changed volume
     * to a nonzero value, leave it alone. A user selecting zero again cannot be
     * distinguished from the app's zero without an external ownership signal;
     * this helper deliberately does not claim to solve process-death recovery.
     * A user may change volume away from zero and then return it to zero;
     * checking currentVolume alone would overwrite that user decision.
     * A future caller MUST independently track volumeChangeObservedSinceMute
     * and fail closed if volume-change observation was lost.
     */
    internal fun restoreOriginalVolume(
        sameSessionOwner: Boolean,
        volumeChangeObservedSinceMute: Boolean,
        originalVolume: Int,
        currentVolume: Int,
        maxVolume: Int,
    ): Boolean =
        sameSessionOwner &&
            !volumeChangeObservedSinceMute &&
            originalVolume in 1..maxVolume &&
            maxVolume > 0 &&
            currentVolume == 0
}
