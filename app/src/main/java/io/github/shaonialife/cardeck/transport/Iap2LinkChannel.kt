package io.github.shaonialife.cardeck.transport

import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * T31 B233-only: repeated DETECT is insufficient to prove the peer reset.
 * A single marker echo is eligible only while the local link remains NORMAL,
 * the latest control packet is unacknowledged, and >=4 post-0x4300 markers
 * have arrived. Pure function to keep safety conditions tested.
 */
internal fun shouldProbeB233Detect(
    normal: Boolean,
    markerDelta: Long,
    latestSent: Int?,
    peerAck: Int?,
    used: Boolean,
): Boolean =
    normal && markerDelta >= 4L && latestSent != null &&
        latestSent != peerAck && !used

/**
 * 链路通道：一个读线程 + 一个节奏线程驱动 [Iap2LinkEngine]，向会话层暴露
 * 阻塞式的 sendControl/recvControl 与 awaitReady。
 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    private val log: (String) -> Unit = {},
) : Closeable {
    private val engine = Iap2LinkEngine()
    private val lock = Object()
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private val readyLatch = CountDownLatch(1)
    /** Track the last outbound control sequence and inbound cumulative peer ACK. */
    private var lastOutboundControlSeq: Int? = null
    private var lastPeerAck: Int? = null
    private var inboundMarkerCount = 0L
    private var b233MarkerProbeSent = false

    @Volatile
    private var terminated = false
    private var readySignalled = false

    /** 原始字节转储预算（每方向每连接），真车字节级对照用。 */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX
    /** B233 credential exchange: retain frame lengths, never emit raw bytes. */
    private var suppressRawPayloads = false

    /** 逐帧摘要日志预算（每方向每连接）。 */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0

    val isAlive: Boolean get() = !terminated

    fun suppressSensitiveRawFrames() = synchronized(lock) {
        suppressRawPayloads = true
    }

    init {
        engine.onPeerSynchronizationAdopted = { sync ->
            log(
                "LINK: 采纳对端会话表 maxOutgoing=${sync.maxOutgoing} maxLen=${sync.maxLength} " +
                    "sessions=" + sync.sessions.joinToString { "${it.id}/k${it.kind}/v${it.version}" },
            )
        }
        engine.onMarkerReceived = {
            inboundMarkerCount++
            if (inboundMarkerCount <= 4L || inboundMarkerCount % 20L == 0L) {
                log("LINK T30: BT DETECT marker #" + inboundMarkerCount +
                    " state=" + engine.state)
            }
            lock.notifyAll()
        }
        engine.onUnknownSessionData = { sessionId, size ->
            log("LINK: 丢弃未知会话 $sessionId 的数据帧（$size 字节）")
        }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            if ((control and 0x40) != 0) {
                lastPeerAck = ack
                lock.notifyAll()
            }
            // 真车调试关键观测：车机在识别阶段是否回帧、回什么（静默分歧全靠它定位）
            if (rxFrameLogs < FRAME_LOG_LIMIT) {
                rxFrameLogs++
                log(
                    "LINK rx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        engine.onFrameSent = { control, sequence, ack, sessionId, size ->
            if ((control and 0x40) != 0 && sessionId != 0 && size > 0) {
                lastOutboundControlSeq = sequence
            }
            if (txFrameLogs < FRAME_LOG_LIMIT) {
                txFrameLogs++
                log(
                    "LINK tx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        Thread({ runReader() }, "$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "$name-pacer").apply {
            isDaemon = true
            start()
        }
    }

    /** 等待链路协商完成（会话 10 可写）。 */
    fun awaitReady(timeoutMillis: Long): Boolean {
        if (!readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return !terminated
    }

    /** 对端协商的最大控制载荷（已扣除帧头与校验和），协商前为 null。 */
    fun peerMaxControlPayload(): Int? = synchronized(lock) { engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1) }

    /** 排入一个完整控制载荷；链路已死返回 false。窗口满时内部积压，由 ACK 驱动发出。 */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck)
            drainOutputLocked()
            return accepted
        }
    }

    /**
     * Buick B233 needs a real peer ACK of the most recently transmitted
     * iAP2 control frame before 0x4300. Unlike a hard-coded sequence (108),
     * this also works when optional HID/EA traffic shifts outbound numbering.
     * Call immediately after sending the target frame, with no other sender
     * using this channel concurrently.
     */
    fun awaitLastControlAck(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(lock) {
            while (!terminated) {
                val sent = lastOutboundControlSeq
                if (sent != null && lastPeerAck == sent) return true
                val millis = (deadline - System.nanoTime()) / 1_000_000L
                if (millis <= 0) return false
                try {
                    lock.wait(millis.coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    /**
     * T21 read-only B233 diagnostic. Snapshot is taken under the same lock
     * as onFrameReceived/onFrameSent; it never dumps identifiers or credentials.
     */
    fun receivedMarkerCount(): Long = synchronized(lock) { inboundMarkerCount }

    /**
     * B233 T31: echo a *single* DETECT only during a stalled 0x4300
     * exchange. Do not restart the existing link state, discard outstanding
     * seq110, or interrupt sessions which may still receive a late ACK.
     */
    fun probeB233StalledAvailability(markersAtSend: Long): Boolean = synchronized(lock) {
        if (terminated || !shouldProbeB233Detect(
                normal = engine.state == Iap2LinkEngine.State.NORMAL,
                markerDelta = inboundMarkerCount - markersAtSend,
                latestSent = lastOutboundControlSeq,
                peerAck = lastPeerAck,
                used = b233MarkerProbeSent,
            )
        ) return@synchronized false
        b233MarkerProbeSent = true
        return@synchronized try {
            stream.write(Iap2LinkEngine.MARKER)
            log("LINK T31: BT B233 DETECT echoed once while 0x4300 unacked; " +
                "markersSince4300=" + (inboundMarkerCount - markersAtSend) +
                " sentSeq=" + lastOutboundControlSeq + " peerAck=" + lastPeerAck)
            true
        } catch (error: IOException) {
            finishLocked(error)
            false
        }
    }

    fun controlAckSnapshot(): String = synchronized(lock) {
        "sentSeq=${lastOutboundControlSeq ?: "none"} " +
            "peerAck=${lastPeerAck ?: "none"} alive=${!terminated}"
    }

    /** 取下一个控制载荷；超时或链路结束返回 null。 */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        if (timeoutMillis == 0L && terminated && controls.isEmpty()) return null
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        return if (polled === null || polled === POISON) null else polled
    }

    override fun close() {
        finish(null)
    }

    // ---- 内部 ----

    private fun runReader() {
        try {
            synchronized(lock) {
                engine.start(nowMillis())
                drainOutputLocked()
            }
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (terminated) return
                    dumpInbound(buffer.copyOf(count))
                    engine.feed(buffer.copyOf(count), nowMillis())
                    processEventsLocked()
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            finish(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * 原始字节抓包（真车字节级对照的唯一手段）：每方向仅转储前
     * [RAW_DUMP_LIMIT] 字节，防止长期会话日志爆炸。
     */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        if (suppressRawPayloads) {
            log("RAW rx: redacted bytes=$shown")
        } else {
            log("RAW rx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        }
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        while (!terminated) {
            val deadline: Long?
            synchronized(lock) {
                if (terminated) return
                engine.advanceTime(nowMillis())
                processEventsLocked()
                drainOutputLocked()
                signalReadyLocked()
                deadline = engine.nextDeadlineMillis()
                if (deadline == null) {
                    try {
                        lock.wait(PACER_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
            if (deadline != null) {
                val sleep = (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** 持锁调用：抽取引擎事件并搬运输出。 */
    private fun processEventsLocked() {
        while (true) {
            when (val event = engine.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Control -> {
                    if (!controls.offer(event.bytes)) {
                        engine.feedEof()
                        return
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(event.reason?.let { IOException(it) })
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked() {
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return
        dumpOutbound(bytes)
        try {
            stream.write(bytes)
        } catch (error: IOException) {
            finishLocked(error)
        }
        signalReadyLocked()
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        if (suppressRawPayloads) {
            log("RAW tx: redacted bytes=$shown")
        } else {
            log("RAW tx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        }
        txDumpBudget -= shown
    }

    private fun signalReadyLocked() {
        if (!readySignalled && engine.state == Iap2LinkEngine.State.NORMAL) {
            readySignalled = true
            readyLatch.countDown()
        }
    }

    private fun finish(reason: String?) {
        synchronized(lock) { finishLocked(reason?.let { IOException(it) }) }
    }

    private fun finishLocked(failure: IOException?) {
        if (terminated) return
        terminated = true
        controls.offer(POISON) // 哨兵：唤醒阻塞的接收方（LBQ 不接受 null）
        readyLatch.countDown()
        lock.notifyAll()
        runCatching { stream.close() }
        if (failure != null) {
            log("LINK closed: ${failure.message}")
        } else {
            log("LINK closed")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val PACER_POLL_MILLIS = 20L

        /** 原始字节转储预算：rx 需覆盖车机全部协商帧（Audi 实测约 140B），给 512B 余量。 */
        // 预算按字节计。曾为 512/256：一次完整 bring-up 都盖不住（0x1D01 单帧即 565B），
        // 提取重放 fixture（tools/extract_car_fixtures.py）会缺尾部——提到 4096 覆盖
        // 整个识别/MFi/凭据交换，长会话靠预算封顶防日志爆炸
        const val RAW_DUMP_LIMIT = 4096
        const val RAW_DUMP_LIMIT_TX = 4096

        /** 逐帧摘要日志上限（每方向每连接）。 */
        const val FRAME_LOG_LIMIT = 40

        /** 链路终结哨兵；真实控制载荷至少包含一段 CSM 帧，不会为空。 */
        val POISON = ByteArray(0)
    }
}
