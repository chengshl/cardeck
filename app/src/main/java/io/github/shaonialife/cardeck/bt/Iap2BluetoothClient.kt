package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import io.github.shaonialife.cardeck.gateway.GatewayVehicleIdentity
import io.github.shaonialife.cardeck.buick.BuickE5HandshakePrefs
import io.github.shaonialife.cardeck.buick.BuickE5HandshakeTarget
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）**主动连接车机**的无线 iAP2 RFCOMM 客户端 —— 真车阶段新增。
 *
 * 真无线 CarPlay 建链方向（对照 carplay-wifi-extractor client 模式，已在真车验证）：
 * 手机与车机完成系统级蓝牙配对后，由**手机主动连入**车机 SDP 广播的配件端服务
 * （UUID 00000000-deca-fade-deca-deafdecacaff，奥迪 MIB3 SDP 实测存在）；
 * 车机从不主动回连手机（测试台 DiPlay 会回连，那是接收端的测试行为）。
 *
 * 连上后复用与测试台完全相同的发送端 bring-up（[Iap2SenderSession]），
 * 成功时回调 [onEndpoint] 交出热点凭据与 AirPlay 端点。
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：选择后无需重启链路，下一轮尝试即生效）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
) {
    companion object {
        /**
         * 配件端（车机）无线 iAP2 服务 UUID。
         * 手机端 UUID 是 …cafe（xcertplay IAP2_IPHONE_UUID），配件端是 …caff。
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /** 「蓝牙已连接→已断开」日志节流（车机可能反复自动重连，避免刷屏）。 */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /** SDP 服务探测等待窗（fetchUuidsWithSdp → ACTION_UUID 广播）。 */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)
    // T17: B233 only. Avoid hammering a head unit that has just torn down an
    // audio CarPlay session or silently ignored the 0x4300 request.
    private val b233ReconnectPolicy = B233ReconnectPolicy()

    /**
     * 客户端线程世代号：start()/stop() 都会递增。卡在阻塞 connect() 里的线程被
     * stop() 解除阻塞后（关闭 connecting socket），会因世代不符直接退出——
     * 防止「stop 后线程残活、用户再次启动时新旧两代并发连车机」
     * （2026-10-11 实测：多个僵尸线程并发 RFCOMM 会让车机频繁拆链）。
     */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    /** 目标设备不在已配对列表的提示节流（避免每 3 秒刷屏）。 */
    private var lastTargetMissingLogAt = 0L

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 「是否提供 iAP2 服务」结论缓存（地址→结论）：SDP 查询不便宜，勿每 3s 重查。 */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 设备是否提供配件端 iAP2 服务（…caff）：先读系统 uuids 缓存，缺失再
     * `fetchUuidsWithSdp` 触发一次 SDP 查询并等 `ACTION_UUID` 广播（超时按无服务）。
     * 结论缓存到客户端停止——车机固件升级换 UUID 的场景由重启 App 覆盖。
     * 权限：start() 已校验 BLUETOOTH_CONNECT；uuids/fetchUuidsWithSdp 的
     * SecurityException 由 runCatching 吸收（探测失败按无服务处理，不断音频）。
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Boolean {
        iap2ServiceCache[device.address]?.let { return it }

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return true
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: android.content.Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address == device.address) latch.countDown()
                }
            }
        val started =
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                device.fetchUuidsWithSdp()
            }.getOrDefault(false)
        if (started) {
            try {
                latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        runCatching { context.unregisterReceiver(receiver) }
        val has = cachedHas()
        iap2ServiceCache[device.address] = has
        return has
    }

    /** 「蓝牙已连接→已断开」日志节流时间戳。 */
    private var lastProfileLogAt = 0L

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: 蓝牙不可用或未开启")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        iap2ServiceCache.clear()
        skippedNoService.clear()
        b233ReconnectPolicy.reset()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT-C: 启动——将主动连入车机的无线 iAP2 服务（…caff）" +
                if (target != null) {
                    "，目标：「${target.name}」${target.address}（只连该设备）"
                } else {
                    "（未指定目标：依次尝试全部候选设备，可在「连接准备」中指定）"
                },
        )
        Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        var lastCooldownLogUntil = 0L
        while (running && generation.get() == myGeneration) {
            // 静默降噪（2026-10-10 用户实测后启用）——会话期间完全静默，不产生任何连接尝试/日志：
            //  - inboundActive：接收端正在连我们（两条 RFCOMM 会互相踩；Audi 实测双会话混乱）
            //  - sessionActive：CarPlay 会话已建立（已拿到端点）——与其它接收端的连接尝试全部暂停
            //    典型场景：连着 Redmi/DiPlay 时不再无脑探测车机 Audi（消除日志噪音与蓝牙churn）；
            //    会话结束（任一端 BT 会话终结 / 手动断开）时自动恢复。
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                sleepQuietly(2_000)
                continue
            }
            // T17: B233 does not reliably clear its 0x4300/iAP2 state after a
            // short audio teardown. Give the receiver time to release the
            // previous CarPlay slot instead of immediately forcing RFCOMM
            // reconnect + HFP/A2DP disconnect every three seconds.
            val remainingB233 = b233ReconnectPolicy.remainingMs(
                android.os.SystemClock.uptimeMillis(),
            )
            if (remainingB233 > 0L) {
                val until = b233ReconnectPolicy.untilMs()
                if (until != lastCooldownLogUntil) {
                    lastCooldownLogUntil = until
                    log("BT-C T17: B233 会话冷却剩余 ${(remainingB233 + 999) / 1000}s；" +
                        "等待车机释放旧 CarPlay 会话（仅暂停自动重连）")
                }
                sleepQuietly(minOf(1_000L, remainingB233))
                continue
            }
            val candidates = candidateDevices(adapter)
            if (candidates.isEmpty()) {
                log("BT-C: 没有已配对的蓝牙设备——请先在系统设置里与车机完成蓝牙配对")
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            var connected: BluetoothSocket? = null
            var connectedKnownBuick = false
            var connectedForcedE5 = false
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                // B233 receiver (B233) was verified with an outbound RFCOMM link
                // in T1, but Android sometimes reports no accessory iAP2 UUID
                // in its SDP cache. Bypass only this known receiver, never all
                // paired devices (headphones/watch still require SDP).
                val forcedE5 = BuickE5HandshakeTarget.shouldForce(
                    BuickE5HandshakePrefs.enabled(context),
                    targetProvider()?.address,
                    device.address,
                    name,
                )
                val knownBuick = forcedE5 || BuickB233Route.useB233Transport(
                    name,
                    ReceiverCompatibilityMemory.wasB233Observed(context, device.address),
                )
                if (forcedE5) log("BT-C: Test 实验开关生效，本轮使用 E5/B233 连接与握手（不写入 B233 自动识别记忆）")
                if (knownBuick) B233PairingMonitor.trackKnownReceiver(device.address)
                if (targetProvider() == null && knownBuick &&
                    skippedNoService.add("B233:" + device.address)
                ) {
                    log("BT-C: 自动识别 B233 receiver（B233）：SDP 可能缺少 iAP2 UUID，仍尝试主动连入")
                }
                // 自动模式（未选目标）先过 SDP 闸门：候选=全部已配对设备，耳机/手表
                // 没有 iAP2 服务，连不上还白白被断 HFP/A2DP（设备级断开兜底更会波及
                // 其它在线设备）——确认提供 …caff 才进入「断开+连接」流程。选定目标
                // 的设备不过闸（用户显式指定，保持原路径与时序）。
                if (targetProvider() == null && !knownBuick && !awaitIap2Service(device)) {
                    if (skippedNoService.add(device.address)) {
                        log("BT-C: 跳过「$name」（未发现 iAP2 服务——非 CarPlay 设备，保持其音频连接）")
                    }
                    continue
                }
                // T19: Android owns the SSP confirmation. A reconnect that
                // disconnects the ACL link while the system prompt is visible
                // can invalidate the pending user choice. This is B233-only
                // and never calls setPairingConfirmation/creates a bond.
                if (knownBuick &&
                    B233PairingMonitor.isWaitingForConfirmation(
                        android.os.SystemClock.elapsedRealtime(),
                    )
                ) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                        lastProfileLogAt = now
                        log("BT-C T19: 检测到 B233 蓝牙系统确认待处理；暂停主动连接和 HFP/A2DP 断开，等待用户确认")
                    }
                    // Do not fall through to a different paired receiver.
                    break
                }
                // 「只配对、不连接」：奥迪 MIB3 实测手机与车机处于蓝牙已连接状态
                // （HFP/A2DP 在线）时，车机不开放无线 CarPlay 的 iAP2 服务窗口——
                // 表现为每 3s 连接失败。iAP2 是独立的 SDP 直连服务，与这些配置文件
                // 无关，断开后不影响 CarPlay 本身（声音走 WiFi 的 AirPlay）。
                if (knownBuick) {
                    // T22 B233-only A/B experiment:
                    // keep HFP/A2DP/LE_AUDIO unchanged and observe whether the
                    // previously unacknowledged 0x4300 (seq110) gets a peer ACK.
                    // Audi MIB3 may REQUIRE the legacy disconnect path, so
                    // retain the original branch for every non-B233 receiver.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                        lastProfileLogAt = now
                        val active = BtProfiles.connectedNames(context, device.address)
                        log(
                            "BT-C T22: B233 实验：保留 HFP/A2DP/LE 连接，不主动断开；" +
                                "当前配置文件=${if (active.isEmpty()) "无" else active.joinToString("/")}",
                        )
                    }
                } else {
                    run {
                        val dropped = BtProfiles.disconnect(context, device)
                        if (dropped.isNotEmpty()) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                                lastProfileLogAt = now
                                val left = BtProfiles.stillConnected(context, device.address)
                                log(
                                    "BT-C: $name 处于蓝牙已连接状态（${dropped.joinToString("/")}）——已断开" +
                                        if (left.isEmpty()) "" else "（仍有 ${left.joinToString("/")} 在线）",
                                )
                                log("BT-C: 车机在「蓝牙已连接」状态下不接受 CarPlay——保持仅配对")
                            }
                        }
                    }
                }
                if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                    log("BT-C T18: 接收端反向 iAP2 会话占用中，取消本次主动连入")
                    break
                }
                val socket = tryConnect(device)
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的无线 iAP2 服务（RFCOMM 建立）")
                    connected = socket
                    connectedKnownBuick = knownBuick
                    connectedForcedE5 = forcedE5
                    break
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        log("BT-C: 连接 $name 失败（车机未就绪或不在无线配对窗口），3 秒后重试…")
                    }
                }
            }
            if (connected == null) {
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() 与 connect() 成功竞争：本线程已过期，不建会话
                currentSocket.compareAndSet(connected, null)
                runCatching { connected.close() }
                return
            }
            // Diagnostics only: resolve a random vehicle label after a real RFCOMM connection.
            val vehicleId = runCatching { GatewayVehicleIdentity.activate(context, connected.remoteDevice?.address) }.getOrNull()
            try {
                runSession(connected, connectedKnownBuick, connectedForcedE5)
            } finally {
                GatewayVehicleIdentity.clearIfActive(vehicleId)
            }
            sleepQuietly(RETRY_DELAY_MILLIS)
        }
        log("BT-C: 客户端已停止")
    }

    /**
     * 候选设备：优先 SDP 缓存里带配件端 UUID 的已配对设备；
     * 缓存缺失时退化为全部已配对设备（connect 时的 SDP 查询会自行过滤）。
     * 指定了目标车机时**只返回该设备**（用户配对了多台车机时避免误连）。
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // 目标策略：地址命中即唯一候选——不再按 SDP 缓存过滤（缓存可能缺失，
            // 而用户显式指定就该尝试）；目标不在已配对列表时给一次提示并空转等待。
            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (hit.isEmpty()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                    lastTargetMissingLogAt = now
                    log(
                        "BT-C: 目标设备「${target.name}」(${target.address}) 不在已配对列表" +
                            "——请到系统蓝牙设置完成配对，或在「连接准备」改选目标",
                    )
                }
                return emptyList()
            }
            return hit
        }
        val withService =
            bonded.filter { device ->
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }
        // Known B233 first: otherwise another paired device with a visible
        // UUID can make the candidates list omit Buick entirely.
        val knownBuick = bonded.filter { device ->
            val name = runCatching { device.name }.getOrNull()
            BuickE5HandshakeTarget.shouldForce(
                BuickE5HandshakePrefs.enabled(context),
                targetProvider()?.address, device.address, name,
            ) || BuickB233Route.useB233Transport(
                name,
                ReceiverCompatibilityMemory.wasB233Observed(context, device.address),
            )
        }
        return if (withService.isNotEmpty() || knownBuick.isNotEmpty()) {
            (knownBuick + withService).distinctBy { it.address }
        } else {
            bonded
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (error: IOException) {
                return null
            }
        // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
        // stop() 只有能拿到 socket 引用才能 close() 打断它，否则线程会滞留。
        currentSocket.set(socket)
        return try {
            socket.connect()
            socket
        } catch (error: IOException) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            null
        }
    }

    private fun runSession(socket: BluetoothSocket, knownBuick: Boolean, forcedE5: Boolean) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car") { line -> log(line) }
        activeChannel = channel
        try {
            channel.runSenderSession(
                context, SESSION_TIMEOUT_MILLIS, log,
                { endpoint ->
                    // Only a completed B233 0x4301 exchange can teach the phone
                    // this receiver's protocol. Bluetooth names alone never do.
                    if (endpoint.isBuickB233 && !forcedE5) {
                        if (ReceiverCompatibilityMemory.rememberB233(context, socket.remoteDevice?.address)) {
                            log("BT-C: 已记录 B233 实车协议画像（仅本机；蓝牙改名后仍可识别）")
                        }
                    }
                    if (knownBuick || endpoint.isBuickB233) {
                        b233ReconnectPolicy.onStartSessionAccepted()
                    }
                    onEndpoint(endpoint)
                },
                onWifiCredentials,
                onEndpointUpdate,
                forceBuickE5 = forcedE5,
            )
        } catch (error: IOException) {
            log("BT-C: SESSION 失败 - ${error.message}")
            if (knownBuick &&
                B233ReconnectPolicy.isUnansweredStartSession(error.message) &&
                running
            ) {
                val backoff = b233ReconnectPolicy.onStartSessionTimeout(
                    android.os.SystemClock.uptimeMillis(),
                )
                log("BT-C T17: B233 0x4300 无回应，下一轮等待 ${backoff / 1000}s " +
                    "（连续超时阶梯退避，最长60s）")
                if (backoff == 30_000L) {
                    log("BT-C T18: B233 连续两次 0x4300 无 ACK；" +
                        "已允许一次车机主动发起的 iAP2 反向握手（仍保留原有主动连接后备）")
                }
            } else if (knownBuick &&
                B233ReconnectPolicy.isPreAvailabilityPeerSilent(error.message) &&
                running
            ) {
                val backoff = b233ReconnectPolicy.onPreAvailabilityPeerSilent(
                    android.os.SystemClock.uptimeMillis(),
                )
                log(
                    "BT-C T29: B233 早期 iAP2/0x4E0C 无应答，自动重连冷却 ${backoff / 1000}s；" +
                        "保留 HFP/A2DP，不删除配对，不误触发 0x4300 反向会话"
                )
            }
        } finally {
            if (activeChannel === channel) activeChannel = null
            // T17: an old session ending after stop/start MUST NOT close the
            // socket already owned by a newer generation of the BT client.
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            channel.close()
            log("BT-C: 本轮会话结束（客户端仍在运行，将自动重连）")
        }
    }

    /**
     * T18: called by the inbound iAP2 listener only for the verified B233
     * receiver after target-device filtering. One shot per client start.
     * Gate is atomic so two concurrent inbound accepts cannot both proceed.
     */
    fun claimB233InboundRecovery(): Boolean =
        b233ReconnectPolicy.claimInboundRecoveryAttempt()

    /**
     * T17 only called after an established B233 audio+video stream has
     * unexpectedly ended. Keep the client's service alive, but close its
     * retired RFCOMM session and defer new discovery while B233 resets.
     * Explicit stop/start clears this cooldown immediately.
     */
    fun recoverAfterB233AudioTeardown() {
        val cooldown = b233ReconnectPolicy.onAudioSessionEnded(
            android.os.SystemClock.uptimeMillis(),
        )
        log("BT-C T17: B233 音频异常拆链，退役旧 iAP2 控制连接；" +
            "自动重试冷却 ${cooldown / 1000}s（不重启 App、不删除配对）")
        detachCurrentSession()
    }

    /** 主动结束当前 BT 控制会话（不影响重连循环）。供 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT-C: 已主动断开当前控制会话")
        }
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        // 递增世代：即便有线程卡在 connect()，事后也会因世代不符自杀，不会在下次启动时复活
        generation.incrementAndGet()
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
        activeChannel?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread = null
        log("BT-C: 已停止")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * 入站会话门闸：车机主动连入的 iAP2 会话活跃期间，出站客户端暂停连接尝试。
 * 目的：车机通常只有单条无线 iAP2 上下文，双向同时建链会让双方互相干扰。
 */
object BtSessionGate {
    /** 入站（车机主动连入）的 iAP2 会话活跃：出站暂停，避免两条 RFCOMM 互相踩。 */
    @Volatile
    var inboundActive: Boolean = false

    /**
     * CarPlay 会话已建立（任一路径成功拿到端点后置位，由 SessionController 维护）：
     * 暂停一切出站连接尝试（静默降噪）——典型表现为连着 DiPlay 时不再探测 Audi。
     * 会话结束/手动断开时由 SessionController 清除。
     */
    @Volatile
    var sessionActive: Boolean = false
}
