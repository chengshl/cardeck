package io.github.shaonialife.cardeck.buick

/** Content padding for activities rendered on the *app* virtual display.
 *
 * T34 already positions that display after the enlarged left Dock / wallpaper
 * safety band. Do NOT add the left inset again. Its right/top/bottom corners
 * still need independent protection, without rescaling the GL framebuffer.
 */
internal object BuickSettingsInsets {
    fun forApp(
        viewport: BuickViewport?,
        streamWidth: Int,
        streamHeight: Int,
        appDisplayWidth: Int,
        activityWidth: Int,
        activityHeight: Int,
    ): BuickInsets {
        if (viewport == null || streamWidth <= 0 || streamHeight <= 0 ||
            appDisplayWidth <= 0 || activityWidth <= 0 || activityHeight <= 0) return BuickInsets.ZERO
        if (viewport.width <= 0 || viewport.height <= 0) return BuickInsets.ZERO
        val ratio = activityWidth.toFloat() / appDisplayWidth
        if (!ratio.isFinite() || ratio <= 0f) return BuickInsets.ZERO
        // Recompute physical top-right space from the selected safe rectangle.
        // Display /info and output compositor may have different pixel sizes;
        // viewport already reflects the actual negotiated stream dimensions.
        val right = (streamWidth - viewport.left - viewport.width).coerceAtLeast(0)
        val bottom = (streamHeight - viewport.top - viewport.height).coerceAtLeast(0)
        return BuickInsets(
            left = 0,
            top = (viewport.top.coerceAtLeast(0) * ratio).toInt()
                .coerceAtMost(activityHeight / 3),
            right = (right * ratio).toInt()
                .coerceAtMost(activityWidth / 3),
            bottom = (bottom * ratio).toInt()
                .coerceAtMost(activityHeight / 3),
        )
    }
}
