package io.github.shaonialife.cardeck

import org.junit.Assert.*
import org.junit.Test

class E5DisconnectPolicyTest {
    @Test fun onlyRecordedScreenWithActiveVideoGetsTeardown() {
        assertTrue(E5DisconnectPolicy.shouldTryTeardown(true, true, true))
        assertFalse(E5DisconnectPolicy.shouldTryTeardown(false, true, true))
        assertFalse(E5DisconnectPolicy.shouldTryTeardown(true, false, false))
        assertFalse(E5DisconnectPolicy.shouldTryTeardown(true, true, false))
        assertFalse(E5DisconnectPolicy.shouldTryTeardown(false, false, false))
    }
    @Test fun screenSessionCanBeVideoOnly() {
        // Audio is deliberately not an input: screen teardown is shared.
        assertTrue(E5DisconnectPolicy.shouldTryTeardown(
            isVideoStreaming = true, hasClient = true, clientOwnsRecordedScreen = true))
    }
}
