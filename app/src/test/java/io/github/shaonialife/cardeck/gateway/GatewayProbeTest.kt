package io.github.shaonialife.cardeck.gateway

import io.github.shaonialife.cardeck.config.EntryConfig
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GatewayProbeTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var probe: GatewayProbe? = null
    private val events = LinkedBlockingQueue<GatewayProbe.Snapshot>()

    @Before
    fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .connectTimeout(2, TimeUnit.SECONDS).build()
    }

    @After
    fun teardown() {
        probe?.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun start(replyTimeout: Long = 2_000): GatewayProbe {
        return GatewayProbe(client, observer = { events.offer(it) }, heartbeatIntervalMs = 100,
            replyTimeoutMs = replyTimeout).also {
            probe = it
            it.start(EntryConfig(server.url("/ws/device").toString().replaceFirst("https://", "wss://"), "test-only-token"))
        }
    }

    private fun await(state: GatewayProbe.State): GatewayProbe.Snapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (event.state == state) return event
            check(event.state !in setOf(GatewayProbe.State.FAILED, GatewayProbe.State.AUTH_FAILED)) {
                "Unexpected terminal state: $event"
            }
        }
        error("Timed out waiting for $state")
    }

    private fun gateway(reply: Boolean = true, mode: String = "transport-test") = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"type\":\"hello\",\"mode\":\"$mode\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (reply && text == "{\"type\":\"heartbeat\"}") webSocket.send("{\"type\":\"heartbeat_ack\"}")
            }
        },
    )

    @Test
    fun authenticatesAndConfirmsRepeatedHeartbeats() {
        server.enqueue(gateway())
        start()
        val first = await(GatewayProbe.State.HEALTHY)
        val second = await(GatewayProbe.State.HEALTHY)
        assertEquals(1, first.heartbeatCount)
        assertEquals(2, second.heartbeatCount)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
        assertEquals("/ws/device", request.path)
    }

    @Test
    fun reportsIncorrectCredentialAsAuthenticationFailure() {
        server.enqueue(MockResponse().setResponseCode(401))
        start()
        assertEquals(401, await(GatewayProbe.State.AUTH_FAILED).httpCode)
    }

    @Test
    fun rejectsUntrustedServerCertificates() {
        client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
        server.enqueue(gateway())
        start()
        assertEquals(GatewayProbe.Failure.TLS, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun aHandshakeWithoutGreetingDoesNotCountAsHealthy() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        start(replyTimeout = 700)
        assertEquals(GatewayProbe.Failure.PROTOCOL, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun doesNotForwardCredentialAcrossRedirects() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://localhost:1/elsewhere"))
        start()
        val failed = await(GatewayProbe.State.FAILED)
        assertEquals(GatewayProbe.Failure.HANDSHAKE, failed.failure)
        assertEquals(302, failed.httpCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingHeartbeatReplyFailsInsteadOfDisplayingHealthy() {
        server.enqueue(gateway(reply = false))
        start(replyTimeout = 700)
        assertEquals(GatewayProbe.Failure.HEARTBEAT, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun rejectsAnUnrecognizedGatewayGreeting() {
        server.enqueue(gateway(mode = "unknown"))
        start()
        assertEquals(GatewayProbe.Failure.PROTOCOL, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun networkLossTerminatesTheProbe() {
        server.enqueue(gateway())
        val active = start()
        await(GatewayProbe.State.HEALTHY)
        active.networkLost()
        assertEquals(GatewayProbe.Failure.NETWORK, await(GatewayProbe.State.FAILED).failure)
    }

    @Test
    fun closingStopsHeartbeatAndIgnoresLateCallbacks() {
        server.enqueue(gateway())
        val active = start()
        await(GatewayProbe.State.HEALTHY)
        active.close()
        await(GatewayProbe.State.DISCONNECTED)
        Thread.sleep(300)
        assertFalse(events.any { it.state == GatewayProbe.State.HEALTHY })
    }
}
