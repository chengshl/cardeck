package io.github.shaonialife.cardeck.update

import android.app.Activity
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** User-triggered update prompts with the standalone Rescue dashboard palette. */
internal object RescueUpdateDialog {
    private val surface = Color.rgb(21, 31, 49)
    private val outline = Color.rgb(43, 59, 80)
    private val accent = Color.rgb(93, 227, 194)
    private val white = Color.rgb(241, 247, 252)
    private val muted = Color.rgb(156, 174, 197)

    fun show(
        activity: Activity,
        title: String,
        message: String,
        confirm: String? = "知道了",
        cancel: String? = null,
        cancelable: Boolean = true,
        onConfirm: (() -> Unit)? = null,
    ): AlertDialog {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val maxBodyHeight = (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(10))
        }
        content.addView(TextView(activity).apply {
            text = title
            textSize = 19f
            setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val boundedHeight = View.MeasureSpec.makeMeasureSpec(maxBodyHeight, View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, boundedHeight)
            }
        }.apply {
            isFillViewport = false
            setPadding(0, dp(13), 0, 0)
            addView(TextView(activity).apply {
                text = message
                textSize = 14f
                setTextColor(muted)
                setLineSpacing(dp(3).toFloat(), 1.08f)
            })
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val builder = AlertDialog.Builder(activity).setView(content).setCancelable(cancelable)
        if (confirm != null) builder.setPositiveButton(confirm) { _, _ -> onConfirm?.invoke() }
        if (cancel != null) builder.setNegativeButton(cancel, null)
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), outline)
        })
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            isAllCaps = false
            setTextColor(accent)
            backgroundTintList = ColorStateList.valueOf(outline)
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.apply {
            isAllCaps = false
            setTextColor(white)
            backgroundTintList = ColorStateList.valueOf(surface)
        }
        return dialog
    }
}
