package io.github.shaonialife.cardeck.buick

import kotlin.math.min

/**
 * Preserve the original standalone app/map input aspect ratio in the intersection
 * of the output app region and the actual receiver-safe viewport.
 * GL uses bottom-left origin; the returned rectangle uses top-left stream pixels.
 */
internal object BuickStandaloneFit {
    fun resolve(
        safe: BuickViewport?,
        dockWidth: Int,
        streamWidth: Int,
        streamHeight: Int,
    ): BuickViewport? {
        val outer = BuickStandaloneFrame.resolve(safe, dockWidth, streamWidth, streamHeight)
            ?: return null
        val x0 = outer.left
        val y0 = outer.top
        val availableW = outer.width
        val availableH = outer.height
        val appW = streamWidth - dockWidth
        val scale = min(availableW.toDouble() / appW, availableH.toDouble() / streamHeight)
        if (!scale.isFinite() || scale <= 0.0 || scale > 1.0) return null
        val w = (appW * scale).toInt().coerceAtLeast(1)
        val h = (streamHeight * scale).toInt().coerceAtLeast(1)
        return BuickViewport(
            left = x0 + (availableW - w) / 2,
            top = y0 + (availableH - h) / 2,
            width = w,
            height = h,
        )
    }
}
