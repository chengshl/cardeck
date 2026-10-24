package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioUiStatusTest {
    @Test fun noAudioRequestedIsExplicitlyOff() {
        assertEquals(AudioUiStatus.NOT_REQUESTED, AudioUiStatus.fromSession(false, false, false, false))
    }

    @Test fun audioRequestedBeforeSenderStartedIsNotReportedOff() {
        assertEquals(AudioUiStatus.WAITING, AudioUiStatus.fromSession(true, false, false, false))
    }

    @Test fun activeSenderReportsStreamingNotGuaranteedAudibility() {
        assertEquals(AudioUiStatus.SENDING, AudioUiStatus.fromSession(true, true, true, false))
    }

    @Test fun senderStoppedUnexpectedlyReportsFailureNotUserChoiceOff() {
        assertEquals(AudioUiStatus.FAILED, AudioUiStatus.fromSession(true, true, false, false))
    }

    @Test fun automaticB233DowngradeRemainsVisibleAfterReconnect() {
        assertEquals(AudioUiStatus.DOWNGRADED, AudioUiStatus.fromSession(true, true, false, true))
    }

    @Test fun newExplicitVideoOnlySessionOverridesOldFailure() {
        assertEquals(AudioUiStatus.NOT_REQUESTED, AudioUiStatus.fromSession(false, false, false, false))
    }
}
