package io.github.shaonialife.cardeck.gateway

/**
 * Manual, bounded crash-evidence summary. A retained DropBox crash is not a new crash;
 * preserve original event time and exception context before user-confirmed upload.
 */
internal object CrashEvidenceSelector {
    private const val SESSION_BREAK = "=== CarDeck 前次进程分隔 ==="
    private const val MAX_LINES = 256 // GatewayProbe splits confirmed evidence into 8-line packets.
    private val tags = listOf(
        "system_server_crash", "system_app_crash", "system_app_anr",
        "system_server_watchdog", "SYSTEM_RESTART", "SYSTEM_LAST_KMSG",
        "SYSTEM_TOMBSTONE", "system_app_native_crash",
    )
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
        preview.firstNotNullOfOrNull { line ->
            val match = Regex("""^\[DropBox:(system_server_crash|system_app_crash|system_app_anr|system_app_native_crash|SYSTEM_TOMBSTONE)\]""").find(line)
            if (match != null && fullEventDate.containsMatchIn(line)) {
                val timestamp = fullEventDate.find(line)?.value ?: return@firstNotNullOfOrNull null
                match.groupValues[1] + "@" + timestamp
            } else null
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
        currentBootCrash: String? = null,
        criticalTrail: List<String> = emptyList(),
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
            val chosen = (clean.filter { clue.containsMatchIn(it) }.takeLast(4) +
                clean.takeLast(4)).distinct().takeLast(8)
            chosen.forEach { add("上轮App" + (index + 1), it) }
        }

        // T52: all 50 persisted breadcrumb stages in exact recording order,
        // including cross-process/possible-reboot boundaries. Never depend on Shizuku.
        criticalTrail.takeLast(50).forEach { add("阶段事件", it) }
        if (!shizukuAvailable) {
            add("取证状态", "Shizuku未运行或未授权：仅本地阶段事件与前次App；启动后可补查系统证据")
            return output
        }

        // Current crash buffer is separate from last-boot -L. These logs can
        // reveal system_app_crash even if the DropBox record has been rotated.
        val currentBoot = currentBootCrash.orEmpty().lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() && !noEntries.containsMatchIn(it) }.toList()
        val currentInteresting = currentBoot.filter {
            keyFault.containsMatchIn(it) || stackFrame.containsMatchIn(it) ||
                it.contains("AndroidRuntime", ignoreCase = true)
        }.takeLast(32)
        currentInteresting.forEach { add("本次启动crash", it) }

        // Previous-boot logcat -L, never current-boot -b crash.
        val boot = previousBootCrash.orEmpty().lineSequence()
            .map { it.trim() }.filter { it.isNotBlank() && !noEntries.containsMatchIn(it) }.toList()
        val bootInteresting = boot.filter {
            keyFault.containsMatchIn(it) || stackFrame.containsMatchIn(it)
        }.takeLast(20)
        bootInteresting.forEach { add("上次启动crash", it) }

        var datedEvents = 0
        var unknownDates = 0
        val quotas = mapOf(
            "system_server_crash" to 50,
            "system_app_crash" to 34,
            "system_app_anr" to 12,
            "system_server_watchdog" to 12,
            "SYSTEM_RESTART" to 10,
            "SYSTEM_LAST_KMSG" to 10,
            "SYSTEM_TOMBSTONE" to 16,
            "system_app_native_crash" to 16,
        )
        // Newest dated event first: the previous 21:27 WindowContext stack must
        // not mask a 21:52 persistent system-process crash.
        val events = tags.mapNotNull { tag ->
            val (entry, hasDate) = latestEntry(dropboxByTag[tag].orEmpty(), tag)
            if (entry.isEmpty()) null else Triple(tag, entry, hasDate)
        }.sortedByDescending { (_, entry, hasDate) ->
            if (hasDate) fullEventDate.find(entry.firstOrNull().orEmpty())?.value.orEmpty() else ""
        }
        for ((tag, entry, hasDate) in events) {
            if (hasDate) datedEvents++ else unknownDates++
            val limit = quotas.getValue(tag)
            val chosen = linkedSetOf<Int>()
            if (hasDate) chosen.add(0)
            // Original occurrence time, first exception/caused-by and stack outrank footers.
            entry.indices.filter { keyFault.containsMatchIn(entry[it]) }
                .take(if (tag == "system_server_crash" || tag == "system_app_crash") 35 else 10).forEach { chosen.add(it) }
            entry.indices.filter { stackFrame.containsMatchIn(entry[it]) }
                .take(if (tag == "system_server_crash" || tag == "system_app_crash") 55 else 10).forEach { chosen.add(it) }
            entry.indices.filter { !metadata.containsMatchIn(entry[it]) }
                .take(if (tag == "system_server_crash" || tag == "system_app_crash") 15 else 12).forEach { chosen.add(it) }
            chosen.toList().sorted().take(limit).forEach { add("DropBox:$tag", entry[it]) }
        }
        if (unknownDates > 0) {
            add("取证状态", "有 " + unknownDates + " 类DropBox内容未识别到事件时间；可能是截断的旧记录，不能视为新崩溃")
        }
        if (datedEvents == 0 && bootInteresting.isEmpty() && currentInteresting.isEmpty()) {
            add("取证状态", "系统未返回带时间的可识别崩溃事件；可能未保留或已轮转，不能据此判定无崩溃")
        }
        return output
    }
}
