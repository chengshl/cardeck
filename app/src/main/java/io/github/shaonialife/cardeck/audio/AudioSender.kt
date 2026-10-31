package io.github.shaonialife.cardeck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.TimingServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio RTP UDP socket ownership: DNS resolution and connect can fail before
 * AudioSender.socket is assigned. Close the just-created socket on either
 * failure so repeated reconnect attempts cannot leak native descriptors.
 *
 * Does not alter the destination, network binding, or successful RTP path.
 */
internal fun connectOwnedAudioDatagramSocket(
    client: DatagramSocket,
    host: String,
    port: Int,
): DatagramSocket {
    try {
        client.connect(InetAddress.getByName(host), port)
        return client
    } catch (error: Exception) {
        client.close()
        throw error
    }
}

/**
 * B233-only: stop can race a blocking UDP connect or AudioRecord construction.
 * Serialize ownership publication with retirement so a late startup cannot
 * leave an RTP/RTCP socket open or resurrect capture after stop().
 */
internal class AudioDatagramSessionGate {
    private val lock = Any()
    private var retired = false

    fun publish(socket: DatagramSocket, isActive: () -> Boolean, install: () -> Unit): Boolean =
        synchronized(lock) {
            if (retired || !isActive()) {
                socket.close()
                false
            } else {
                install()
                true
            }
        }

    fun runIfActive(isActive: () -> Boolean, action: () -> Unit): Boolean =
        synchronized(lock) {
            if (retired || !isActive()) false else {
                action()
                true
            }
        }

