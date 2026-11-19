package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAutoCheckTest {
    @Test fun throttlesRepeatedForegroundEntries() {
        assertTrue(UpdateAutoCheck.due(0L, 1_000_000L))
        assertFalse(UpdateAutoCheck.due(1_000_000L, 1_000_001L))
        assertFalse(UpdateAutoCheck.due(1_000_000L, 1_899_999L))
        assertTrue(UpdateAutoCheck.due(1_000_000L, 1_900_000L))
        assertTrue(UpdateAutoCheck.due(2_000_000L, 1_000_000L))
    }

    @Test fun onlyOneAutoCheckPerForegroundScreen() {
        val identity = UpdateAutoCheck.track("experiment", "experiment/buick-e5-detailed-diagnostics", 90000000L)
        assertNotEquals(identity + "|MainActivity", identity + "|UpdateCenterActivity")
    }
    @Test fun separateChannelsBuildsAndSources() {
        val current = UpdateAutoCheck.track("experiment", "experiment/buick-e5-detailed-diagnostics", 89000000L)
        assertNotEquals(current, UpdateAutoCheck.track("formal", "experiment/buick-e5-detailed-diagnostics", 89000000L))
        assertNotEquals(current, UpdateAutoCheck.track("experiment", "experiment/buick-e5-other", 89000000L))
        assertNotEquals(current, UpdateAutoCheck.track("experiment", "experiment/buick-e5-detailed-diagnostics", 89000001L))
    }
}
