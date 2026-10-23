package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertFalse
import org.junit.Test

class BuickMainAudioPolicyTest {
    @Test fun explicitAudioChoiceCannotEnableForwarding() {
        assertFalse(BuickMainAudioPolicy.effectiveAudioEnabled(true))
        assertFalse(BuickMainAudioPolicy.effectiveAudioEnabled(false))
    }

    @Test fun neitherVideoOnlyNorLegacyAudioStateClaimsCarPlayAudio() {
        assertFalse(BuickMainAudioPolicy.shouldClaimCarPlayAudio(true))
        assertFalse(BuickMainAudioPolicy.shouldClaimCarPlayAudio(false))
    }
}
