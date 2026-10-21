package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * B233 pairing diagnostics: only observe Android Bluetooth broadcasts.
 * NEVER reply to SSP prompts, set PINs, create/remove bonds or launch fake UI.
 *
 * The iPhone HCI reference observed User Confirmation Request (0x33) followed
 * by User Confirmation Request Reply (0x042c) before iAP2 began.
 * Android owns that confirmation; CarDeck can only trace broadcast delivery.
 */
internal object B233PairingMonitor {
    private val gate = B233PairingGate()
    private val knownAddresses = java.util.Collections.synchronizedSet(HashSet<String>())
    private var registered = false

    /** Remember verified B233 candidates; Android may not resolve name at SSP time. */
    fun trackKnownReceiver(address: String) {
        knownAddresses.add(address.uppercase(java.util.Locale.ROOT))
    }

    @Synchronized
    fun start(context: Context, log: (String) -> Unit) {
        if (registered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("BT-PAIR T19: 缺少 BLUETOOTH_CONNECT 权限，无法观察 B233 配对广播")
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                val name = runCatching { device.name }.getOrNull()
                val address = runCatching { device.address }.getOrNull()
                val selected = TargetDevicePrefs.get(ctx)
                val bySelectedAddress = selected != null &&
                    BuickB233Route.isKnownReceiver(selected.name) &&
                    TargetDevicePrefs.matches(selected, address)
                val byKnownAddress = address != null &&
                    knownAddresses.contains(address.uppercase(java.util.Locale.ROOT))
                if (!BuickB233Route.isKnownReceiver(name) &&
                    !bySelectedAddress && !byKnownAddress
                ) return
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        gate.onPairingRequest(SystemClock.elapsedRealtime())
                        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                        log("BT-PAIR T19: B233 收到 Android 系统配对确认请求 variant=$variant；" +
                            "请查看手机系统弹窗并手动确认（本应用不会自动批准）")
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val from = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        val to = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        gate.onBondState(to)
                        log("BT-PAIR T19: B233 配对状态 ${bondStateName(from)} -> ${bondStateName(to)}")
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED ->
                        log("BT-PAIR T19: B233 ACL 物理蓝牙链路建立（不等于 iAP2/CarPlay 已接受）")
                    BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                        log("BT-PAIR T19: B233 ACL 物理蓝牙链路断开（可能触发后续重新认证）")
                }
            }
        }
        try {
            // Bluetooth broadcasts may be emitted by the privileged Bluetooth
            // process, not this app. RECEIVER_EXPORTED is required for delivery.
            ContextCompat.registerReceiver(context.applicationContext, receiver, filter,
                ContextCompat.RECEIVER_EXPORTED)
            registered = true
            log("BT-PAIR T19: B233 Android 配对/ACL 事件监听已开启（只记录不自动确认）")
        } catch (error: Exception) {
            log("BT-PAIR T19: 无法注册系统蓝牙事件监听：${error.javaClass.simpleName}")
        }
    }

    fun isWaitingForConfirmation(nowMs: Long): Boolean = gate.isWaiting(nowMs)

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN($state)"
    }
}

/** Deterministic, bounded guard; a missing Android broadcast cannot stall forever. */
internal class B233PairingGate {
    companion object {
        const val CONFIRMATION_WINDOW_MS = 45_000L
    }
    private var pendingUntilMs = 0L

    @Synchronized
    fun onPairingRequest(nowMs: Long) {
        pendingUntilMs = nowMs + CONFIRMATION_WINDOW_MS
    }

    @Synchronized
    fun onBondState(state: Int) {
        if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) {
            pendingUntilMs = 0L
        }
    }

    @Synchronized
    fun isWaiting(nowMs: Long): Boolean = nowMs < pendingUntilMs
}
