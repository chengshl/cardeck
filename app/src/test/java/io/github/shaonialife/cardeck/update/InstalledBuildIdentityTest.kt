package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledBuildIdentityTest {
    @Test fun formalApkHasItsOwnImmutableOrigin() {
        assertEquals(InstalledBuildIdentity.Track.FORMAL, InstalledBuildIdentity.track("Buick-E5"))
        assertEquals("正式分支 · Buick-E5", InstalledBuildIdentity.description("Buick-E5"))
    }

    @Test fun experimentApkRetainsSourceBranch() {
        val source = "experiment/buick-e5-ui-t76"
        assertEquals(InstalledBuildIdentity.Track.EXPERIMENT, InstalledBuildIdentity.track(source))
        assertEquals("试验分支 · buick-e5-ui-t76", InstalledBuildIdentity.description(source))
    }

    @Test fun unknownBuildNeverAssumesTheSelectedUpdateTrack() {
        assertEquals(InstalledBuildIdentity.Track.UNKNOWN, InstalledBuildIdentity.track("unmarked"))
        assertEquals(InstalledBuildIdentity.Track.UNKNOWN, InstalledBuildIdentity.track("Buick-Test"))
    }
}
