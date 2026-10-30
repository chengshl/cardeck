package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic policy checks only. No AudioManager writes, device, or vehicle. */
class B233LocalOutputSafetyTest {
    private fun gate(
        optedIn: Boolean = true,
        peak: Int = 120,
        audible: Boolean = true,
        call: Boolean = false,
        volume: Int = 7,
        max: Int = 15,
    ) = B233LocalOutputSafety.trialBlock(optedIn, peak, audible, call, volume, max)

    @Test fun positiveCaptureAndConfirmedCarSoundAreOnlyTrialEligibility() {
        assertEquals(B233LocalOutputSafety.TrialBlock.NONE, gate())
        assertFalse(B233LocalOutputSafety.keepTrialSuppression(0, true, false))
        assertTrue(B233LocalOutputSafety.keepTrialSuppression(120, true, false))
    }

    @Test fun neverSuppressBeforeOptInOrNonzeroCapture() {
        assertEquals(B233LocalOutputSafety.TrialBlock.NOT_OPTED_IN, gate(optedIn = false))
        assertEquals(B233LocalOutputSafety.TrialBlock.NO_CAPTURE, gate(peak = 0))
        assertEquals(B233LocalOutputSafety.TrialBlock.NO_CAPTURE, gate(peak = -1))
    }

    @Test fun neverSuppressBeforeCarAudibilityOrDuringCall() {
        assertEquals(B233LocalOutputSafety.TrialBlock.CAR_AUDIO_UNCONFIRMED, gate(audible = false))
        assertEquals(B233LocalOutputSafety.TrialBlock.CALL_ACTIVE, gate(call = true))
        assertFalse(B233LocalOutputSafety.keepTrialSuppression(120, true, true))
        assertFalse(B233LocalOutputSafety.keepTrialSuppression(120, false, false))
    }

    @Test fun invalidOrAlreadyMutedVolumeCannotAcquireOwnership() {
        assertEquals(B233LocalOutputSafety.TrialBlock.VOLUME_UNAVAILABLE, gate(volume = -1))
        assertEquals(B233LocalOutputSafety.TrialBlock.VOLUME_UNAVAILABLE, gate(volume = 16))
        assertEquals(B233LocalOutputSafety.TrialBlock.VOLUME_UNAVAILABLE, gate(max = 0))
        assertEquals(B233LocalOutputSafety.TrialBlock.ALREADY_MUTED, gate(volume = 0))
    }

    @Test fun onlyOwnedUnchangedMuteMayRestoreOriginal() {
        assertTrue(B233LocalOutputSafety.restoreOriginalVolume(true, false, 7, 0, 15))
        assertFalse(B233LocalOutputSafety.restoreOriginalVolume(false, false, 7, 0, 15))
        assertFalse(B233LocalOutputSafety.restoreOriginalVolume(true, false, 7, 5, 15))
        assertFalse(B233LocalOutputSafety.restoreOriginalVolume(true, false, 0, 0, 15))
        assertFalse(B233LocalOutputSafety.restoreOriginalVolume(true, false, 16, 0, 15))
        assertFalse(B233LocalOutputSafety.restoreOriginalVolume(true, false, 7, 0, 0))
    }

    @Test fun userVolumeChangedThenReturnedToZeroMustNeverBeOverwritten() {
        // Synthetic sequence: app mute 7 -> 0, user selects 5, then selects 0.
        // Final zero does not mean the app still owns that volume state.
        assertFalse(B233LocalOutputSafety.restoreOriginalVolume(true, true, 7, 0, 15))
        assertFalse(B233LocalOutputSafety.restoreOriginalVolume(true, true, 7, 5, 15))
        assertTrue(B233LocalOutputSafety.restoreOriginalVolume(true, false, 7, 0, 15))
    }

    @Test fun postMuteCaptureDropRequiresImmediateAbortNotSuccessClaim() {
        assertFalse(B233LocalOutputSafety.keepTrialSuppression(0, true, false))
        assertFalse(B233LocalOutputSafety.keepTrialSuppression(-1, true, false))
        assertFalse(B233LocalOutputSafety.keepTrialSuppression(500, false, false))
        assertFalse(B233LocalOutputSafety.keepTrialSuppression(500, true, true))
    }
}
