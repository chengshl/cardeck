package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Only draws measured cell/Wi-Fi status; unknown levels are muted. */
class DockStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private var wifi = false
    private var cell = false
    private var wifiBars: Int? = null
    private var cellBars: Int? = null

    fun setIndicators(wifi: Boolean, wifiBars: Int?, cell: Boolean, cellBars: Int?) {
        if (this.wifi == wifi && this.wifiBars == wifiBars &&
            this.cell == cell && this.cellBars == cellBars) return
        this.wifi = wifi
        this.cell = cell
        this.wifiBars = wifiBars
        this.cellBars = cellBars
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.save()
        canvas.scale(width / 40f, height / 14f)
        for (i in 0..3) {
            paint.style = Paint.Style.FILL
            paint.alpha = when {
                !cell -> 35
                cellBars == null -> 96
                i < cellBars!! -> 245
                else -> 45
            }
            val x = 1.5f + i * 4f
            canvas.drawRoundRect(x, 12f - (i + 1) * 2.5f, x + 2.7f, 12.5f, 0.8f, 0.8f, paint)
        }
        for (i in 0..2) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.9f
            paint.alpha = when {
                !wifi -> 35
                wifiBars == null -> 96
                (2 - i) < wifiBars!! -> 245
                else -> 45
            }
            val inset = 2f + i * 4f
            canvas.drawArc(RectF(20.5f + inset, inset, 40.5f - inset, 19f - inset),
                217f, 106f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.alpha = if (wifi) 230 else 35
        canvas.drawCircle(30.5f, 12.2f, 1.5f, paint)
        canvas.restore()
    }
}
