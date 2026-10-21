package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
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
            // T25: adapter power state is different from B233 ACL link state.
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            // B233 uses regular Bluetooth A2DP for music in video-only mode.
            // The ACL can recover while A2DP remains disconnected.
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                // Adapter power events have no EXTRA_DEVICE. They must be
                // inspected BEFORE the B233-device filter below.
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val from = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1)
                    val to = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    log(
                        "BT-ADAPTER T25: 手机蓝牙开关状态 " +
                            "${adapterStateName(from)} -> ${adapterStateName(to)}；" +
                            "仅观察，CarDeck 不执行蓝牙开关操作"
                    )
                    return
                }
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
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val profileName =
                            if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                                "A2DP(音乐)" else "HFP(通话)"
                        val oldState = intent.getIntExtra(
                            BluetoothProfile.EXTRA_PREVIOUS_STATE, -1
                        )
                        val newState = intent.getIntExtra(
                            BluetoothProfile.EXTRA_STATE, -1
                        )
                        log(
                            "BT-AUDIO T25: B233 $profileName " +
                                "${profileStateName(oldState)} -> ${profileStateName(newState)}；" +
                                "只记录，不主动断开/重连音乐配置文件"
                        )
                    }
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
            log("BT-AUDIO T25: 已启用 B233 HFP/A2DP 连接变化监听；不修改普通蓝牙音频连接")
            // Read the initial radio state so that a missing OFF broadcast
            // cannot be mistaken for proof the radio was always enabled.
            val initial = runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE)
                    as android.bluetooth.BluetoothManager).adapter?.state
            }.getOrNull()
            log(
                "BT-ADAPTER T25: 已启用手机蓝牙开关状态监听；当前=" +
                    (initial?.let(::adapterStateName) ?: "UNKNOWN") +
                    "；ACL 断开不等于蓝牙开关关闭"
            )
        } catch (error: Exception) {
            log("BT-PAIR T19: 无法注册系统蓝牙事件监听：${error.javaClass.simpleName}")
        }
    }

    fun isWaitingForConfirmation(nowMs: Long): Boolean = gate.isWaiting(nowMs)

    private fun profileStateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    private fun adapterStateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "UNKNOWN($state)"
    }

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
