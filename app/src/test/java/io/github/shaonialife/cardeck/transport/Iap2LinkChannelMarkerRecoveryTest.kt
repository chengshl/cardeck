package io.github.shaonialife.cardeck.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkChannelMarkerRecoveryTest {
    @Test fun echoOnlyAfterFourNewMarkersWhenControlAckStalled() {
        assertFalse(shouldProbeB233Detect(false, 10L, 110, 109, false))
        assertFalse(shouldProbeB233Detect(true, 3L, 110, 109, false))
        assertFalse(shouldProbeB233Detect(true, 4L, 110, 110, false))
        assertFalse(shouldProbeB233Detect(true, 4L, null, 109, false))
        assertFalse(shouldProbeB233Detect(true, 4L, 110, 109, true))
        assertTrue(shouldProbeB233Detect(true, 4L, 110, 109, false))
        assertTrue(shouldProbeB233Detect(true, 20L, 0, 255, false))
    }

    @Test fun acknowledgedNormalLinkDoesNotEchoRepeatedDetect() {
        assertFalse(shouldProbeB233Detect(
            normal = true, markerDelta = 300L,
            latestSent = 110, peerAck = 110, used = false
        ))
    }
}
