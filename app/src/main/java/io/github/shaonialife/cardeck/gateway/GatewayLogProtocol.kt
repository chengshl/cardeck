package io.github.shaonialife.cardeck.gateway

import org.json.JSONArray
import org.json.JSONObject

/** Shared v2 limits with Store: whole UTF-8 rows, whole serialized frames, no truncation. */
object GatewayLogProtocol {
    const val MAX_LINE_BYTES = 12 * 1024
    const val MAX_FRAME_BYTES = 16 * 1024
    private const val BATCH_ID_SAMPLE = "00000000-0000-4000-8000-000000000000"

    fun message(lines: List<String>, source: String, vehicle: String?, id: String): String =
        JSONObject().put("type", "logs").put("schema_version", 2)
            .put("source", source).put("batch_id", id)
            .put("user_confirmed", source == "system").put("lines", JSONArray(lines)).apply {
                vehicle?.let { put("vehicle_id", it) }
            }.toString()

    // Once unified ingestion derives project from the writer credential, not the body.
    // clientId must be the installation UUIDv4 registered for that credential.
    fun httpMessage(lines: List<String>, source: String, clientId: String, id: String): String =
        JSONObject().put("schema_version", 2).put("batch_id", id)
            .put("client_id", clientId).put("source", source)
            .put("user_confirmed", source == "system").put("lines", JSONArray(lines)).toString()

    fun fitsHttp(lines: List<String>, source: String): Boolean =
        lines.size in 1..8 && source in setOf("app", "system") && lines.all {
            it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES
        } && httpMessage(lines, source, BATCH_ID_SAMPLE, BATCH_ID_SAMPLE)
            .toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES

    fun planHttp(lines: List<String>, source: String): List<List<String>> {
        val groups = mutableListOf<List<String>>()
        var pending = mutableListOf<String>()
        for (line in lines) {
            require(fitsHttp(listOf(line), source)) {
                "单行或完整 JSON 超过统一日志合同上限；原文不截断，请本地导出"
            }
            if (pending.isNotEmpty() && !fitsHttp(pending + line, source)) {
                groups.add(pending.toList()); pending = mutableListOf()
            }
            pending.add(line)
        }
        if (pending.isNotEmpty()) groups.add(pending.toList())
        return groups
    }

    fun fits(lines: List<String>, source: String, vehicle: String?): Boolean =
        lines.size in 1..8 && lines.all {
            it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES
        } && message(lines, source, vehicle, BATCH_ID_SAMPLE).toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES

    fun plan(lines: List<String>, source: String, vehicle: String?): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var batch = mutableListOf<String>()
        for (line in lines) {
            require(fits(listOf(line), source, vehicle)) { "日志单行或JSON编码超限，请保留并本地导出原文" }
            if (batch.isNotEmpty() && !fits(batch + line, source, vehicle)) {
                result.add(batch.toList()); batch = mutableListOf()
            }
            batch.add(line)
        }
        if (batch.isNotEmpty()) result.add(batch.toList())
        return result
    }
}
