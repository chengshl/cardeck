package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePreferenceTest {
    @Test fun defaultsToFormalWithoutSavedPreference() {
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse(null))
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse("unsupported"))
    }

    @Test fun rejectsInvalidProjectSources() {
        assertNull(UpdatePreference.validProjectSource(null))
        assertNull(UpdatePreference.validProjectSource(""))
        assertNull(UpdatePreference.validProjectSource("Buick-Test"))
        assertNull(UpdatePreference.validProjectSource("experiment/../../bad"))
        assertNull(UpdatePreference.validProjectSource("experiment/BadCase"))
        assertEquals("experiment/buick-e5-session-teardown",
            UpdatePreference.validProjectSource("experiment/buick-e5-session-teardown"))
    }

    @Test fun parsesSelectedExperimentAndFormal() {
        assertEquals(UpdatePreference.Channel.EXPERIMENT, UpdatePreference.Channel.parse("experiment"))
        assertEquals(UpdatePreference.Channel.FORMAL, UpdatePreference.Channel.parse("formal"))
    }
}
