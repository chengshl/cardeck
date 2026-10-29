package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.audio.AudioFailureTrace
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * T73: Advertise a PHONE-local reverse RTCP controlPort in CarPlay audio SETUP.
 * catplay's RtpReceiver::new_tx binds this before the outgoing SETUP.
 * This is not the car's dataPort=6000 / remote controlPort=6011.
 * Receive-only; never emit legacy 0xD4 on this path.
 */
internal class AudioReverseControlPort(
    private val bindNetwork: (DatagramSocket) -> Unit,
    private val log: (String) -> Unit,
    private val failureTrace: AudioFailureTrace? = null,
    /** T79: current audio sender's monotonic successful RTP count, if available. */
    private val currentSentRtpCount: () -> Long? = { null },
) : AutoCloseable {
    @Volatile private var socket: DatagramSocket? = null
    private val received = AtomicInteger(0)
    @Volatile private var openedAtNanos = 0L
    @Volatile private var lastReceiveNanos = 0L

    /** 仅解析头部；不存储音频控制报文负载、IP或设备标识。 */
    internal data class ControlHeader(val version: Int, val packetType: Int, val declaredBytes: Int?)

    companion object {
        internal fun inspectHeader(bytes: ByteArray, length: Int): ControlHeader? {
            if (length < 2 || length > bytes.size) return null
            val version = (bytes[0].toInt() and 0xFF) ushr 6
            val packetType = bytes[1].toInt() and 0xFF
            val declaredBytes =
                if (packetType == AudioRetransmitRequest.TYPE) null
                else if (length >= 4) {
                    val words = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                    (words + 1) * 4
                } else {
                    null
                }
            return ControlHeader(version, packetType, declaredBytes)
        }
    }

    val port: Int get() = socket?.takeUnless { it.isClosed }?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        if (port > 0) return port
        val datagram = try {
            DatagramSocket()
        } catch (error: Exception) {
            log("AUDIO T73: 手机反向 RTCP Socket 创建失败：${error.javaClass.simpleName}")
            return 0
        }
        try {
            datagram.soTimeout = 1_000
            bindNetwork(datagram)
        } catch (error: Exception) {
            datagram.close()
            log("AUDIO T73: 手机反向 RTCP 网络绑定失败：${error.javaClass.simpleName}")
            return 0
        }
        socket = datagram
        openedAtNanos = System.nanoTime()
        lastReceiveNanos = 0L
        failureTrace?.event("reverse_rtcp_listening")
        log("AUDIO T73: 手机反向 RTCP UDP 已监听 localControlPort=${datagram.localPort}")
        Thread({
            val buffer = ByteArray(2_048)
            var nextReportSecond = 1L
            fun reportWindow() {
                val now = System.nanoTime()
                val elapsedSecond = ((now - openedAtNanos) / 1_000_000_000L).coerceAtLeast(0L)
                if (elapsedSecond < nextReportSecond || elapsedSecond > 30L) return
                nextReportSecond = elapsedSecond + 1L
                val last = lastReceiveNanos
                val gap = if (last == 0L) "none" else ((now - last) / 1_000_000L).toString()
                log("AUDIO T75: 反向RTCP t=${elapsedSecond}s 接收累计=${received.get()} 距最后接收=${gap}ms")
            }
            try {
                while (!datagram.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    datagram.receive(packet)
                    lastReceiveNanos = System.nanoTime()
                    val n = received.incrementAndGet()
                    val header = inspectHeader(buffer, packet.length)
                    if (n <= 8 || n % 30 == 0) {
                        log("AUDIO T75: 反向RTCP包 #${n} size=${packet.length} " +
                            "version=${header?.version ?: -1} pt=${header?.packetType ?: -1} " +
                            (if (header?.packetType == AudioRetransmitRequest.TYPE)
                                 "requestLayout=D5_not_RTCP_length"
                             else "declaredBytes=${header?.declaredBytes ?: -1}"))
                    }
                    // T79: receive-only. requestSeq alone is never a deduplication key.
                    // No media bytes, sender address or keys are captured or sent.
                    if (header?.packetType == AudioRetransmitRequest.TYPE) {
                        when (val decoded = AudioRetransmitRequest.inspect(buffer, packet.length)) {
                            is AudioRetransmitRequest.Parse.Valid -> {
                                val f = decoded.fields
                                val sent = currentSentRtpCount()
                                val coverage = AudioRetransmitRequest.coverage(f, sent)
                                if (n <= 32 || n % 30 == 0) {
                                    log("AUDIO T79: 反向重传请求 #$n requestSeq=${f.requestSeq} " +
                                        "firstMissingSeq=${f.firstMissingSeq} count=${f.count} " +
                                        "lastMissingSeq=${f.lastMissingSeq} seqWrap=${f.wrapsRtpSequence} " +
                                        "sentCount=${sent ?: "unknown"} coverage=$coverage (未响应)")
                                    failureTrace?.event("reverse_audio_resend_request",
                                        "req=${f.requestSeq} first=${f.firstMissingSeq} count=${f.count} inSent=$coverage")
                                }
                            }
                            is AudioRetransmitRequest.Parse.Invalid -> {
                                if (n <= 32 || n % 30 == 0) {
                                    log("AUDIO T79: 反向0xD5报文 #$n 拒绝解析 reason=${decoded.reason} size=${packet.length}")
                                    failureTrace?.event("reverse_audio_resend_invalid", decoded.reason)
                                }
                            }
                        }
                    }
                    reportWindow()
                } catch (_: SocketTimeoutException) {
                    // 在30秒高风险窗口内，连0包也如实记录；没有包不等于协议错误。
                    reportWindow()
                } catch (error: SocketException) {
                    if (!datagram.isClosed) {
                        log("AUDIO T73: 反向 RTCP 套接字异常：${error.javaClass.simpleName}")
                    }
                    break
                } catch (error: Exception) {
                    if (!datagram.isClosed) {
                        log("AUDIO T73: 反向 RTCP 接收异常：${error.javaClass.simpleName}")
                    }
                    break
                }
                }
            } finally {
                // A receive-thread exception must not leave a bound but unread
                // UDP port advertised as live. Never clear a newer socket if
                // start() raced with this old thread's cleanup.
                datagram.close()
                synchronized(this@AudioReverseControlPort) {
                    if (socket === datagram) socket = null
                }
            }
        }, "b233-audio-rtcp-reverse").apply { isDaemon = true; start() }
        return datagram.localPort
    }

    @Synchronized
    override fun close() {
        val active = socket ?: return
        socket = null
        active.close()
        val now = System.nanoTime()
        val activeMs = if (openedAtNanos == 0L) 0L else ((now - openedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        val lastGap = if (lastReceiveNanos == 0L) "none" else ((now - lastReceiveNanos) / 1_000_000L).toString()
        failureTrace?.event("reverse_rtcp_closed", "packets=${received.get()}")
        log("AUDIO T75: 手机反向 RTCP 监听关闭 elapsedMs=$activeMs received=${received.get()} lastGapMs=$lastGap")
    }
}
