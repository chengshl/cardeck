package io.github.shaonialife.cardeck.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.OverlayCard

/** Screen-space bounds shared by the GL button and touch interception. */
internal data class WindowButtonBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/** Dashboard map-window maximize / restore. Never changes task or display ownership. */
internal object WindowFullscreenToggle {
    fun bounds(
        streamW: Int,
        streamH: Int,
        dockW: Int,
        dockAtBottom: Boolean,
        mapTileW: Int,
        mapTileH: Int,
        tileTop: Int,
        tileBottom: Int,
        maximized: Boolean,
    ): WindowButtonBounds? {
        if (streamW <= 0 || streamH <= 0 || mapTileW <= 0 || mapTileH <= 0) return null
        val shorter = minOf(streamW, streamH).toFloat()
        val size = (shorter * 0.105f).coerceIn(48f, 84f)
        val inset = (shorter * 0.02f).coerceIn(8f, 20f)
        val tileLeft = if (dockAtBottom) CARD_MARGIN_PX else dockW + CARD_MARGIN_PX
        val tileWidth = if (dockAtBottom) mapTileW.toFloat() else mapTileW.toFloat()
        val tileHeight = mapTileH.toFloat()
        if (!maximized && (tileWidth < size + inset * 2 || tileHeight < size + inset * 2)) return null
        val left = if (maximized) inset else tileLeft + inset
        val bottom = if (maximized) streamH - inset else
            if (dockAtBottom) CARD_MARGIN_PX + mapTileH - inset else streamH - tileBottom - inset
        val top = bottom - size
        if (top < 0f || left < 0f || left + size > streamW || bottom > streamH) return null
        return WindowButtonBounds(left, top, left + size, bottom)
    }

    fun paint(bounds: WindowButtonBounds, maximized: Boolean): OverlayCard {
        val edge = (bounds.right - bounds.left).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = edge.toFloat()
        paint.color = Color.argb(148, 17, 22, 30)
        canvas.drawRoundRect(0f, 0f, s, s, s * .23f, s * .23f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = s * .053f
        paint.color = Color.WHITE
        val cx = s * .5f
        for (sx in floatArrayOf(-1f, 1f)) {
            for (sy in floatArrayOf(-1f, 1f)) {
                if (maximized) {
                    val tipX = cx + sx * s * .09f
                    val tipY = cx + sy * s * .09f
                    canvas.drawLine(cx + sx * s * .29f, cx + sy * s * .29f, tipX, tipY, paint)
                    canvas.drawLine(tipX + sx * s * .11f, tipY, tipX, tipY, paint)
                    canvas.drawLine(tipX, tipY + sy * s * .11f, tipX, tipY, paint)
                } else {
                    val endX = cx + sx * s * .29f
                    val endY = cx + sy * s * .29f
                    canvas.drawLine(cx + sx * s * .09f, cx + sy * s * .09f, endX, endY, paint)
                    canvas.drawLine(endX - sx * s * .12f, endY, endX, endY, paint)
                    canvas.drawLine(endX, endY - sy * s * .12f, endX, endY, paint)
                }
            }
        }
        return OverlayCard(bitmap, bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
}
