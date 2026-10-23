package io.github.shaonialife.cardeck.gateway

/** Pure offline report formatting. Never copy raw iAP2/RTSP packet bytes. */
internal object OfflineLogFormatter {
    private val rawProtocol = Regex(
        """(?:^|\]\s*)RAW\s+(?:tx|rx):|0x4301\s+无线组参数|0x1d01\s+原始体|""" +
            """AIRPLAY:.*(?:resp=.*head=\[|req=.*head=\[)""",
        RegexOption.IGNORE_CASE,
    )
    private val logTime = Regex("""^\[\d{2}:\d{2}:\d{2}(?:\.\d+)?\]""")

    fun appLines(raw: String, redactor: LogRedactor): List<String> =
        raw.lineSequence().filter { it.isNotBlank() }.map { line ->
            if (rawProtocol.containsMatchIn(line)) {
                val time = logTime.find(line)?.value.orEmpty()
                redactor.line("$time [原始协议数据已省略]")
            } else {
                redactor.line(line)
            }
        }.filter { it.isNotBlank() }.toList()

    fun format(title: String, version: String, device: String, lines: List<String>, time: String): String =
        buildString {
            appendLine("CarDeck 本地诊断日志（已脱敏）")
            appendLine("类型: $title")
            appendLine("导出时间: $time")
            appendLine("应用版本: $version")
            appendLine("设备: $device")
            appendLine("行数: ${lines.size}")
            appendLine("说明: 本文件仅在用户主动保存后写入手机，不会自动上传。")
            appendLine("==================================================")
            lines.forEach { appendLine(it) }
        }
}
