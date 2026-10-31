package io.github.shaonialife.cardeck.gateway

/** Both preview and transmission use this exact bounded representation. */
class LogRedactor(private val secrets: List<String> = emptyList()) {
    private val sensitive = Regex("authorization|bearer|password|passwd|token|secret|private.?key|pairing.?key|session.?key|write.?key|read.?key|credential|nonce|salt|signature|密码|凭证", RegexOption.IGNORE_CASE)
    private val replacements = listOf(
        Regex("\\b[a-z][a-z0-9+.-]*://\\S+", RegexOption.IGNORE_CASE) to "[地址]",
        Regex("\\b(?:BUICK|CHEVROLET|CADILLAC)[_-][0-9]{3,}\\b", RegexOption.IGNORE_CASE) to "[车机蓝牙名]",
        Regex("\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b", RegexOption.IGNORE_CASE) to "[MAC]",
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "[IP]",
        // IPv6 with :: compression (e.g. fe80::1234), including one leading block.
        Regex("""(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){1,7}:[0-9A-Fa-f:]{0,39}(?![0-9A-Fa-f:])""", RegexOption.IGNORE_CASE) to "[IPv6]",
        Regex("(?:[0-9a-f]{1,4}:){2,}[0-9a-f:]+", RegexOption.IGNORE_CASE) to "[IPv6]",
        Regex("\\b[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}\\b") to "[邮箱]",
        Regex("[A-Za-z0-9_+/=-]{32,}") to "[长标识]",
        Regex("\\b\\d{11,18}\\b") to "[数字标识]",
        Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b") to "[车辆标识]",
    )

    // Clock time is diagnostic data, not an IPv6 address.
    private val clockTime = Regex("""(?<![0-9A-Fa-f:])(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?![0-9A-Fa-f:])""")

    /**
     * Deep forensic mode keeps useful AVC/SELinux/stack context while
     * excluding credentials and raw protocol data. It never uploads raw.
     * Output remains compatible with 256-character logs_v1 lines.
     */
    fun forensicChunks(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        if (raw.length > 8192) return listOf("[取证行超过8KB，已省略]")
        if (Regex("""(?:^|\]\s*)RAW\s+(?:tx|rx):|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY""",
                RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            return listOf("[原始协议数据或密钥内容已省略]")
        }
        var safe = raw
        for (secret in secrets.filter { it.isNotEmpty() }) safe = safe.replace(secret, "[已隐藏凭证]")
        // Mask entire Authorization/Bearer sequences before field-value masking,
        // otherwise the word "Bearer" could be removed while its token survives.
        safe = Regex(
            """\bAuthorization\s*[:=]\s*(?:Bearer|Basic)\s+[^\s,;]+|\b(?:Bearer|Basic)\s+[^\s,;]+""",
            RegexOption.IGNORE_CASE,
        ).replace(safe, "[已隐藏认证头]")
        // Mask values rather than dropping a whole stack trace on a generic word.
        val namedSecret = Regex(
            """\b(authorization|password|passwd|access[_-]?token|refresh[_-]?token|token|secret|private[_-]?key|pairing[_-]?key|session[_-]?key|write[_-]?key|read[_-]?key|credential|nonce|salt|signature|密码|凭证)(?:"|')?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)""",
            RegexOption.IGNORE_CASE,
        )
        safe = namedSecret.replace(safe) { it.groupValues[1] + "=[已隐藏]" }
        safe = Regex("""\b(?:Bearer|Basic)\s+[^\s,;]+""", RegexOption.IGNORE_CASE)
            .replace(safe, "[已隐藏认证头]")
        val preservedTimes = ArrayList<String>()
        safe = clockTime.replace(safe) { hit ->
            preservedTimes.add(hit.value)
            "~CARDECK_TIME_" + (preservedTimes.size - 1) + "~"
        }
        for ((pattern, replacement) in replacements) safe = pattern.replace(safe, replacement)
        for ((i, t) in preservedTimes.withIndex()) {
            safe = safe.replace("~CARDECK_TIME_" + i + "~", t)
        }
        safe = Regex("""\b(ssid|serial|device.?id|vin|latitude|longitude)(?:"|')?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)""", RegexOption.IGNORE_CASE)
            .replace(safe) { it.groupValues[1] + "=[已隐藏]" }
        safe = safe.map { if (it.code < 32 || it.code == 127) ' ' else it }.joinToString("")
        return safe.chunked(200).take(42).mapIndexed { i, chunk ->
            "[取证" + (i + 1) + "] " + chunk
        }
    }

    fun line(raw: String): String {
        if (raw.length > 8192) return "[超长日志已省略]"
        var text = raw
        for (secret in secrets.filter { it.isNotEmpty() }) text = text.replace(secret, "[已隐藏凭证]")
        if (sensitive.containsMatchIn(text)) return "[已隐藏含凭证或密钥的日志行]"
        val times = ArrayList<String>()
        text = clockTime.replace(text) { matched ->
            val id = times.size
            times.add(matched.value)
            "~CARDECK_TIME_" + id + "~"
        }
        for ((pattern, replacement) in replacements) text = pattern.replace(text, replacement)
        for ((index, value) in times.withIndex()) text = text.replace("~CARDECK_TIME_" + index + "~", value)
        text = Regex("""\b(ssid|serial|device.?id|vin|latitude|longitude)(?:"|')?\s*[:=].*""", RegexOption.IGNORE_CASE)
            .replace(text) { "${it.groupValues[1]}=[已隐藏]" }
        return text.map { if (it.code < 32 || it.code == 127) ' ' else it }.joinToString("").take(256)
    }
}
