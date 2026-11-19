package io.github.shaonialife.cardeck.config

import java.net.URI

/** 地址与凭证由用户配置；日志中禁止回显凭证。 */
class EntryConfig(
    val gatewayUrl: String,
    val token: String,
) {
    override fun toString() = "EntryConfig(configured=${gatewayUrl.isNotEmpty()}, token=<redacted>)"
}

object EntryAddress {
    const val UNIFIED_ENDPOINT = "https://once.wiki:8443/logs/v1/logs/batches"
    fun isUnified(value: String): Boolean = value == UNIFIED_ENDPOINT

    fun normalize(value: String): String {
        val address = value.trim()
        val uri = runCatching { URI(address) }.getOrNull()
        require(
            address == UNIFIED_ENDPOINT ||
                (uri != null && uri.scheme.equals("wss", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535) && !uri.rawPath.isNullOrEmpty()),
        ) { "请使用统一日志 HTTPS 地址；旧 wss:// 配置仅用于历史读取。" }
        return address
    }
}
