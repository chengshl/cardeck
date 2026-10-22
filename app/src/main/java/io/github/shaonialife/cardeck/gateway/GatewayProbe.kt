package io.github.shaonialife.cardeck.gateway

import io.github.shaonialife.cardeck.config.EntryAddress
import io.github.shaonialife.cardeck.config.EntryConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 每次手动测试独占客户端与定时器；状态中不携带 URL、凭证或服务器原始消息。 */
class GatewayProbe(
    client: OkHttpClient,
    private val observer: (Snapshot) -> Unit,
    private val heartbeatIntervalMs: Long = 10_000,
    private val replyTimeoutMs: Long = 10_000,
) : AutoCloseable {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    enum class State { CONNECTING, CONNECTED, HEALTHY, AUTH_FAILED, DISCONNECTED, FAILED }
    enum class Failure { NETWORK, TLS, HANDSHAKE, PROTOCOL, HEARTBEAT, CONFIGURATION }

    data class Snapshot(
        val state: State,
        val failure: Failure? = null,
        val httpCode: Int? = null,
        val heartbeatCount: Int = 0,
        val latencyMs: Long = 0,
    )

    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gateway-probe-heartbeat").apply { isDaemon = true }
    }
    private var socket: WebSocket? = null
    private var deadline: ScheduledFuture<*>? = null
    private var nextHeartbeat: ScheduledFuture<*>? = null
    private var started = false
    private var stopped = false
    private var greeted = false
    private var pendingSince: Long? = null
    private var heartbeatCount = 0

    @Synchronized
    fun start(config: EntryConfig) {
        if (started || stopped) return
        started = true
        val request = runCatching {
            val address = EntryAddress.normalize(config.gatewayUrl)
            require(config.token.isNotBlank() && config.token.length <= 4096)
            require(config.token.none { it <= ' ' || it > '~' })
            Request.Builder().url(address).header("Authorization", "Bearer ${config.token}").build()
        }.getOrElse {
            finish(Snapshot(State.FAILED, Failure.CONFIGURATION))
            return
        }
        observer(Snapshot(State.CONNECTING))
        deadline = timer.schedule({ fail(Failure.NETWORK) }, replyTimeoutMs * 2, TimeUnit.MILLISECONDS)
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@GatewayProbe) {
                if (stopped) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
                deadline?.cancel(false)
                deadline = timer.schedule({ fail(Failure.PROTOCOL) }, replyTimeoutMs, TimeUnit.MILLISECONDS)
                observer(Snapshot(State.CONNECTED))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(this@GatewayProbe) {
                if (stopped) return
                if (text.length > 16_384) {
                    fail(Failure.PROTOCOL)
                    return
                }
                val message = runCatching { JSONObject(text) }.getOrNull()
                if (message == null) {
                    fail(Failure.PROTOCOL)
                    return
                }
                when (message.optString("type")) {
                    "hello" -> {
                        if (greeted || message.optString("mode") != "transport-test") {
                            fail(Failure.PROTOCOL)
                            return
                        }
                        greeted = true
                        deadline?.cancel(false)
                        sendHeartbeat()
                    }
                    "heartbeat_ack" -> {
                        if (!greeted) {
                            fail(Failure.PROTOCOL)
                            return
                        }
                        val sent = pendingSince ?: return
                        pendingSince = null
                        deadline?.cancel(false)
                        heartbeatCount++
                        observer(Snapshot(State.HEALTHY, heartbeatCount = heartbeatCount,
                            latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent)))
                        nextHeartbeat = timer.schedule({ sendHeartbeat() }, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
                    }
                    else -> fail(Failure.PROTOCOL)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = fail(Failure.PROTOCOL)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            finish(Snapshot(State.DISCONNECTED))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(Snapshot(State.DISCONNECTED))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            response?.body?.close()
            when {
                code == 401 || code == 403 -> finish(Snapshot(State.AUTH_FAILED, httpCode = code))
                code != null -> finish(Snapshot(State.FAILED, Failure.HANDSHAKE, httpCode = code))
                generateSequence(t) { it.cause }.any { it is SSLException } -> fail(Failure.TLS)
                else -> fail(Failure.NETWORK)
            }
        }
    }

    @Synchronized
    private fun sendHeartbeat() {
        if (stopped || !greeted || pendingSince != null) return
        pendingSince = System.nanoTime()
        if (socket?.send("{\"type\":\"heartbeat\"}") != true) {
            fail(Failure.NETWORK)
            return
        }
        deadline = timer.schedule({ fail(Failure.HEARTBEAT) }, replyTimeoutMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun networkLost() = fail(Failure.NETWORK)

    @Synchronized
    private fun fail(failure: Failure) = finish(Snapshot(State.FAILED, failure))

    @Synchronized
    private fun finish(snapshot: Snapshot) {
        if (stopped) return
        stopped = true
        deadline?.cancel(false)
        nextHeartbeat?.cancel(false)
        socket?.cancel()
        socket = null
        timer.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        observer(snapshot)
    }

    @Synchronized
    override fun close() = finish(Snapshot(State.DISCONNECTED))
}
