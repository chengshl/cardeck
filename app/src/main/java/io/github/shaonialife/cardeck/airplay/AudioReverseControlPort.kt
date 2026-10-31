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
    /** Synthetic receive failures in JVM tests; production always calls DatagramSocket.receive. */
    private val receivePacket: (DatagramSocket, DatagramPacket) -> Unit = { udp, packet -> udp.receive(packet) },
) : AutoCloseable {
    @Volatile private var socket: DatagramSocket? = null
    private val received = AtomicInteger(0)
    @Volatile private var openedAtNanos = 0L
    @Volatile private var lastReceiveNanos = 0L

    /** 仅解析头部；不存储音频控制报文负载、IP或设备标识。 */
    internal data class ControlHeader(val version: Int, val packetType: Int, val declaredBytes: Int?)

    companion object {
        /**
         * Diagnose only fixed errno categories. Neither arbitrary exception
         * messages nor network IDs, addresses, credentials, or peer data are
         * ever copied into logs. This does not infer why Android denied access.
         */
        internal fun bindFailureCategory(error: Throwable): String {
            val seen = java.util.IdentityHashMap<Throwable, Boolean>()
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth++ < 6 && seen.put(current, true) == null) {
                if (current is SecurityException) return "security_exception"
                val message = current.message.orEmpty()
                if (Regex("""(?<![A-Za-z0-9_])(EPERM|EACCES)(?![A-Za-z0-9_])""").containsMatchIn(message)) {
                    return "permission_denied"
                }
                if (Regex("""(?<![A-Za-z0-9_])(ENETDOWN|ENETUNREACH|EHOSTUNREACH)(?![A-Za-z0-9_])""").containsMatchIn(message)) {
                    return "network_unreachable"
                }
                if (Regex("""(?<![A-Za-z0-9_])(EBADF|ENOTSOCK)(?![A-Za-z0-9_])""").containsMatchIn(message)) {
                    return "socket_invalid"
                }
                current = current.cause
            }
            return "other"
        }

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
            // Explicitly report that a pre-SETUP reverse listener is unusable.
            // Expose only a fixed category, not SocketException.message:
            // Android errors can include Network IDs or endpoint information.
            val category = bindFailureCategory(error)
            log("AUDIO T73: 手机反向 RTCP 网络绑定失败：${error.javaClass.simpleName}")
            log("AUDIO T96: reverse_rtcp_bind_unavailable category=$category listener=false")
            failureTrace?.event("reverse_rtcp_bind_unavailable", "category=$category")
            return 0
        }
        socket = datagram
        val listenerStartNanos = System.nanoTime()
        openedAtNanos = listenerStartNanos
        lastReceiveNanos = 0L
        failureTrace?.event("reverse_rtcp_listening")
        log("AUDIO T73: 手机反向 RTCP UDP 已监听 localControlPort=${datagram.localPort}")
        Thread({
            val buffer = ByteArray(2_048)
            var nextReportSecond = 1L
            var exitReason = "socket_closed"
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
                        receivePacket(datagram, packet)
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
                            exitReason = "socket_exception"
                            log("AUDIO T73: 反向 RTCP 套接字异常：${error.javaClass.simpleName}")
                        }
                        break
                    } catch (error: Exception) {
                        if (!datagram.isClosed) {
                            exitReason = "receive_exception"
                            log("AUDIO T73: 反向 RTCP 接收异常：${error.javaClass.simpleName}")
                        }
                        break
                    }
                }
            } finally {
                // An unexpectedly dead receive thread must never advertise a stale UDP
                // port. Identity guard avoids retiring a replacement listener started
                // after the previous one was explicitly closed.
                datagram.close()
                val retired = synchronized(this@AudioReverseControlPort) {
                    if (socket === datagram) {
                        socket = null
                        true
                    } else {
                        false
                    }
                }
                if (retired) {
                    val elapsedMs = ((System.nanoTime() - listenerStartNanos) / 1_000_000L).coerceAtLeast(0L)
                    log("AUDIO T89: 反向RTCP监听退出 reason=$exitReason elapsedMs=$elapsedMs")
                    failureTrace?.event("reverse_rtcp_listener_exit", "reason=$exitReason elapsedMs=$elapsedMs")
                } else {
                    // Already closed/replaced by the owner; no mutation of newer socket.
                    log("AUDIO T89: 旧监听线程退出，保留当前Socket")
                }
            }
        }, "b233-audio-rtcp-reverse").apply { isDaemon = true; start() }
        // Returning the live field, rather than the already-created datagram's
        // localPort, fails closed if the worker terminated during start().
        return port
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
