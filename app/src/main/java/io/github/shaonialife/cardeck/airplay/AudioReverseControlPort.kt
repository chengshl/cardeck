package io.github.shaonialife.cardeck.airplay

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
) : AutoCloseable {
    @Volatile private var socket: DatagramSocket? = null
    private val received = AtomicInteger(0)

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
        log("AUDIO T73: 手机反向 RTCP UDP 已监听 localControlPort=${datagram.localPort}")
        Thread({
            val buffer = ByteArray(1_500)
            while (!datagram.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    datagram.receive(packet)
                    val n = received.incrementAndGet()
                    if (n <= 8 || n % 30 == 0) {
                        val pt = if (packet.length >= 2) buffer[1].toInt() and 0xff else -1
                        log("AUDIO T73: 收到车机反向音频控制包 #${n} size=${packet.length} pt=${pt}")
                    }
                } catch (_: SocketTimeoutException) {
                    // Loop until the owning CarPlay audio stream is closed.
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
        }, "b233-audio-rtcp-reverse").apply { isDaemon = true; start() }
        return datagram.localPort
    }

    @Synchronized
    override fun close() {
        val active = socket ?: return
        socket = null
        active.close()
        log("AUDIO T73: 手机反向 RTCP 监听已关闭，累计接收=${received.get()}")
    }
}
