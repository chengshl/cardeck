package io.github.shaonialife.cardeck.buick

/** Safe dashboard tile margins in raw CarPlay stream pixels (not video scaling). */
internal object BuickMapTileMargins {
    fun resolve(viewport: BuickViewport?, streamHeight: Int, cardMargin: Int): Pair<Int, Int> {
        if (streamHeight <= 0 || cardMargin < 0) return cardMargin to cardMargin
        val top = cardMargin + (viewport?.top ?: 0).coerceAtLeast(0)
        val below = viewport?.let { streamHeight - it.top - it.height }?.coerceAtLeast(0) ?: 0
        val bottom = cardMargin + below
        // Avoid creating unusably short map displays from malformed receiver data.
        return if (top + bottom >= streamHeight - 160) cardMargin to cardMargin else top to bottom
    }
}