    fun retire(closeOwned: () -> Unit) {
        synchronized(lock) {
            retired = true
            closeOwned()
        }
    }
}

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（对照 DiPlay `AudioStream.kt` 解密路径与 catplay `rtp_cipher_chacha.rs`）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（timestamp+ssrc 的 BE 表示，
 *    catplay 的 "modern AAD"；DiPlay 解密即取同一范围）
 *  - nonce（12B）= 4 字节零 + 尾 8 字节（小端计数，从 0 逐包递增）→ [AirPlayCrypto.nonce64]
 *  - RTP 字段：version=2、PT=流类型（100=PCM 通路 / 102=AAC-LC 通路；
 *    对照 catplay `payload_type = stream_type`）、
 *    SSRC=0（catplay 同款）、timestamp = 累计样本帧数、seq 逐包递增
 *  - **首个音频包置 Marker 位**（规范：RECORD/FLUSH 之后的首包要打标记，接收端据此
 *    切分播放边界；catplay 恒 false，但我们的会话是「RECORD 之后才加入音频流」，
 *    首包标记能帮接收端重新对齐）
 *
 * 旁路：
 *  - **同步包**：[AudioSyncPacket] 每 1 秒发往该流的 `controlPort`，建立 RTP↔NTP 锚点。
 *    （缺了它接收端只能「按到达时刻猜」，严格的实现干脆不排播——2026-10-20 实测）
 *
 * 采集：`AudioPlaybackCapture`（Android 10+）捕获手机端其他应用播放的媒体/导航音频
 * （需要 MediaProjection——与镜像模式的同一授权）。每包 [SAMPLES_PER_PACKET] 样本帧
 * （48000Hz 立体声 16bit = 2048B 负载，总包 ~2084B，不超接收端 4096B 收包缓冲）。
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 静音手机媒体音量（声音只从车机出）。null = 不做（测试用）。 */
    private val context: android.content.Context? = null,
    /**
     * 采集采样率（Hz）：必须与 SETUP 协商出的格式一致（48k 主选，车机只支持
     * 44.1k 时由 AirPlayClient 协商下发）。默认 48k。
     */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /**
     * 该音频流的控制端口（车机在 SETUP 应答里下发）。>0 时每 [SYNC_INTERVAL_MILLIS]
     * 发一个 [AudioSyncPacket] 建立 RTP↔NTP 锚点；0 = 车机没给（旧车机），只发音频。
     */
    private val controlPort: Int = 0,
    /** T71 B233 type=102: remote controlPort is reported but no classic 0xD4 is sent. */
    private val sendLegacyAudioSyncPackets: Boolean = true,
    /** B233 only: do not send RTCP before the late-joined audio RECORD succeeds. */
    private val deferSyncUntilRecord: Boolean = false,
    /** B233 only: detect rejected audio control endpoint and leave the video session alive. */
    private val onSyncPortRefused: ((String) -> Unit)? = null,
    /** B233 T12 diagnosis: keep phone media volume unchanged while verifying playback capture. */
    private val mutePhoneOutput: Boolean = true,
    /** T13 B233: 1 Hz RTP/capture diagnostics for first 45s; other cars unchanged. */
    private val verboseStartupDiagnostics: Boolean = false,
    /** T24: B233 audio RTP and RTCP must not depend on VPN/default routes. */
    private val vehicleNetwork: android.net.Network? = null,
    /** T73: release the phone-local reverse RTCP listener when this audio sender stops. */
    private val onStopped: (() -> Unit)? = null,
    /** T76: observation-only per-AirPlay-session monotonic timeline. */
    private val failureTrace: AudioFailureTrace? = null,
    /** T77: user-selected B233-only 35s test signal, default false. Never enabled silently. */
    private val diagnosticToneEnabled: Boolean = false,
    /** B233 only: preserve partial AudioRecord reads instead of dropping samples. */
    private val completePartialPcmReads: Boolean = false,
) {
    companion object {
        const val CHANNELS = 2

        /**
         * 每包样本帧数 = AirPlay 标准 SPF 352（catplay `MAX_FRAMES_PER_PACKET_PCM`，
         * 真车验证）。48000/352 ≈ 136 包/秒（7.33ms/包）。
         * 2026-10-17 实测：此前用 512 帧（10.67ms/包），奥迪接收端无声音——
         * 与真 iPhone 的包长不一致时接收端可能按固定 SPF 缓冲而丢弃。
         */
        const val SAMPLES_PER_PACKET = 352

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        /**
         * T14 B233 trial: this receiver advertises AAC RTCP controlPort=6011
         * yet returns ICMP Port Unreachable on the second sync send. Stopping
         * the healthy RTP stream after that error leaves a still-advertised
         * audio session with no packets; the car tears down CarPlay ~19s later.
         * Sync sender exits on IOException regardless; only AAC/B233 keeps RTP.
         * Do NOT apply to PCM or other vehicles without real-car evidence.
         */
        internal fun keepRtpAfterSyncPortRefused(
            isBuickB233: Boolean,
            codec: AudioCodec,
        ): Boolean = isBuickB233 && codec == AudioCodec.AAC_LC

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** 静音前媒体音量的持久化键（进程被杀也不丢恢复目标）。 */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** PCM capture sample peak, independent of AAC codecs (Android AudioRecord is little endian). */
        internal fun peakPcm16Le(samples: ByteArray): Int {
            var peak = 0
            var offset = 0
            while (offset + 1 < samples.size) {
                val word = (samples[offset].toInt() and 0xFF) or
                    ((samples[offset + 1].toInt() and 0xFF) shl 8)
                val signed = if (word >= 0x8000) word - 0x10000 else word
                val magnitude = if (signed == Short.MIN_VALUE.toInt()) 32768 else kotlin.math.abs(signed)
                if (magnitude > peak) peak = magnitude
                offset += 2
            }
            return peak
        }

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private val b233DatagramGate = AudioDatagramSessionGate()
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /**
     * RTP 游标（原本是采集循环里的局部变量）。提到字段上是因为**同步包线程**要读
     * 「当前/下一包时间戳」，否则锚点里的时间戳永远对不上真实发出的包。
     */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L
    @Volatile private var syncRecordAccepted = !deferSyncUntilRecord

    fun activateSyncAfterRecord() {
        if (!deferSyncUntilRecord) return
        syncRecordAccepted = true
        log("AUDIO T10: B233 RECORD confirmed; RTCP will start after first RTP samples")
    }

    @Volatile private var nonceCounter = 0L

    /** 首个音频包标记（打完就清）；AAC 自检只跑一次。 */
    private var markerPending = true
    private var selfChecked = false
    private val aacSelfCheckWindow = AacSelfCheckWindow()

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /**
     * RTP SSRC：固定 0（catplay `prepare_header` 在真车上即用 0；DiPlay 收端不校验）。
     * 2026-10-17 实测：此前用随机值，奥迪接收端无声音——部分车机按 SSRC 关联 SETUP
     * 声明的流（非 0 视为未知流丢弃）。
     */
    private val ssrc = 0

    /** 采集+推流统计（日志限流用）。 */
    private var packetsSent = 0L
    /** T79: published only after a successful send(), for receive-only 0xD5 diagnostics. */
    @Volatile private var diagnosticSentPacketCount = 0L
    internal fun sentPacketCountSnapshot(): Long = diagnosticSentPacketCount
    private var bytesSent = 0L
    private var lastLogAt = 0L
    private var diagnosticStartedAt = 0L
    private var lastCaptureDiagnosticAt = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** 静音前的手机媒体音量 index（-1 = 未静音）。 */
    private var mutedOriginalVolume = -1

    /**
     * 静音手机媒体输出：音频通道激活后声音只从车机出（真 CarPlay 行为）。
     * 依据（实测 2026-10-10）：播放捕获取自 REMOTE_SUBMIX（音量应用点之前），
     * 手机流音量静音**不影响捕获数据**——手机不响、车机照常出声。
     *
     * 恢复值持久化（SharedPreferences）：进程被系统杀死/安装更新时来不及恢复，
     * 下次静音若读到 0 会把它当"原音量"，导致断开后永久静音（2026-10-10 踩过）。
     */
    private fun mutePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            if (mutedOriginalVolume >= 0) return
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            // 目标恢复值：优先上次未恢复成功的保存值；否则当前值（>0 才可信）
            val restoreTarget =
                when {
                    saved > 0 -> saved
                    current > 0 -> current
                    else -> -1
                }
            if (restoreTarget > 0) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, restoreTarget).apply()
            }
            mutedOriginalVolume = restoreTarget
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            // 回读校验：ROM 的音频策略可能拒绝/立刻回弹（实测见过 volume 仍为原值），
            // 此时手机继续外放——必须让日志能直接看出来
            val after = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            log(
                "AUDIO: 已静音手机媒体输出（原音量 ${if (restoreTarget > 0) restoreTarget else "未知"}，" +
                    "断开后恢复）——回读音量=$after" +
                    if (after != 0) "（⚠ 静音未生效，手机仍会外放）" else "",
            )
        } catch (error: Exception) {
            log("AUDIO: 静音手机媒体失败 - ${error.message}")
        }
    }

    private fun restorePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val target =
                when {
                    mutedOriginalVolume > 0 -> mutedOriginalVolume
                    else -> prefs.getInt(KEY_SAVED_VOLUME, -1)
                }
            if (target > 0) {
                manager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    target,
                    0,
                )
                log("AUDIO: 已恢复手机媒体音量（$target）")
            } else {
                log("AUDIO: 无有效原音量记录，手机媒体保持当前值（请按音量键调整）")
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        } catch (_: Exception) {
        }
        mutedOriginalVolume = -1
    }

    /**
     * 启动音频发送。需要 MediaProjection（AudioPlaybackCapture 的授权来源）。
     * 返回是否成功进入采集循环。
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        stopped.set(false)
        failureTrace?.event("audio_sender_start")
        return try {
            val client = DatagramSocket()
            vehicleNetwork?.let { network ->
                runCatching { network.bindSocket(client) }
                    .onSuccess { log("AUDIO T24: RTP UDP 指定车机 WiFi Network=$network") }
                    .onFailure { log("AUDIO T24: RTP WiFi 路由绑定失败 - ${it.message}") }
            }
            val connected = connectOwnedAudioDatagramSocket(client, host, dataPort)
            if (verboseStartupDiagnostics) {
                if (!b233DatagramGate.publish(connected, { !stopped.get() }) { socket = connected }) {
                    return false // stop() retired this B233 sender during UDP connect
                }
            } else {
                socket = connected
            }

            // AAC 通路先把编码器立起来：编不出来就没必要采集（也避免「有包无声」的假象）
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            }

            val captureConfig = buildCaptureConfig(projection)
            val audioFormat =
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val recorder =
                AudioRecord
                    .Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(framePcmBytes * 8)
                    .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AUDIO: AudioRecord 初始化失败（state=${recorder.state}）")
                runCatching { recorder.release() }
                aacEncoder?.stop()
                aacEncoder = null
                client.close()
                socket = null
                return false
            }
            if (verboseStartupDiagnostics) {
                if (!b233DatagramGate.runIfActive({ !stopped.get() }) {
                    record = recorder
                    running.set(true)
                }) {
                    runCatching { recorder.release() }
                    return false // stop() won while AudioRecord was being constructed
                }
            } else {
                record = recorder
                running.set(true)
            }
            if (verboseStartupDiagnostics) {
                diagnosticStartedAt = android.os.SystemClock.uptimeMillis()
                lastLogAt = diagnosticStartedAt - 1_000L
                lastCaptureDiagnosticAt = diagnosticStartedAt - 1_000L
                log("AUDIO T13: B233 前45秒开启每秒 RTP/采集诊断（静音数据也会持续发送）")
            }
            if (mutePhoneOutput) {
                mutePhoneMedia()
            } else {
                // T12 isolates B233's all-zero capture from Sony's STREAM_MUSIC mute.
                // This is diagnostic: phone speaker may be audible as well as the car.
                val am = context?.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as? android.media.AudioManager
                log("AUDIO T12: B233 原媒体音量保留；volume=${am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}" +
                    " musicActive=${am?.isMusicActive}（手机可能同时外放）")
            }
            log(
                "AUDIO: 已启动（播放捕获 ${sampleRate}Hz 立体声 → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，" +
                    if (codec == AudioCodec.AAC_LC) {
                        "每包 1 个 AAC 帧（${AacLcEncoder.FRAME_SAMPLES} 采样" +
                            (if (adtsFrames) "，ADTS 封装））" else "，裸帧））")
                    } else {
                        "每包 ${SAMPLES_PER_PACKET} 帧 PCM）"
                    },
            )

            // 同步包线程先起：锚点越早建立，接收端越早能排播
            startSyncLoop()

            Thread(
                {
                    try {
                        recorder.startRecording()
                    } catch (error: Exception) {
                        log("AUDIO: 启动采集失败 - ${error.message}")
                        stop()
                        return@Thread
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            true
        } catch (error: Exception) {
            log("AUDIO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 停止音频。
     *
     * 收尾路径不止一条（采集线程 finally、会话结束回调、手动断开），会重复调用；
     * 必须幂等——否则「已恢复手机媒体音量」会打两遍，且在用户于会话期间手动改过
     * 音量时，第二次恢复会用旧值把它覆盖回去。
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        failureTrace?.event("audio_sender_stop_requested", "caller=${Thread.currentThread().stackTrace.getOrNull(3)?.methodName ?: "unknown"}")
        running.set(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        aacEncoder?.stop()
        aacEncoder = null
        if (verboseStartupDiagnostics) {
            b233DatagramGate.retire {
                runCatching { socket?.close() }
                socket = null
                runCatching { syncSocket?.close() }
                syncSocket = null
            }
        } else {
            runCatching { socket?.close() }
            socket = null
            runCatching { syncSocket?.close() }
            syncSocket = null
        }
        syncThread = null
        thread = null
        if (mutePhoneOutput) restorePhoneMedia()
        runCatching { onStopped?.invoke() }
            .onFailure { log("AUDIO T73: 反向音频控制端口释放失败：${it.javaClass.simpleName}") }
        log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
    }

    /** 捕获配置：媒体/游戏/导航等常见播放用途（逐项添加，个别用途被系统拒绝时跳过）。 */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    private fun startSyncLoop() {
        if (!sendLegacyAudioSyncPackets) {
            log(
                "AUDIO T71: B233 102/media 已协商 controlPort=$controlPort，" +
                    "本轮不主动发送传统 0xD4；避免混淆为车机未下发端口",
            )
            return
        }
        if (controlPort <= 0) {
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            return
        }
        val client =
            try {
                DatagramSocket().apply {
                    vehicleNetwork?.let { network ->
                        runCatching { network.bindSocket(this) }
                            .onSuccess { log("AUDIO T24: RTCP UDP 指定车机 WiFi Network=$network") }
                            .onFailure { log("AUDIO T24: RTCP WiFi 路由绑定失败 - ${it.message}") }
                    }
                    connectOwnedAudioDatagramSocket(this, host, controlPort)
                }
            } catch (error: Exception) {
                log("AUDIO: 同步包套接字创建失败 - ${error.message}")
                return
            }
        if (verboseStartupDiagnostics) {
            if (!b233DatagramGate.publish(client, { !stopped.get() }) { syncSocket = client }) {
                return // stop() retired this B233 sender during RTCP connect
            }
        } else {
            syncSocket = client
        }
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        val worker =
            Thread(
                {
                    var syncSequence = 0
                    var first = true
                    try {
                        while (running.get() && !client.isClosed) {
                            // B233 often opens its control UDP port only after the
                            // audio stream is activated via the *second* RECORD.
                            // Do not send the old zero->zero RTCP probe before RECORD.
                            if (deferSyncUntilRecord && (!syncRecordAccepted || timestamp == 0L)) {
                                Thread.sleep(25L)
                                continue
                            }
                            val next = timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                            client.send(DatagramPacket(packet, packet.size))
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                first = false
                                log(
                                    "AUDIO: 首个同步包已发出（X 位=1）ts=$current→$next——" +
                                        "车机若仍无声，说明它没在等这个锚点",
                                )
                            }
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        }
                    } catch (error: Exception) {
                        if (running.get()) {
                            log("AUDIO: 同步包发送停止 - ${error.message}")
                            val message = error.message.orEmpty()
                            if (deferSyncUntilRecord &&
                                (error is java.net.PortUnreachableException ||
                                 message.contains("ECONNREFUSED", ignoreCase = true))
                            ) {
                                onSyncPortRefused?.invoke(message)
                            }
                        }
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    // ---- 采集循环 → 封包发送 ----

    private fun runCaptureLoop() {
        val recorder = record ?: return
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        val readWindow = if (completePartialPcmReads) PcmCaptureFrameWindow(frameBytes) else null
        // T77: uses AudioRecord only as a pacing clock, never changes RTP codec/SETUP/RTCP.
        // Injection happens only after the user expressly chooses the diagnostic-signal option.
        val diagnosticTone = if (diagnosticToneEnabled) DiagnosticPcmTone(sampleRate) else null
        val toneStartedAtMs = android.os.SystemClock.uptimeMillis()
        var toneStopLogged = false
        if (diagnosticTone != null) {
            log("AUDIO T77: 用户明确选择 440Hz 低幅度诊断音；仅首35秒替换本地捕获的 PCM，音量峰值=1000/32768，不代表手机外部音乐被捕获")
            failureTrace?.event("diagnostic_tone_started", "hz=440 peak=1000 durationMs=35000")
        }
        // 诊断抽样节拍：PCM 约 125 包/s（每 20s 一次）；AAC 约 47 帧/s
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600 else 1875
        try {
            while (running.get()) {
                val bytesRead =
                    try {
                        recorder.read(
                            pcm,
                            readWindow?.offset ?: 0,
                            readWindow?.remaining ?: pcm.size,
                            AudioRecord.READ_BLOCKING,
                        )
                    } catch (error: Exception) {
                        log("AUDIO: 采集读取异常 - ${error.message}")
                        break
                    }
                if (bytesRead < 0) {
                    // A partial frame from the previous successful read is
                    // invalid after an AudioRecord error, never splice it into
                    // the next RTP frame.
                    readWindow?.discard()
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("AUDIO: 采集错误码 $bytesRead（连续 $readFailures 次）")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                val read =
                    if (readWindow == null) {
                        // All other receivers keep the exact old capture logic.
                        if (bytesRead < frameBytes) continue
                        bytesRead
                    } else {
                        // Wait for one complete s16 frame before encoding,
                        // timestamp advancement, nonce consumption or RTP send.
                        // READ_BLOCKING normally fills the frame but the Android
                        // API permits short reads when recording is interrupted.
                        if (bytesRead == 0) {
                            readWindow.accept(0) // aggregate count only; preserve partial PCM
                            Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                            continue
                        }
                        if (!readWindow.accept(bytesRead)) continue
                        frameBytes
                    }
                readFailures = 0
                if (diagnosticTone != null) {
                    if (android.os.SystemClock.uptimeMillis() - toneStartedAtMs < 35_000L) {
                        diagnosticTone.fill(pcm, read)
                    } else if (!toneStopLogged) {
                        toneStopLogged = true
                        log("AUDIO T77: 35秒低幅度测试音窗口结束，恢复原 AudioPlaybackCapture 音源")
                        failureTrace?.event("diagnostic_tone_finished")
                    }
                }

                // 内容诊断（2026-10-17）：播放捕获可能拿到全零（app 禁止捕获 / 音频路由
                // 到蓝牙 / 无音频播放）。全零时车机静音但手机仍在响，用户会报「声音在
                // 手机上」——必须能区分「没采到」与「采到了但车机没播」。
                val diagNow = android.os.SystemClock.uptimeMillis()
                val earlyB233Diagnostic = verboseStartupDiagnostics &&
                    diagnosticStartedAt > 0L &&
                    diagNow - diagnosticStartedAt <= 45_000L &&
                    diagNow - lastCaptureDiagnosticAt >= 1_000L
                if (sequence < 3 || sequence % diagnosticStride == 0 || earlyB233Diagnostic) {
                    if (verboseStartupDiagnostics) lastCaptureDiagnosticAt = diagNow
                    var nonZero = 0
                    var peak = 0
                    var index = 0
                    while (index + 1 < read) {
                        val value = ((pcm[index + 1].toInt() and 0xFF) shl 8) or (pcm[index].toInt() and 0xFF)
                        val magnitude = if (value > 32767) 65536 - value else value
                        if (magnitude > peak) peak = magnitude
                        if (magnitude > 0) nonZero++
                        index += 2
                    }
                    val percent = nonZero * 100 / (read / 2).coerceAtLeast(1)
                    val activity =
                        if (!mutePhoneOutput && peak == 0) {
                            val am = context?.getSystemService(android.content.Context.AUDIO_SERVICE)
                                as? android.media.AudioManager
                            " musicActive=${am?.isMusicActive}" +
                                " mediaVolume=${am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}"
                        } else ""
                    log(
                        "AUDIO: 采集样本 seq=$sequence 非零$percent% 峰值=$peak$activity" +
                            (if (diagnosticTone != null && !toneStopLogged) " source=T77_opt_in_tone" else "") +
                            (if (peak == 0) "（⚠ 全零：音乐可能未播放、来源禁止捕获或走了其他音频路由）" else ""),
                    )
                }

                // PCM 通路：采一段 = 发一包；AAC 通路：喂一帧 PCM，编码器可能吐出 0~N 帧
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        encoder.encode(pcm, frameBytes)
                    } else {
                        listOf(pcm)
                    }
                // T76: wait for actual nonzero PCM and then accumulate 12 contiguous
                // encoded frames (max 64KiB/1s). The live RTP path is unchanged.
                if (!selfChecked && codec == AudioCodec.AAC_LC) {
                    val wasArmed = aacSelfCheckWindow.armed
                    val ready = aacSelfCheckWindow.accept(
                        nonzeroPcm = peakPcm16Le(pcm) > 0,
                        encoded = payloads,
                        nowNanos = System.nanoTime(),
                    )
                    if (!wasArmed && aacSelfCheckWindow.armed) {
                        failureTrace?.event("nonzero_pcm_first_seen")
                    }
                    if (ready != null) {
                        selfChecked = true
                        verifyEncodedFrames(ready, aacEncoder?.codecSpecificData())
                    }
                } else if (!selfChecked && codec == AudioCodec.PCM && payloads.isNotEmpty()) {
                    selfChecked = true
                    log("AUDIO T12: PCM 首帧自检 ${payloads.first().size}B peak=${peakPcm16Le(payloads.first())}")
                }
                if (payloads.isEmpty()) continue

                var sendFailed = false
                for (payload in payloads) {
                    if (!sendPayload(payload)) {
                        sendFailed = true
                        break
                    }
                }
                if (sendFailed) break
                val now = android.os.SystemClock.uptimeMillis()
                val inTrialWindow = verboseStartupDiagnostics &&
                    diagnosticStartedAt > 0L &&
                    now - diagnosticStartedAt <= 45_000L
                val logInterval = if (inTrialWindow) 1_000L else 30_000L
                if (now - lastLogAt >= logInterval) {
                    val elapsedMs = if (diagnosticStartedAt > 0L) now - diagnosticStartedAt else -1L
                    val label = if (inTrialWindow) "AUDIO T13: B233 RTP" else "AUDIO: 推流中"
                    val recordState = if (inTrialWindow) " RECORD确认=$syncRecordAccepted" else ""
                    // T94: reuse the existing throttled B233 RTP diagnostic.
                    // No new timer, no payload/audio content or identifiers.
                    val captureState = readWindow?.let { " " + it.diagnosticSummary() } ?: ""
                    lastLogAt = now
                    log(
                        "$label（elapsed=${elapsedMs}ms，codec=${codec.name} type=$streamType，" +
                            "累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                            "seq=$sequence ts=$timestamp$recordState$captureState）",
                    )
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            if (readWindow != null) {
                log("AUDIO T94: B233 PCM capture shutdown " + readWindow.diagnosticSummary())
            }
            failureTrace?.event("audio_capture_loop_exit")
            stop()
        }
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            diagnosticSentPacketCount = packetsSent
            bytesSent += wire.size
            if (packetsSent == 1L) failureTrace?.firstRtpSent()
            true
        } catch (error: IOException) {
            failureTrace?.event("audio_rtp_udp_error", error.javaClass.simpleName)
            log("AUDIO: UDP 发送失败 - ${error.message}")
            false
        }
    }

    /**
     * T76: one bounded asynchronous decoder run over consecutive AAC frames.
     * Success proves only that this plaintext segment decodes locally; it does
     * not prove receiver decryption, timing, routing or audible playback.
     */
    private fun verifyEncodedFrames(frames: List<ByteArray>, csd0: ByteArray?) {
        failureTrace?.event("aac_selfcheck_started", "frames=${frames.size} csdBytes=${csd0?.size ?: 0}")
        Thread(
            {
                val result = AacLcEncoder.decodeWindow(frames, sampleRate, CHANNELS, csd0)
                log(
                    "AUDIO T76: 自检 frames=${frames.size} queued=${result.queuedFrames} " +
                        "decodedSamples=${result.decodedSamples} peak=${result.peak} " +
                        "csdPresent=${csd0 != null && csd0.isNotEmpty()} error=${result.errorKind ?: "none"} " +
                        "（仅证明本机明文AAC解码，不代表车机解密/播放）",
                )
                failureTrace?.event(
                    "aac_selfcheck_done",
                    "samples=${result.decodedSamples} peak=${result.peak} error=${result.errorKind ?: "none"}",
                )
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 组一个 RTP 包：明文 PCM → ChaCha20 密封 → [头][密文+tag][nonce8]。（internal：测试直测） */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD = header 的 timestamp+ssrc 段（字节 4..12）——catplay "modern AAD" / DiPlay 解密同款
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // 关键：CarPlay 线格式的 LPCM 是**大端** s16 样本——接收端对收到的负载做
        // byteSwapS16 转回小端再喂 AudioTrack（DiPlay AndroidMediaSink.kt:1687）。
        // Android 采集是原生小端，因此这里必须换序；漏掉会得到「沙沙声」（2026-10-10 实测）。
        //
        // AAC 通路不做换序：AAC 比特流是字节流，不是 16bit 样本序列，换序会直接把
        // 比特流搅烂（车机解码失败 → 有包无声）。
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
