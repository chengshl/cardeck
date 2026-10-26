package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** E5 专属深色更新弹窗，限制高度、防止底部操作随长文本滚走。 */
internal object UpdateDialogUi {
    private val bg = Color.rgb(16, 20, 24)
    private val surface = Color.rgb(29, 36, 43)
    private val border = Color.rgb(61, 74, 81)
    private val accent = Color.rgb(134, 216, 235)
    private val fg = Color.rgb(237, 244, 247)
    private val muted = Color.rgb(162, 176, 184)
    private val caution = Color.rgb(247, 197, 121)

    data class Choice(val title: String, val detail: String, val status: String, val selected: () -> Unit)

    class DownloadProgress(private val dialog: Dialog, private val progress: ProgressBar, private val label: TextView) {
        fun setProgress(value: Int) { progress.progress = value.coerceIn(0, 100) }
        fun setMessage(value: String) { label.text = value }
        fun dismiss() { dialog.dismiss() }
    }

    private fun dp(a: Activity, value: Int) = (value * a.resources.displayMetrics.density + 0.5f).toInt()
    private fun background(a: Activity, fill: Int, stroke: Int = fill, radius: Int = 14) =
        GradientDrawable().apply {
            cornerRadius = dp(a, radius).toFloat()
            setColor(fill)
            setStroke(dp(a, 1), stroke)
        }
    private fun label(a: Activity, content: String, size: Float, color: Int = fg, bold: Boolean = false) =
        TextView(a).apply {
            text = content
            textSize = size
            setTextColor(color)
            setLineSpacing(dp(a, 3).toFloat(), 1f)
            isSingleLine = false
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    private fun LinearLayout.line(a: Activity, view: View, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(a, top) })
    }
    private fun panel(a: Activity, heading: String, subheading: String) =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 18), dp(a, 18), dp(a, 18), dp(a, 18))
            background = background(a, bg, border, 21)
            line(a, label(a, "CARDECK · BUICK-E5", 11f, accent, true))
            line(a, label(a, heading, 20f, fg, true), 7)
            if (subheading.isNotBlank()) line(a, label(a, subheading, 12f, muted), 7)
        }
    private fun scroll(a: Activity) = object : ScrollView(a) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (resources.displayMetrics.heightPixels * .52f).toInt()
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
    }
    private fun button(a: Activity, caption: String, primary: Boolean, click: () -> Unit) =
        label(a, caption, 14f, if (primary) bg else accent, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(a, 48)
            setPadding(dp(a, 9), dp(a, 10), dp(a, 9), dp(a, 10))
            background = background(a, if (primary) accent else surface, if (primary) accent else border)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
    private fun show(a: Activity, content: LinearLayout, cancellable: Boolean = true): Dialog =
        Dialog(a).apply {
            setContentView(content)
            setCancelable(cancellable)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(.65f)
            show()
            window?.setLayout(min(a.resources.displayMetrics.widthPixels - dp(a, 28), dp(a, 470)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    fun choices(a: Activity, title: String, subtitle: String, items: List<Choice>) {
        val root = panel(a, title, subtitle)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroller = scroll(a).apply { addView(body) }
        root.line(a, scroller, 15)
        var dlg: Dialog? = null
        items.forEachIndexed { index, item ->
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12))
                background = background(a, surface, border)
                isClickable = true
                isFocusable = true
                contentDescription = item.title + "，" + item.status + "，" + item.detail
                setOnClickListener { dlg?.dismiss(); item.selected() }
            }
            val heading = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(label(a, item.title, 14f, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label(a, item.status, 11f, accent, true))
            card.line(a, heading)
            card.line(a, label(a, item.detail, 12f, muted), 7)
            body.line(a, card, if (index == 0) 0 else 9)
        }
        root.line(a, button(a, "关闭", false) { dlg?.dismiss() }, 15)
        dlg = show(a, root)
    }

    fun details(
        a: Activity, title: String, subtitle: String = "",
        fields: List<Pair<String, String>> = emptyList(), notes: String = "", warning: String = "",
        primary: String = "确定", onPrimary: () -> Unit = {},
        secondary: String = "取消", onSecondary: () -> Unit = {},
        tertiary: String? = null, onTertiary: () -> Unit = {},
    ) {
        val root = panel(a, title, subtitle)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fields.forEachIndexed { i, (key, value) ->
            body.line(a, label(a, key, 11.5f, muted, true), if (i == 0) 0 else 12)
            body.line(a, label(a, value, 13.5f), 4)
        }
        if (notes.isNotBlank()) {
            val note = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(a, 13), dp(a, 12), dp(a, 13), dp(a, 12))
                background = background(a, surface, border)
            }
            note.line(a, label(a, "更新内容", 12f, accent, true))
            note.line(a, label(a, notes, 13f), 5)
            body.line(a, note, 14)
        }
        if (warning.isNotBlank()) body.line(a, label(a, warning, 12.5f, caution), 12)
        root.line(a, scroll(a).apply { addView(body) }, 15)
        val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        var dlg: Dialog? = null
        actions.addView(button(a, secondary, false) { dlg?.dismiss(); onSecondary() },
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(a, primary, true) { dlg?.dismiss(); onPrimary() },
            LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginStart = dp(a, 9) })
        root.line(a, actions, 16)
        if (!tertiary.isNullOrBlank()) {
            root.line(a, button(a, tertiary, false) { dlg?.dismiss(); onTertiary() }, 8)
        }
        dlg = show(a, root)
    }

    fun message(a: Activity, title: String, text: String) =
        details(a, title, notes = text, primary = "确定", secondary = "关闭")

    fun progress(a: Activity, title: String, subtitle: String = "官方预发布 · SHA-256 校验"): DownloadProgress {
        val root = panel(a, title, subtitle)
        val detail = label(a, "正在准备下载…", 13f, muted)
        root.line(a, detail, 20)
        val progress = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(border)
        }
        root.line(a, progress, 14)
        return DownloadProgress(show(a, root, cancellable = false), progress, detail)
    }
}
