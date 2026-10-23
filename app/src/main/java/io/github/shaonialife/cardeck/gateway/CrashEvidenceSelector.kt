package io.github.shaonialife.cardeck.gateway

/**
 * Pure, bounded evidence selection. Called only for a user-initiated, previewed
 * crash report. Raw shell output is NEVER uploaded or written to disk.
 */
internal object CrashEvidenceSelector {
    private val clue = Regex(
        "FATAL|Exception|ANR|Watchdog|system_server|rescue.?party|reboot|restart|" +
            "tombstone|signal [0-9]+|WindowContextListenerImpl|DisplayManager|" +
            "Bluetooth|BT-ADAPTER|SHIZUKU|SESSION|LINK closed|crash|崩溃|重启|蓝牙",
        RegexOption.IGNORE_CASE,
    )
    private val noise = Regex("^(?:VIDEO: 帧|RAW (?:tx|rx):|IAPLINK: tx\\(|IAPLINK: rx\\()")
    private val noEntries = Regex(
        "No entries|No matching|permission denied|Can't find|not found|error:|unknown option",
        RegexOption.IGNORE_CASE,
    )

    fun select(
        previousApp: String?,
        previousBootCrash: String?,
        dropboxByTag: Map<String, String>,
        shizukuAvailable: Boolean,
        redactor: LogRedactor,
    ): List<String> {
        val output = ArrayList<String>(64)
        fun add(source: String, line: String) {
            if (output.size >= 64) return
            val sanitized = redactor.line("[$source] ${line.trim()}")
            if (sanitized.isNotBlank()) output.add(sanitized.take(256))
        }

        // Save the final meaningful pre-reboot app events even if the log stream
        // stopped when the system was killed. The file holds up to 2 MB only.
        val previous = previousApp.orEmpty().lineSequence()
            .map(String::trim).filter { it.isNotBlank() && !noise.containsMatchIn(it) }
            .toList()
        val previousClues = previous.filter { clue.containsMatchIn(it) }.takeLast(14)
        if (previousClues.isNotEmpty()) previousClues.forEach { add("上轮App", it) }
        else previous.takeLast(6).forEach { add("上轮App", it) }

        if (!shizukuAvailable) {
            add("取证状态", "Shizuku未运行或未授权：只含上轮App文件；启动Shizuku后重新取证才能读取系统记录")
            return output
        }

        // -L asks Android's logcat for the last boot (if pstore/pmsg is
        // supported); never mislabel current-boot -b crash as previous boot.
        val bootLines = previousBootCrash.orEmpty().lineSequence()
            .map(String::trim).filter { it.isNotBlank() && !noEntries.containsMatchIn(it) }
            .toList()
        val bootCandidates = bootLines.filter { clue.containsMatchIn(it) ||
            it.startsWith("at ") || it.startsWith("Caused by:") || it.startsWith("E/") }
        bootCandidates.takeLast(18).forEach { add("上次启动crash", it) }

        val tags = listOf("system_server_crash", "system_server_watchdog", "SYSTEM_RESTART", "SYSTEM_LAST_KMSG")
        for (tag in tags) {
            val raw = dropboxByTag[tag].orEmpty()
            val lines = raw.lineSequence().map(String::trim)
                .filter { it.isNotBlank() && !noEntries.containsMatchIn(it) }.toList()
            // Prefer the latest entry with the most relevant crash stack. A
            // DropBox dump can include unrelated earlier reports.
            val hints = lines.mapIndexedNotNull { index, line ->
                if (clue.containsMatchIn(line)) index else null
            }
            val end = (hints.lastOrNull() ?: lines.lastIndex).coerceAtLeast(0)
            val from = (end - 6).coerceAtLeast(0)
            lines.drop(from).take(12).forEach { add("DropBox:$tag", it) }
        }
        if (bootCandidates.isEmpty() && dropboxByTag.values.none { value ->
                value.lineSequence().any { clue.containsMatchIn(it) && !noEntries.containsMatchIn(it) }
            }) {
            add("取证状态", "系统未返回可识别崩溃栈；可能已轮转、厂商未保留或Shizuku权限不足，不能据此判定无崩溃")
        }
        return output
    }
}
