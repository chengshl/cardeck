package io.github.shaonialife.cardeck.gateway

/**
 * User-confirmed, bounded detailed snapshot. The regular 256-line diagnostic
 * remains unchanged. Crash and stage evidence must not be evicted by logcat.
 * All inputs have been redacted before reaching this selector.
 */
internal object DetailedDiagnosticSelector {
    const val MAX_LINES = 3000
    const val PREVIEW_LINES = 256

    fun merge(
        crashEvidence: List<String>,
        stageEvidence: List<String>,
        appHistory: List<String>,
        systemLines: List<String>,
        shizukuReady: Boolean,
    ): List<String> {
        fun List<String>.clean(limit: Int): List<String> =
            asSequence().filter { it.isNotBlank() && it != "[已隐藏含凭证或密钥的日志行]" }
                .map { it.take(256) }.take(limit).toList()

        val important = crashEvidence.clean(256)
        val stages = stageEvidence.takeLast(50).map { "[阶段事件] " + it }.clean(50)
        val app = appHistory.takeLast(350).map { "[App历史] " + it }.clean(350)
        val remaining = MAX_LINES - important.size - stages.size - app.size
        val system = if (shizukuReady) {
            systemLines.takeLast(remaining).map { "[当前系统] " + it }.clean(remaining)
        } else {
            emptyList()
        }
        return important + stages + app + system
    }

    /** Only the small review sample is materialized in a phone TextView. */
    fun reviewSample(lines: List<String>): List<String> {
        if (lines.size <= PREVIEW_LINES) return lines
        return lines.take(128) + lines.takeLast(128)
    }
}
