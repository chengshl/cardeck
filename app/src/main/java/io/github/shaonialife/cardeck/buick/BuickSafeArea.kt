package io.github.shaonialife.cardeck.buick

/** All geometry is in the receiver's unscaled CarPlay display coordinates (top-left origin). */
data class BuickInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object { val ZERO = BuickInsets(0, 0, 0, 0) }
}

data class BuickSafeArea(
    val displayWidth: Int,
    val displayHeight: Int,
    val insets: BuickInsets,
)

data class BuickViewport(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun contains(x: Int, y: Int): Boolean =
        x >= left && y >= top && x < left + width && y < top + height

    /** An inverse of the compositor's GL viewport, before normal dock/app hit-testing. */
    fun unmap(x: Int, y: Int, canvasWidth: Int, canvasHeight: Int): Pair<Int, Int> =
        Pair(
            (((x - left).toFloat() * canvasWidth / width).toInt()).coerceIn(0, canvasWidth - 1),
            (((y - top).toFloat() * canvasHeight / height).toInt()).coerceIn(0, canvasHeight - 1),
        )
}

internal object BuickSafeAreaGeometry {
    /**
     * Reads the actual /info displays[].viewAreas[initialViewArea].safeArea.
     * Never infer geometry from vehicle model or physical display advertising.
     */
    fun parse(display: Map<*, *>?): BuickSafeArea? {
        if (display == null) return null
        val w = (display["widthPixels"] as? Number)?.toInt() ?: return null
        val h = (display["heightPixels"] as? Number)?.toInt() ?: return null
        if (w !in 320..12000 || h !in 160..5000) return null
        val areas = display["viewAreas"] as? List<*> ?: return null
        val index = ((display["initialViewArea"] as? Number)?.toInt() ?: 0)
        if (index !in areas.indices) return null
        val viewArea = areas[index] as? Map<*, *> ?: return null
        val safe = viewArea["safeArea"] as? Map<*, *> ?: return null
        val viewX = (viewArea["originXPixels"] as? Number)?.toInt() ?: 0
        val viewY = (viewArea["originYPixels"] as? Number)?.toInt() ?: 0
        val x = viewX + ((safe["originXPixels"] as? Number)?.toInt() ?: return null)
        val y = viewY + ((safe["originYPixels"] as? Number)?.toInt() ?: return null)
        val sw = (safe["widthPixels"] as? Number)?.toInt() ?: return null
        val sh = (safe["heightPixels"] as? Number)?.toInt() ?: return null
        if (x < 0 || y < 0 || sw <= 0 || sh <= 0 || x + sw > w || y + sh > h) return null
        return BuickSafeArea(w, h, BuickInsets(x, y, w - x - sw, h - y - sh))
    }

    /**
     * Modes: auto=receiver's exact area; relaxed=50% of receiver's margins;
     * custom=user margins in receiver pixels; off=original full-screen.
     * This is only an optional video compositor viewport; never alter RTSP/HID sizes.
     */
    fun resolve(
        source: BuickSafeArea?,
        displayWidth: Int,
        displayHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
        mode: String,
        custom: BuickInsets,
    ): BuickViewport? {
        if (mode == "off") return null
        if (displayWidth <= 0 || displayHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) return null
        val margins = when (mode) {
            "auto" -> source?.insets ?: return null
            "relaxed" -> source?.insets?.let { BuickInsets(it.left / 2, it.top / 2, it.right / 2, it.bottom / 2) } ?: return null
            "custom" -> custom
            else -> return null
        }
        val l = (margins.left.coerceAtLeast(0).toLong() * streamWidth / displayWidth).toInt()
        val r = (margins.right.coerceAtLeast(0).toLong() * streamWidth / displayWidth).toInt()
        val t = (margins.top.coerceAtLeast(0).toLong() * streamHeight / displayHeight).toInt()
        val b = (margins.bottom.coerceAtLeast(0).toLong() * streamHeight / displayHeight).toInt()
        if (l + r > streamWidth - 320 || t + b > streamHeight - 160) return null
        if (l == 0 && r == 0 && t == 0 && b == 0) return null
        return BuickViewport(l, t, streamWidth - l - r, streamHeight - t - b)
    }
}
