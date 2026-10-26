package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.shaonialife.cardeck.R

/** 更新器专属深色实验列表；不再弹粉色系统setItems原生Dialog。 */
internal object ExperimentPickerDialog {
    data class Item(val title: String, val detail: String, val selected: Boolean = false)

    fun show(
        activity: Activity,
        title: String,
        subtitle: String,
        items: List<Item>,
        onPick: (Int) -> Unit,
    ) {
        val d = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * d + .5f).toInt()
        val bg = Color.rgb(17, 23, 29)
        val card = Color.rgb(32, 41, 49)
        val selectedColor = Color.rgb(36, 63, 72)
        val accent = activity.getColor(R.color.m3_primary)
        val fg = activity.getColor(R.color.m3_on_surface)
        val muted = activity.getColor(R.color.m3_on_var)
        fun round(color: Int, border: Int = color): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), border)
            }
        fun text(value: String, size: Float, color: Int = fg, bold: Boolean = false) =
            TextView(activity).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(dp(3).toFloat(), 1f)
                isSingleLine = false
            }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = round(bg, Color.rgb(62, 76, 85))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text(title, 19f, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
        val cancel = text("关闭", 14f, accent, true).apply {
            setPadding(dp(8), dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(cancel)
        root.addView(header)
        root.addView(text(subtitle, 12f, muted), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(11)
        })
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = true }
        val choices = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, item ->
            val surface = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(12), dp(13))
                minimumHeight = dp(73)
                background = round(
                    if (item.selected) selectedColor else card,
                    if (item.selected) accent else Color.rgb(54, 66, 76),
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            }
            val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(text(item.title, 15f, fg, true))
            copy.addView(text(item.detail, 12f, muted), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(5)
            })
            surface.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            if (item.selected) {
                surface.addView(text("✓", 20f, accent, true).apply {
                    setPadding(dp(10), 0, 0, 0)
                })
            }
            choices.addView(surface, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(9)
            })
        }
        scroll.addView(choices)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val pixels = activity.resources.displayMetrics
            setLayout((pixels.widthPixels * .93f).toInt(), (pixels.heightPixels * .76f).toInt())
        }
    }
}
