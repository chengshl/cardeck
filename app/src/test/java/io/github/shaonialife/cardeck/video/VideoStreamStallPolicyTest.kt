package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStreamStallPolicyTest {
    @Test fun streamWithActiveDataNeverTriggersRecovery() {
        assertFalse(VideoStreamStallPolicy.isStalled(100_000, 99_000, 15_000, true))
        assertFalse(VideoStreamStallPolicy.isStalled(115_000, 110_000, 15_000, true))
    }

    @Test fun deadDataWriterTriggersOnlyAfterThreshold() {
        assertFalse(VideoStreamStallPolicy.isStalled(24_999, 10_000, 15_000, true))
        assertTrue(VideoStreamStallPolicy.isStalled(25_000, 10_000, 15_000, true))
        assertTrue(VideoStreamStallPolicy.isStalled(35_000, 10_000, 15_000, true))
    }

    @Test fun retiredStreamAndDefaultPolicyNeverTrigger() {
        assertFalse(VideoStreamStallPolicy.isStalled(50_000, 10_000, 15_000, false))
        assertFalse(VideoStreamStallPolicy.isStalled(50_000, 10_000, 0, true))
        assertFalse(VideoStreamStallPolicy.isStalled(50_000, 0, 15_000, true))
        assertFalse(VideoStreamStallPolicy.isStalled(9_000, 10_000, 15_000, true))
    }
}
