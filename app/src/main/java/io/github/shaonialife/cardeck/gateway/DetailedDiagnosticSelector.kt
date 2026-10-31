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

        // Filter before adding source labels: a prefixed blank/secret placeholder
        // is no longer equal to the original marker and must not use upload slots.
        // Walk backwards so trailing filtered lines cannot evict recent evidence.
        fun List<String>.cleanLatest(limit: Int): List<String> =
            asReversed().asSequence()
                .filter { it.isNotBlank() && it != "[已隐藏含凭证或密钥的日志行]" }
                .take(limit).map { it.take(256) }.toList().asReversed()

        val important = crashEvidence.clean(256)
        val stages = stageEvidence.cleanLatest(50).map { ("[阶段事件] " + it).take(256) }
        val app = appHistory.cleanLatest(350).map { ("[App历史] " + it).take(256) }
        val remaining = MAX_LINES - important.size - stages.size - app.size
        val system = if (shizukuReady) {
            systemLines.cleanLatest(remaining).map { ("[当前系统] " + it).take(256) }
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
