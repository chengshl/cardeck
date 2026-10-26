package io.github.shaonialife.cardeck.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.shaonialife.cardeck.gateway.DiagnosticPreviewSummary

/**
 * 高对比度系统取证弹层：默认摘要，按需查看精选日志；滚动区与按钮分离。
 * 由EntryConfigActivity提供已脱敏行及确认回调，本类不采集、不上传、不留存日志。
 */
internal object DiagnosticPreviewDialog {
    data class Model(
        val lines: List<String>,
        val eventId: String?,
        val uploadedBefore: Boolean,
        val shizukuReady: Boolean,
        val uploadReady: Boolean,
        val detailedCount: Int? = null,
        val diagnosticType: String? = null,
    )

    fun show(
        activity: Activity,
        model: Model,
        onSave: () -> Unit,
        onUpload: () -> Unit,
    ): Dialog {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val backdrop = Color.rgb(17, 22, 29)
        val surface = Color.rgb(29, 37, 47)
        val selected = Color.rgb(39, 58, 73)
        val divider = Color.rgb(66, 82, 94)
        val foreground = Color.rgb(239, 246, 250)
        val secondary = Color.rgb(183, 199, 208)
        val accent = Color.rgb(142, 220, 241)
        val disabled = Color.rgb(120, 132, 140)

        fun shape(fill: Int, border: Int = fill, radius: Int = 14): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(dp(1), border)
            }

        fun text(value: String, size: Float, color: Int = foreground, bold: Boolean = false): TextView =
            TextView(activity).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                isSingleLine = false
                setLineSpacing(dp(3).toFloat(), 1f)
            }

        fun LinearLayout.place(view: View, top: Int = 0) {
            addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(top) })
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(16))
            background = shape(backdrop, divider, 20)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text((model.diagnosticType ?: "快速诊断") + " · 安全预览", 19f, foreground, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val close = text("关闭 ✕", 13f, accent, true).apply {
            isClickable = true
            isFocusable = true
            setPadding(dp(7), dp(6), 0, dp(6))
            contentDescription = "关闭诊断预览"
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close)
        root.place(header)

        val badge = text(
            DiagnosticPreviewSummary.headline(model.eventId, model.uploadedBefore),
            13.5f,
            if (model.uploadedBefore) Color.rgb(255, 208, 140) else accent,
            true,
        ).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = shape(selected)
        }
        root.place(badge, 13)
        val countLabel = if (model.detailedCount == null) {
            "精选 ${model.lines.size} / 最多256行  ·  系统事件原始时间单独标注"
        } else {
            "${model.diagnosticType ?: "详细故障"}：${model.detailedCount} 行 · 预览 ${model.lines.size} 行"
        }
        root.place(text(countLabel, 12f, secondary), 11)

        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val summaryTab = text("诊断摘要", 14f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(11), dp(10), dp(11))
            isClickable = true
            isFocusable = true
        }
        val rawTab = text("精选日志（${model.lines.size}行）", 14f, secondary, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(11), dp(10), dp(11))
            isClickable = true
            isFocusable = true
        }
        tabRow.addView(summaryTab, LinearLayout.LayoutParams(0, -2, 1f))
        tabRow.addView(rawTab, LinearLayout.LayoutParams(0, -2, 1f))
        root.place(tabRow, 13)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            background = shape(surface)
        }
        val content = text("", 13f, foreground).apply {
            setPadding(dp(12), dp(13), dp(12), dp(15))
            setTextIsSelectable(true)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(8)
        })
        val summaryText = DiagnosticPreviewSummary.compact(
            model.lines, model.eventId, model.uploadedBefore, model.shizukuReady,
            model.detailedCount, model.diagnosticType,
        )
        fun showTab(raw: Boolean) {
            summaryTab.background = shape(if (raw) surface else selected, if (raw) surface else accent)
            rawTab.background = shape(if (raw) selected else surface, if (raw) accent else surface)
            summaryTab.setTextColor(if (raw) secondary else accent)
            rawTab.setTextColor(if (raw) accent else secondary)
            content.text = if (raw) model.lines.joinToString("\n") else summaryText
            scroll.post { scroll.scrollTo(0, 0) }
        }
        summaryTab.setOnClickListener { showTab(false) }
        rawTab.setOnClickListener { showTab(true) }
        showTab(false)

        val note = text(
            if (model.detailedCount != null) {
                "保存详细文件只在本地；上传需手动确认，诊断发送可能较长，不会自动重传。"
            } else {
                "保存只在本地；在线上传必须手动确认，分批上报不包含完整2MB历史。"
            },
            11.5f, secondary,
        )
        root.place(note, 11)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun action(label: String, color: Int, background: Int, outline: Int): TextView =
            text(label, 13.5f, color, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(49)
                setPadding(dp(7), dp(10), dp(7), dp(10))
                this.background = shape(background, outline)
                isClickable = true
                isFocusable = true
            }
        val save = action("保存本地", accent, surface, accent)
        val upload = action(
            if (model.uploadReady) "确认分批上传" else "上传暂不可用",
            if (model.uploadReady) backdrop else disabled,
            if (model.uploadReady) accent else surface,
            if (model.uploadReady) accent else divider,
        )
        save.setOnClickListener {
            dialog.dismiss()
            onSave()
        }
        upload.isEnabled = model.uploadReady
        upload.setOnClickListener {
            if (!model.uploadReady) return@setOnClickListener
            dialog.dismiss()
            onUpload()
        }
        actions.addView(save, LinearLayout.LayoutParams(0, -2, 1f).apply {
            rightMargin = dp(6)
        })
        actions.addView(upload, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(6)
        })
        root.place(actions, 13)

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val metrics = activity.resources.displayMetrics
            setLayout(
                (metrics.widthPixels * 0.94f).toInt(),
                (metrics.heightPixels * 0.84f).toInt(),
            )
        }
        return dialog
    }
}
