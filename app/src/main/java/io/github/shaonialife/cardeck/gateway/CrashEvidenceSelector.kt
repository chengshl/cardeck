package io.github.shaonialife.cardeck.gateway

/**
 * Manual, bounded crash-evidence summary. A retained DropBox crash is not a new crash;
 * preserve original event time and exception context before user-confirmed upload.
 */
internal object CrashEvidenceSelector {
    private const val SESSION_BREAK = "=== CarDeck 前次进程分隔 ==="
    private const val MAX_LINES = 64 // GatewayProbe.uploadConfirmedSystemLogs enforces this limit.
    private val tags = listOf("system_server_crash", "system_server_watchdog", "SYSTEM_RESTART", "SYSTEM_LAST_KMSG")
    private val clue = Regex(
        "FATAL|Exception|ANR|Watchdog|system_server|rescue.?party|reboot|restart|" +
            "tombstone|signal [0-9]+|WindowContextListenerImpl|DisplayManager|" +
            "Bluetooth|BT-ADAPTER|SHIZUKU|SESSION|LINK closed|crash|崩溃|重启|蓝牙",
        RegexOption.IGNORE_CASE,
    )
    private val keyFault = Regex(
        "FATAL|Exception|Caused by:|Process:|Subject:|Reason:|ANR in|signal [0-9]+|" +
            "WindowContainer|RootWindowContainer|DisplayManager|Abort message|backtrace:|system_server died",
        RegexOption.IGNORE_CASE,
    )
    private val stackFrame = Regex("""^(?:at\s+|#\d\d\s+|native:\s+|Caused by:)""", RegexOption.IGNORE_CASE)
    private val noise = Regex("""^(?:VIDEO: 帧|RAW (?:tx|rx):|IAPLINK: tx\(|IAPLINK: rx\()""")
    private val noEntries = Regex(
        "No entries|No matching|permission denied|Can't find|not found|error:|unknown option",
        RegexOption.IGNORE_CASE,
    )
    private val metadata = Regex(
        """^(?:={3,}|Drop box contents:|Max entries:|Low priority rate limit|Low priority tags:|Searching for:)""",
        RegexOption.IGNORE_CASE,
    )
    private val fullEventDate = Regex("""\b\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?\b""")

    fun joinPriorSessions(sessions: List<String>): String = sessions.joinToString("\n$SESSION_BREAK\n")

    /** Stable identity for one dated event, rather than a stack common to many events. */
    fun datedCrashId(preview: List<String>): String? =
        preview.firstOrNull {
            it.startsWith("[DropBox:system_server_crash] ") && fullEventDate.containsMatchIn(it)
        }?.let { line ->
            val timestamp = fullEventDate.find(line)?.value ?: return@let null
            "system_server_crash@" + timestamp
        }

    private fun latestEntry(raw: String, tag: String): Pair<List<String>, Boolean> {
        val lines = raw.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !noEntries.containsMatchIn(it) }.toList()
        // "dumpsys dropbox --print" emits a date and tag for each entry.
        // Count metadata must never be treated as an event timestamp.
        val header = Regex(
            """^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?\s+""" +
                Regex.escape(tag) + """\s+\(""",
            RegexOption.IGNORE_CASE,
        )
        val newest = lines.indexOfLast { header.containsMatchIn(it) }
        return if (newest >= 0) lines.drop(newest) to true
        else lines.filterNot { metadata.containsMatchIn(it) }.takeLast(128) to false
    }

    fun select(
        previousApp: String?,
        previousBootCrash: String?,
        dropboxByTag: Map<String, String>,
        shizukuAvailable: Boolean,
        redactor: LogRedactor,
    ): List<String> {
        val output = ArrayList<String>(MAX_LINES)
        fun add(source: String, line: String) {
            if (output.size >= MAX_LINES) return
            val sanitized = redactor.line("[$source] " + line.trim())
            if (sanitized.isNotBlank() && sanitized != "[已隐藏含凭证或密钥的日志行]") {
                output.add(sanitized.take(256))
            }
        }

        // Also keep last actions without keywords; they may show what immediately preceded a restart.
        val sessions = previousApp.orEmpty().split(SESSION_BREAK).takeLast(3)
        for ((index, session) in sessions.withIndex()) {
            val clean = session.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && !noise.containsMatchIn(it) }.toList()
            val chosen = (clean.filter { clue.containsMatchIn(it) }.takeLast(2) +
                clean.takeLast(2)).distinct().takeLast(4)
            chosen.forEach { add("上轮App" + (index + 1), it) }
        }

        if (!shizukuAvailable) {
            add("取证状态", "Shizuku未运行或未授权：只有前次App记录；需重新启动Shizuku后补查系统证据")
            return output
        }

        // Previous-boot logcat -L, never current-boot -b crash.
        val boot = previousBootCrash.orEmpty().lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() && !noEntries.containsMatchIn(it) }.toList()
        val bootInteresting = boot.filter {
            keyFault.containsMatchIn(it) || stackFrame.containsMatchIn(it)
        }.takeLast(12)
        bootInteresting.forEach { add("上次启动crash", it) }

        var datedEvents = 0
        var unknownDates = 0
        val quotas = mapOf(
            "system_server_crash" to 24,
            "system_server_watchdog" to 6,
            "SYSTEM_RESTART" to 6,
            "SYSTEM_LAST_KMSG" to 6,
        )
        for (tag in tags) {
            val (entry, hasDate) = latestEntry(dropboxByTag[tag].orEmpty(), tag)
            if (entry.isEmpty()) continue
            if (hasDate) datedEvents++ else unknownDates++
            val limit = quotas.getValue(tag)
            val chosen = linkedSetOf<Int>()
            if (hasDate) chosen.add(0)
            // Original occurrence time, first exception/caused-by and stack outrank footers.
            entry.indices.filter { keyFault.containsMatchIn(entry[it]) }
                .take(if (tag == "system_server_crash") 8 else 3).forEach { chosen.add(it) }
            entry.indices.filter { stackFrame.containsMatchIn(entry[it]) }
                .take(if (tag == "system_server_crash") 12 else 3).forEach { chosen.add(it) }
            entry.indices.filter { !metadata.containsMatchIn(entry[it]) }
                .take(if (tag == "system_server_crash") 6 else 5).forEach { chosen.add(it) }
            chosen.toList().sorted().take(limit).forEach { add("DropBox:$tag", entry[it]) }
        }
        if (unknownDates > 0) {
            add("取证状态", "有 " + unknownDates + " 类DropBox内容未识别到事件时间；可能是截断的旧记录，不能视为新崩溃")
        }
        if (datedEvents == 0 && bootInteresting.isEmpty()) {
            add("取证状态", "系统未返回带时间的可识别崩溃事件；可能未保留或已轮转，不能据此判定无崩溃")
        }
        return output
    }
}
