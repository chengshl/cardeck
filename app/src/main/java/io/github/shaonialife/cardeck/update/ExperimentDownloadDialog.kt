package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.WindowManager
import io.github.shaonialife.cardeck.R

/** App内安装包下载的深色进度视图；不更改网络请求、文件校验和安装流程。 */
internal object ExperimentDownloadDialog {
    class Handle(private val dialog: Dialog, private val bar: ProgressBar, private val status: TextView) {
        var progress: Int
            get() = bar.progress
            set(value) { bar.progress = value.coerceIn(0, 100) }

        fun setStatus(message: String) { status.text = message }

        fun dismiss() = dialog.dismiss()
    }

    fun show(activity: Activity, title: String = "正在下载实验安装包"): Handle {
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density + .5f).toInt()
        val fg = activity.getColor(R.color.m3_on_surface)
        val muted = activity.getColor(R.color.m3_on_var)
        val accent = activity.getColor(R.color.m3_primary)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(19, 26, 32))
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), Color.rgb(62, 79, 88))
            }
        }
        card.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            setTextColor(fg)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(activity).apply {
            text = "下载后将进行 SHA-256 校验，再调用 Android 系统安装确认。"
            textSize = 12.5f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(accent)
        }
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(20) })
        val status = TextView(activity).apply {
            text = "请保持网络连接，验证完成后会提示安装。"
            textSize = 12f
            setTextColor(muted)
        }
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })
        dialog.setContentView(card)
        dialog.setCancelable(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout((activity.resources.displayMetrics.widthPixels * .9f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return Handle(dialog, bar, status)
    }
}
