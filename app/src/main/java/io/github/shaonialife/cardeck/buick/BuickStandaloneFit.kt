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
        if (safe == null || dockWidth < 0 || dockWidth >= streamWidth ||
            streamWidth <= 0 || streamHeight <= 0) return null
        val x0 = maxOf(dockWidth, safe.left)
        val x1 = minOf(streamWidth, safe.left + safe.width)
        val y0 = maxOf(0, safe.top)
        val y1 = minOf(streamHeight, safe.top + safe.height)
        val availableW = x1 - x0
        val availableH = y1 - y0
        if (availableW < 320 || availableH < 160) return null
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
