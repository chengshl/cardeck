package io.github.shaonialife.cardeck.bt

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B233PairingGateTest {
    @Test fun requestPausesReconnectUntilConfirmedOrExpired() {
        val gate = B233PairingGate()
        assertFalse(gate.isWaiting(100L))
        gate.onPairingRequest(1_000L)
        assertTrue(gate.isWaiting(1_000L))
        assertTrue(gate.isWaiting(45_999L))
        assertFalse(gate.isWaiting(46_000L))
    }

    @Test fun bondedOrCancelledPairingReleasesReconnectGuard() {
        val gate = B233PairingGate()
        gate.onPairingRequest(100L)
        gate.onBondState(BluetoothDevice.BOND_BONDING)
        assertTrue(gate.isWaiting(200L))
        gate.onBondState(BluetoothDevice.BOND_BONDED)
        assertFalse(gate.isWaiting(200L))
        gate.onPairingRequest(300L)
        gate.onBondState(BluetoothDevice.BOND_NONE)
        assertFalse(gate.isWaiting(350L))
    }

    @Test fun nextPairingRequestRearmsOnlyBoundedGuard() {
        val gate = B233PairingGate()
        gate.onPairingRequest(0L)
        gate.onPairingRequest(30_000L)
        assertTrue(gate.isWaiting(45_000L))
        assertFalse(gate.isWaiting(75_000L))
    }
}
