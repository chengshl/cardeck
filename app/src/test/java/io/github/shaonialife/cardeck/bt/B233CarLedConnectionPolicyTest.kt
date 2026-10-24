package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertEquals
import org.junit.Test

class B233CarLedConnectionPolicyTest {
    @Test fun serviceStartDoesNotTriggerOutboundConnection() {
        val p = B233CarLedConnectionPolicy()
        assertEquals(false, p.claimOutboundAttempt(1_000L))
        assertEquals(false, p.claimOutboundAttempt(30_000L))
    }

    @Test fun receiverSignalAllowsOnlyTwoAttemptsWithinLimitedWindow() {
        val p = B233CarLedConnectionPolicy()
        assertEquals(true, p.onVehicleBluetoothConnected(10_000L))
        assertEquals(true, p.claimOutboundAttempt(11_000L))
        assertEquals(true, p.claimOutboundAttempt(20_000L))
        assertEquals(false, p.claimOutboundAttempt(21_000L))
        assertEquals(true, p.onVehicleBluetoothConnected(60_000L))
        assertEquals(false, p.claimOutboundAttempt(105_000L))
    }

    @Test fun rapidAclAndProfileBroadcastsDoNotResetAttemptBudget() {
        val p = B233CarLedConnectionPolicy()
        assertEquals(true, p.onVehicleBluetoothConnected(1_000L))
        assertEquals(true, p.claimOutboundAttempt(1_001L))
        assertEquals(false, p.onVehicleBluetoothConnected(2_000L))
        assertEquals(true, p.claimOutboundAttempt(2_001L))
        assertEquals(false, p.claimOutboundAttempt(2_002L))
    }

    @Test fun successfulOrReverseSessionConsumesThePendingInvitation() {
        val p = B233CarLedConnectionPolicy()
        p.onVehicleBluetoothConnected(1_000L)
        p.clearPendingInvitation()
        assertEquals(false, p.claimOutboundAttempt(2_000L))
        assertEquals(true, p.onVehicleBluetoothConnected(7_000L))
        assertEquals(true, p.claimOutboundAttempt(7_001L))
        p.reset()
        assertEquals(false, p.claimOutboundAttempt(8_000L))
    }

    @Test fun explicitDiagnosticProbeIsSingleUse() {
        val p = B233CarLedConnectionPolicy()
        p.requestOneManualProbe(1_000L)
        assertEquals(true, p.claimOutboundAttempt(1_001L))
        assertEquals(false, p.claimOutboundAttempt(1_002L))
        p.requestOneManualProbe(3_000L)
        assertEquals(false, p.claimOutboundAttempt(18_000L))
    }

    @Test fun signalCanWaitUntilAudioTeardownCooldownEnds() {
        val p = B233CarLedConnectionPolicy()
        p.onVehicleBluetoothConnected(3_000L)
        assertEquals(true, p.claimOutboundAttempt(24_000L))
        assertEquals(false, p.claimOutboundAttempt(48_000L))
    }
}
