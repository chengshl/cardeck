package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertEquals
import org.junit.Test

class B233CarLedConnectionPolicyTest {
    private val carA = "01:23:45:67:89:AB"
    private val carB = "10:32:54:76:98:BA"

    @Test fun serviceStartupNeverAutomaticallyDials() {
        val p = B233CarLedConnectionPolicy()
        assertEquals(false, p.claimOutboundAttempt(carA, 1_000L))
        assertEquals(false, p.claimOutboundAttempt(carB, 50_000L))
    }

    @Test fun aclEventWaitsForInboundAndAllowsTwoBoundedDialsOnlyToOriginator() {
        val p = B233CarLedConnectionPolicy()
        assertEquals(true, p.onVehicleAclConnected(carA, 10_000L))
        assertEquals(false, p.claimOutboundAttempt(carA, 10_500L))
        assertEquals(false, p.claimOutboundAttempt(carB, 12_000L))
        assertEquals(true, p.claimOutboundAttempt(carA.lowercase(), 12_000L))
        assertEquals(true, p.claimOutboundAttempt(carA, 20_000L))
        assertEquals(false, p.claimOutboundAttempt(carA, 21_000L))
    }

    @Test fun delayedHfpOrRepeatedAclCannotRearmWithoutPhysicalDisconnect() {
        val p = B233CarLedConnectionPolicy()
        assertEquals(true, p.onVehicleAclConnected(carA, 1_000L))
        assertEquals(true, p.claimOutboundAttempt(carA, 3_000L))
        p.clearPendingInvitation()
        // The HFP profile can reconnect even while the same ACL stays up.
        assertEquals(false, p.onVehicleAclConnected(carA, 10_000L))
        assertEquals(false, p.claimOutboundAttempt(carA, 10_100L))
        assertEquals(false, p.onVehicleAclConnected(carA, 80_000L))
        assertEquals(false, p.onVehicleAclDisconnected(carB))
        assertEquals(true, p.onVehicleAclDisconnected(carA))
        assertEquals(true, p.onVehicleAclConnected(carA, 90_000L))
        assertEquals(true, p.claimOutboundAttempt(carA, 92_000L))
    }

    @Test fun acceptedReverseSessionConsumesTheOriginalAclInvitation() {
        val p = B233CarLedConnectionPolicy()
        p.onVehicleAclConnected(carA, 1_000L)
        p.clearPendingInvitation()
        assertEquals(false, p.claimOutboundAttempt(carA, 5_000L))
        assertEquals(false, p.onVehicleAclConnected(carA, 20_000L))
    }

    @Test fun signalExpiresBeforeLateAutomaticDial() {
        val p = B233CarLedConnectionPolicy()
        p.onVehicleAclConnected(carA, 3_000L)
        assertEquals(true, p.claimOutboundAttempt(carA, 24_000L))
        assertEquals(false, p.claimOutboundAttempt(carA, 48_000L))
    }

    @Test fun manualDiagnosticIsSingleUseAndCanBindToSelectedCar() {
        val p = B233CarLedConnectionPolicy()
        p.requestOneManualProbe(1_000L, carB)
        assertEquals(false, p.claimOutboundAttempt(carA, 1_001L))
        assertEquals(true, p.claimOutboundAttempt(carB, 1_001L))
        assertEquals(false, p.claimOutboundAttempt(carB, 1_002L))
        p.requestOneManualProbe(3_000L, null)
        assertEquals(false, p.claimOutboundAttempt(carA, 18_000L))
    }

    @Test fun manualStopAndPhysicalDisconnectClearPermission() {
        val p = B233CarLedConnectionPolicy()
        p.onVehicleAclConnected(carA, 1_000L)
        p.reset()
        assertEquals(false, p.claimOutboundAttempt(carA, 3_000L))
        assertEquals(true, p.onVehicleAclConnected(carA, 5_000L))
        assertEquals(true, p.onVehicleAclDisconnected(carA))
        assertEquals(false, p.claimOutboundAttempt(carA, 8_000L))
    }
}
