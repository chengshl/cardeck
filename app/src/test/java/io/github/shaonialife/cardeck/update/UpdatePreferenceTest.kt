package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePreferenceTest {
    @Test fun defaultsToFormalWithoutSavedPreference() {
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse(null))
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse("unsupported"))
    }

    @Test fun parsesSelectedExperimentAndFormal() {
        assertEquals(UpdatePreference.Channel.EXPERIMENT, UpdatePreference.Channel.parse("experiment"))
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse("formal"))
    }
}
