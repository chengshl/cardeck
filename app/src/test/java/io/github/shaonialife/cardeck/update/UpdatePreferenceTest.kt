package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePreferenceTest {
    @Test fun defaultsToFormalWithoutSavedPreference() {
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse(null))
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse("unsupported"))
    }

    @Test fun installedExperimentsDefaultToExperimentalUpdates() {
        assertEquals(UpdatePreference.Channel.EXPERIMENT,
            UpdatePreference.defaultForOrigin("experiment/buick-e5-detailed-diagnostics"))
        assertEquals(UpdatePreference.Channel.FORMAL,
            UpdatePreference.defaultForOrigin("Buick-E5"))
        assertEquals(UpdatePreference.Channel.FORMAL,
            UpdatePreference.defaultForOrigin("unmarked"))
    }

    @Test fun parsesSelectedExperimentAndFormal() {
        assertEquals(UpdatePreference.Channel.EXPERIMENT, UpdatePreference.Channel.parse("experiment"))
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse("formal"))
    }
}
