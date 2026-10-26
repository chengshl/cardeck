package io.github.shaonialife.cardeck.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentInstallPolicyTest {
    @Test fun higherVersionIsUpgrade() {
        assertEquals(ExperimentInstallPolicy.Mode.UPGRADE, ExperimentInstallPolicy.mode(100, 101))
    }

    @Test fun olderAndEqualVersionsAreDownloadOnly() {
        assertEquals(ExperimentInstallPolicy.Mode.SAVE_ONLY, ExperimentInstallPolicy.mode(100, 99))
        assertEquals(ExperimentInstallPolicy.Mode.SAVE_ONLY, ExperimentInstallPolicy.mode(100, 100))
    }
}
