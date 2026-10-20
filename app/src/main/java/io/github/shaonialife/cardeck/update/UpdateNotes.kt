package io.github.shaonialife.cardeck.update

/**
 * GitHub's unified release keeps machine-readable version metadata at the top,
 * then an optional human-friendly changelog after "## 更新说明".
 * Parse as plain text: never render remote HTML or let lengthy release bodies
 * overwhelm the in-app update dialog. Works identically in all three branches.
 */
internal object UpdateNotes {
    private const val HEADER = "## 更新说明"
    private const val MAX_CHARS = 1_600
    private const val EMPTY = "此次构建暂未提供详细更新说明。"

    fun extract(releaseBody: String): String {
        val lines = releaseBody.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim() == HEADER }
        if (start < 0) return EMPTY
        val notes =
            lines.drop(start + 1)
                .takeWhile { !it.trim().startsWith("## ") }
                .map { line ->
                    when {
                        line.startsWith("- ") -> "• " + line.drop(2)
                        line.startsWith("* ") -> "• " + line.drop(2)
                        else -> line
                    }
                }
                .joinToString("\n")
                .trim()
        return notes.take(MAX_CHARS).ifBlank { EMPTY }
    }
}
