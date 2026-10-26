package io.github.shaonialife.cardeck.buick

/**
 * T68 实验：设置页面与第三方 App 共用的视觉外框，输出流坐标（左上原点）。
 *
 * 不改变虚拟屏 / 触摸域。外框是纯视觉底板；应用原始比例仍由
 * BuickStandaloneFit 决定，外框内的上下空白无法凭空变成地图内容。
 */
internal object BuickStandaloneFrame {
    fun resolve(
        safe: BuickViewport?,
        dockWidth: Int,
        streamWidth: Int,
        streamHeight: Int,
    ): BuickViewport? {
        if (safe == null || dockWidth < 0 || dockWidth >= streamWidth ||
            streamWidth <= 0 || streamHeight <= 0 ||
            safe.width <= 0 || safe.height <= 0
        ) return null
        val left = maxOf(dockWidth, safe.left)
        val right = minOf(streamWidth, safe.left + safe.width)
        val top = maxOf(0, safe.top)
        val bottom = minOf(streamHeight, safe.top + safe.height)
        if (right - left < 320 || bottom - top < 160) return null
        return BuickViewport(left, top, right - left, bottom - top)
    }
}
