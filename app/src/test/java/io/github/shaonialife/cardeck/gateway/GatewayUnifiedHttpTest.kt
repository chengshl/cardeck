package io.github.shaonialife.cardeck.gateway

import io.github.shaonialife.cardeck.config.EntryAddress
import io.github.shaonialife.cardeck.config.EntryConfig
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A local intercept-only integration: no public network request or real token is sent. */
class GatewayUnifiedHttpTest {
    private val installation = GatewayDeviceInfo("65bddcd6-0e81-4f98-b509-7aafc1ddc3f8", "test", 35)
    private val fakeWriter = "SyntheticWriterOnly1234567890ABCDEF0000000000"

    private fun client(handler: (JSONObject) -> Pair<Int, JSONObject>): Pair<OkHttpClient, LinkedBlockingQueue<JSONObject>> {
        val captured = LinkedBlockingQueue<JSONObject>()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals(EntryAddress.UNIFIED_ENDPOINT, request.url.toString())
            assertEquals("Bearer $fakeWriter", request.header("Authorization"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            assertEquals(setOf("schema_version", "batch_id", "client_id", "source",
                "user_confirmed", "lines"), payload.keys().asSequence().toSet())
            assertEquals(2, payload.getInt("schema_version"))
            assertEquals(installation.deviceId, payload.getString("client_id"))
            captured.add(payload)
            val (code, reply) = handler(payload)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("synthetic").body(reply.toString()
                    .toResponseBody("application/json".toMediaType())).build()
        }).build()
        return http to captured
    }

    private fun ack(payload: JSONObject, schema: Int = 2): JSONObject =
        JSONObject().put("schema_version", schema)
            .put("batch_id", payload.getString("batch_id"))
            .put("accepted_lines", payload.getJSONArray("lines").length())
            .put("duplicate", false)

    private fun waitState(events: LinkedBlockingQueue<GatewayProbe.Snapshot>, state: GatewayProbe.State): GatewayProbe.Snapshot {
        repeat(12) {
            val item = events.poll(2, TimeUnit.SECONDS) ?: return@repeat
            if (item.state == state) return item
        }
        error("No state $state, received $events")
    }

    private fun start(client: OkHttpClient, buffer: GatewayLogBuffer,
                      events: LinkedBlockingQueue<GatewayProbe.Snapshot>): GatewayProbe =
        GatewayProbe(client, observer = { events.add(it) }, replyTimeoutMs = 2000,
            deviceInfo = installation, appLogs = buffer).also {
            it.start(EntryConfig(EntryAddress.UNIFIED_ENDPOINT, fakeWriter))
        }

    @Test fun rawUtf8AndSensitiveLookingContentsAreSentVerbatimOnlyAfterHttpAck() {
        val raw = "2026-11-19 12:34:56.123 password=synthetic Authorization Bearer TEST 192.0.2.20 😀"
        val queue = GatewayLogBuffer(LogRedactor(listOf("TEST"), verbatim = true))
        queue.offer(raw)
        val (client, captured) = client { payload -> 200 to ack(payload) }
        val events = LinkedBlockingQueue<GatewayProbe.Snapshot>()
        val probe = start(client, queue, events)
        try {
            val healthy = waitState(events, GatewayProbe.State.HEALTHY)
            assertTrue(healthy.appLogLines >= 1)
            val payload = captured.poll(2, TimeUnit.SECONDS)!!
            val lines = payload.getJSONArray("lines")
            assertEquals(raw, lines.getString(0))
            assertFalse(payload.toString().contains("[已隐藏]"))
            assertFalse(payload.toString().contains("[已省略]"))
        } finally {
            probe.close()
        }
    }

    @Test fun mismatchedSchemaAckCannotCountAsSuccess() {
        val queue = GatewayLogBuffer(LogRedactor(verbatim = true))
        queue.offer("unchanged line")
        val (client, _) = client { payload -> 200 to ack(payload, schema = 1) }
        val events = LinkedBlockingQueue<GatewayProbe.Snapshot>()
        val probe = start(client, queue, events)
        try {
            assertEquals(GatewayProbe.Failure.PROTOCOL, waitState(events, GatewayProbe.State.FAILED).failure)
            assertFalse(events.any { it.state == GatewayProbe.State.HEALTHY })
        } finally { probe.close() }
    }

    @Test fun failedHttpPostReplaysExactlySameBatchIdAndOriginalContent() {
        val queue = GatewayLogBuffer(LogRedactor(verbatim = true))
        queue.offer("unredacted test credential=ONLY_TEST_SECRET")
        val first = client { payload -> 503 to ack(payload) }
        val events1 = LinkedBlockingQueue<GatewayProbe.Snapshot>()
        val probe1 = start(first.first, queue, events1)
        try {
            assertEquals(GatewayProbe.Failure.LOG_UPLOAD, waitState(events1, GatewayProbe.State.FAILED).failure)
        } finally { probe1.close() }
        val firstBody = first.second.poll(2, TimeUnit.SECONDS)!!
        val second = client { payload -> 200 to ack(payload) }
        val events2 = LinkedBlockingQueue<GatewayProbe.Snapshot>()
        val probe2 = start(second.first, queue, events2)
        try {
            waitState(events2, GatewayProbe.State.HEALTHY)
            val secondBody = second.second.poll(2, TimeUnit.SECONDS)!!
            assertEquals(firstBody.getString("batch_id"), secondBody.getString("batch_id"))
            assertEquals(firstBody.getJSONArray("lines").toString(), secondBody.getJSONArray("lines").toString())
        } finally { probe2.close() }
    }
}
